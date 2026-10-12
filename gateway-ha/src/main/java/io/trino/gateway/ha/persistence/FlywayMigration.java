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
        FluentConfiguration fluentConfiguration = Flyway.configure()
                .dataSource(config.getJdbcUrl(), config.getUser(), config.getPassword())
                .locations(getLocation(config.getJdbcUrl()))
                .baselineOnMigrate(true)
                .baselineVersion("0")
                // Renumbered fork migrations leave lower-numbered upstream migrations (V5, V6)
                // pending after higher ones; they are new to such databases, not out of order.
                .outOfOrder(true);
        if (config.getJdbcUrl().startsWith("jdbc:postgresql:")) {
            // A transaction-level lock prevents concurrent indexes from finishing their snapshot wait.
            fluentConfiguration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        }
        Flyway flyway = fluentConfiguration.load();
        renumberLegacyForkMigrations(config);
        flyway.repair();
        MigrateResult migrations = flyway.migrate();
        log.info("Performed %s migrations", migrations.migrationsExecuted);
    }

    // The 2026-10 upstream merge renumbered this fork's PostgreSQL migrations V5-V12 to V7-V14 to
    // make room for upstream's V5 (oauth2 routing) and V6 (backend tags). Databases that applied
    // the fork's original numbering would fail validation; rewriting the installed version rows
    // here and letting the repair() call afterwards realign the checksums avoids any manual
    // flyway repair step during rollout. A no-op once no legacy rows remain.

    private static void renumberLegacyForkMigrations(DataStoreConfiguration config)
    {
        // Runs before flyway.migrate(), whose advisory/increment locks do not cover this rewrite;
        // a PostgreSQL advisory lock serializes concurrent gateway pods, and the single CASE
        // statement keeps the rewrite atomic per row so overlapping non-PostgreSQL pods converge.
        boolean postgres = config.getJdbcUrl().startsWith("jdbc:postgresql:");
        String caseStatement = "UPDATE flyway_schema_history SET version = CASE version " +
                "WHEN '5' THEN '7' WHEN '6' THEN '8' WHEN '7' THEN '9' WHEN '8' THEN '10' " +
                "WHEN '9' THEN '11' WHEN '10' THEN '12' WHEN '11' THEN '13' WHEN '12' THEN '14' END " +
                "WHERE version IN ('5', '6', '7', '8', '9', '10', '11', '12') AND script ~ '^V(5|6|7|8|9|10|11|12)__'";
        try (Connection connection = java.sql.DriverManager.getConnection(config.getJdbcUrl(), config.getUser(), config.getPassword());
                Statement statement = connection.createStatement()) {
            if (postgres) {
                statement.execute("SELECT pg_advisory_lock(7272727)");
            }
            try {
                int updated = statement.executeUpdate(postgres ? caseStatement : caseStatement.replace("~", "REGEXP"));
                if (updated > 0) {
                    log.info("Renumbered %s legacy fork flyway history rows (V5-V12 -> V7-V14)", updated);
                }
            }
            catch (SQLException e) {
                // Table does not exist yet (fresh database) — nothing to renumber.
                log.debug("Skipping flyway history renumbering: %s", e.getMessage());
            }
            if (postgres) {
                statement.execute("SELECT pg_advisory_unlock(7272727)");
            }
        }
        catch (SQLException e) {
            throw new RuntimeException("Failed to renumber legacy fork migrations in flyway_schema_history", e);
        }
    }
}
