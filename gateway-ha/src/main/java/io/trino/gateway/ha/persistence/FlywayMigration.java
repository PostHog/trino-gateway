/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.persistence;

import io.airlift.log.Logger;
import io.trino.gateway.ha.config.DataStoreConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class FlywayMigration
{
    private static final Logger log = Logger.get(FlywayMigration.class);

    private FlywayMigration() {}

    private static String getLocation(String configDbUrl)
    {
        if (configDbUrl.startsWith("jdbc:postgresql")) {
            return "postgresql";
        }
        if (configDbUrl.startsWith("jdbc:mysql")) {
            return "mysql";
        }
        if (configDbUrl.startsWith("jdbc:oracle")) {
            return "oracle";
        }
        throw new IllegalArgumentException("Invalid JDBC URL: %s. Only PostgreSQL, MySQL, and Oracle are supported.".formatted(configDbUrl));
    }

    public static void migrate(DataStoreConfiguration config)
    {
        if (!config.isRunMigrationsEnabled()) {
            log.info("Skip migrations as automatic migrations are disabled");
            return;
        }
        log.info("Performing migrations...");
        boolean postgres = config.getJdbcUrl().startsWith("jdbc:postgresql:");
        // Flyway's non-transactional lock (required for CREATE INDEX CONCURRENTLY in V14) fails
        // instead of waiting when another gateway pod holds it, crashing later pods on rolling
        // deploys. Hold our own advisory lock across renumber+repair+migrate so pods serialize;
        // the lock releases with the connection if the pod dies mid-migration.
        MigrationLock migrationLock = postgres ? MigrationLock.acquire(config) : MigrationLock.NONE;
        try {
            FluentConfiguration fluentConfiguration = Flyway.configure()
                    .dataSource(config.getJdbcUrl(), config.getUser(), config.getPassword())
                    .locations(getLocation(config.getJdbcUrl()))
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    // Renumbered fork migrations leave lower-numbered upstream migrations (V5, V6)
                    // pending after higher ones; they are new to such databases, not out of order.
                    .outOfOrder(true);
            if (postgres) {
                // A transaction-level lock prevents concurrent indexes from finishing their snapshot wait.
                fluentConfiguration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
            }
            Flyway flyway = fluentConfiguration.load();
            renumberLegacyForkMigrations(config);
            flyway.repair();
            MigrateResult migrations = flyway.migrate();
            log.info("Performed %s migrations", migrations.migrationsExecuted);
        }
        finally {
            migrationLock.release();
        }
    }

    private record MigrationLock(Connection connection)
    {
        private static final MigrationLock NONE = new MigrationLock(null);

        private static MigrationLock acquire(DataStoreConfiguration config)
        {
            try {
                Connection connection = java.sql.DriverManager.getConnection(config.getJdbcUrl(), config.getUser(), config.getPassword());
                connection.createStatement().execute("SELECT pg_advisory_lock(7272727, 7272727)");
                return new MigrationLock(connection);
            }
            catch (SQLException e) {
                throw new RuntimeException("Failed to acquire flyway migration lock", e);
            }
        }

        private void release()
        {
            if (connection != null) {
                try {
                    connection.createStatement().execute("SELECT pg_advisory_unlock(7272727, 7272727)");
                    connection.close();
                }
                catch (SQLException e) {
                    log.warn(e, "Failed to release flyway migration lock");
                }
            }
        }
    }

    // The 2026-10 upstream merge renumbered this fork's PostgreSQL migrations V5-V12 to V7-V14 to
    // make room for upstream's V5 (oauth2 routing) and V6 (backend tags). Databases that applied
    // the fork's original numbering would fail validation; rewriting the installed version rows
    // here and letting the repair() call afterwards realign the checksums avoids any manual
    // flyway repair step during rollout. A no-op once no legacy rows remain.

    private static void renumberLegacyForkMigrations(DataStoreConfiguration config)
    {
        // Keyed by script name, not by version: rows written by an older gateway build carry
        // the old script names, and only those rows are the legacy numbering. Idempotent and
        // safe against rows already renumbered or applied by a newer build.
        String caseStatement = "UPDATE flyway_schema_history SET version = CASE script " +
                "WHEN 'V5__transaction_awareness.sql' THEN '7' " +
                "WHEN 'V6__backend_incarnation_history.sql' THEN '8' " +
                "WHEN 'V7__query_capabilities.sql' THEN '9' " +
                "WHEN 'V8__drain_obligation_indexes.sql' THEN '10' " +
                "WHEN 'V9__cell_rollout_operations.sql' THEN '11' " +
                "WHEN 'V10__pool_member_lifecycle.sql' THEN '12' " +
                "WHEN 'V11__pool_tenant_principals.sql' THEN '13' " +
                "WHEN 'V12__drain_reconciliation_index.sql' THEN '14' END " +
                "WHERE script IN ('V5__transaction_awareness.sql', 'V6__backend_incarnation_history.sql', 'V7__query_capabilities.sql', 'V8__drain_obligation_indexes.sql', " +
                "'V9__cell_rollout_operations.sql', 'V10__pool_member_lifecycle.sql', 'V11__pool_tenant_principals.sql', 'V12__drain_reconciliation_index.sql')";
        try (Connection connection = java.sql.DriverManager.getConnection(config.getJdbcUrl(), config.getUser(), config.getPassword());
                Statement statement = connection.createStatement()) {
            int updated = statement.executeUpdate(caseStatement);
            if (updated > 0) {
                log.info("Renumbered %s legacy fork flyway history rows (V5-V12 -> V7-V14)", updated);
            }
        }
        catch (SQLException e) {
            // Table does not exist yet (fresh database) — nothing to renumber.
            log.debug("Skipping flyway history renumbering: %s", e.getMessage());
        }
    }
}
