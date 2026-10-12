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

import io.trino.gateway.ha.config.DataStoreConfiguration;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.trino.gateway.ha.util.TestcontainersUtils.createPostgreSqlContainer;
import static org.assertj.core.api.Assertions.assertThat;

final class TestDatabaseMigrationsPostgreSql
        extends BaseTestDatabaseMigrations
{
    public TestDatabaseMigrationsPostgreSql()
    {
        super(createPostgreSqlContainer(), "public");
    }

    @Override
    protected void createGatewaySchema()
    {
        String gatewayBackendTable =
                """
                CREATE TABLE gateway_backend (
                     name VARCHAR(256) PRIMARY KEY,
                     routing_group VARCHAR (256),
                     backend_url VARCHAR (256),
                     external_url VARCHAR (256),
                     active BOOLEAN
                );""";
        String queryHistoryTable =
                """
                CREATE TABLE query_history (
                     query_id VARCHAR(256) PRIMARY KEY,
                     query_text VARCHAR (256),
                     created bigint,
                     backend_url VARCHAR (256),
                     user_name VARCHAR(256),
                     source VARCHAR(256)
                );""";
        Handle jdbiHandle = jdbi.open();
        jdbiHandle.execute(gatewayBackendTable);
        jdbiHandle.execute(queryHistoryTable);
        jdbiHandle.close();
    }

    @Test
    public void testLegacyForkHistoryIsRenumbered()
    {
        // Schema + history shape of a deployment that applied this fork's original V5-V12
        // numbering, before the 2026-10 upstream merge renumbered them to V7-V14. The base
        // V1-V4 migrations are applied for real so the pending upstream V5/V6 (applied
        // out-of-order after renumbering) find the tables they alter.
        Path migrations = Path.of("src/main/resources/postgresql");
        jdbi.useHandle(handle -> {
            for (int version = 1; version <= 4; version++) {
                try {
                    handle.execute(Files.readString(migrations.resolve("V" + version + "__" + switch (version) {
                        case 1 -> "create_schema";
                        case 2 -> "add_routingGroup_to_query_history";
                        case 3 -> "add_externalUrl_to_query_history";
                        default -> "update_query_text_column_type";
                    } + ".sql")));
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            handle.execute("CREATE TABLE flyway_schema_history (installed_rank INT, version VARCHAR(50), description VARCHAR(200), type VARCHAR(20), script VARCHAR(1000), checksum INT, installed_by VARCHAR(100), installed_on TIMESTAMP, execution_time INT, success BOOLEAN)");
            for (int version = 1; version <= 12; version++) {
                handle.execute(
                        "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success) VALUES (?, ?, 'legacy', 'SQL', ?, 'test', now(), 0, true)",
                        version,
                        String.valueOf(version),
                        "V" + version + "__legacy.sql");
            }
        });
        DataStoreConfiguration config = new DataStoreConfiguration(
                container.getJdbcUrl(), container.getUsername(), container.getPassword(), null, true, 4, true, null);
        FlywayMigration.migrate(config);
        List<String> versions = jdbi.withHandle(handle -> handle.createQuery("SELECT version FROM flyway_schema_history WHERE type = 'SQL' AND version IS NOT NULL ORDER BY CAST(version AS INT)")
                .mapTo(String.class).list());
        assertThat(versions).contains("7", "8", "9", "10", "11", "12", "13", "14");
        List<String> legacyScripts = jdbi.withHandle(handle -> handle.createQuery("SELECT script FROM flyway_schema_history WHERE version IN ('5', '6') AND type = 'SQL'").mapTo(String.class).list());
        assertThat(legacyScripts)
                .describedAs("upstream V5 and V6 must be applied after renumbering, not left as legacy rows")
                .containsExactlyInAnyOrder("V5__add_oauth2_routing.sql", "V6__add_tags_to_gateway_backend.sql");
        dropAllTables();
    }

    @Override
    protected void verifyGatewaySchema()
    {
        super.verifyGatewaySchema();
        verifyResultSetCount("SELECT name FROM transaction_backend", 0);
        verifyResultSetCount("SELECT transaction_id FROM transaction_binding", 0);
        verifyResultSetCount("SELECT query_id FROM transaction_query", 0);
        verifyResultSetCount("SELECT query_id FROM transaction_query_capability", 0);
        verifyResultSetCount("SELECT admission_id FROM transaction_admission", 0);
        verifyResultSetCount("SELECT routing_group FROM transaction_route", 0);
        verifyResultSetCount("SELECT operation_id FROM transaction_rollout", 0);
        verifyResultSetCount("SELECT pool_id FROM pool", 0);
        verifyResultSetCount("SELECT operation_id FROM pool_operation", 0);
        verifyResultSetCount("SELECT incarnation::text FROM pool_member_certificate", 0);
        verifyResultSetCount("SELECT incarnation::text FROM pool_failure_receipt", 0);
        verifyResultSetCount("SELECT publication_id FROM pool_publication", 0);
        verifyResultSetCount("SELECT publication_id FROM pool_publication_receipt", 0);
        verifyResultSetCount("SELECT tenant FROM pool_tenant_admission", 0);
        verifyResultSetCount("SELECT principal FROM pool_tenant_principal", 0);
    }

    @Override
    protected void dropAllTables()
    {
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS pool_tenant_principal");
            handle.execute("DROP TABLE IF EXISTS pool_publication_receipt");
            handle.execute("DROP TABLE IF EXISTS pool_publication");
            handle.execute("DROP TABLE IF EXISTS pool_tenant_admission");
            handle.execute("DROP TABLE IF EXISTS pool_failure_receipt");
            handle.execute("DROP TABLE IF EXISTS pool_member_certificate");
            handle.execute("DROP TABLE IF EXISTS pool_operation");
            handle.execute("DROP TABLE IF EXISTS transaction_rollout");
            handle.execute("DROP TABLE IF EXISTS transaction_admission");
            handle.execute("DROP TABLE IF EXISTS transaction_query_capability");
            handle.execute("DROP TABLE IF EXISTS transaction_query");
            handle.execute("DROP TABLE IF EXISTS transaction_binding");
            // These tables reference each other, so remove them in one statement.
            handle.execute("DROP TABLE IF EXISTS transaction_backend, pool, transaction_route");
        });
        super.dropAllTables();
    }
}
