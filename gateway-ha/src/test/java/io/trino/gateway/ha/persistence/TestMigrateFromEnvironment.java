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
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.util.Map;
import java.util.UUID;

import static io.trino.gateway.ha.persistence.MigrateFromEnvironment.dataStoreConfiguration;
import static io.trino.gateway.ha.util.TestcontainersUtils.createPostgreSqlContainer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestMigrateFromEnvironment
{
    @ParameterizedTest
    @ValueSource(strings = {"postgres", "postgresql"})
    void testBuildsConfiguration(String scheme)
    {
        DataStoreConfiguration config = dataStoreConfiguration(Map.of("DATABASE_URL", scheme + "://gateway:secret@db.internal:6543/gateway"));

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/gateway?sslmode=require");
        assertThat(config.getUser()).isEqualTo("gateway");
        assertThat(config.getPassword()).isEqualTo("secret");
        assertThat(config.getDriver()).isEqualTo("org.postgresql.Driver");
        assertThat(config.isQueryHistoryEnabled()).isTrue();
        assertThat(config.getQueryHistoryHoursRetention()).isEqualTo(24);
        assertThat(config.isRunMigrationsEnabled()).isTrue();
    }

    @Test
    void testDefaultPort()
    {
        assertThat(dataStoreConfiguration(Map.of("DATABASE_URL", "postgres://gateway:secret@db.internal/gateway")).getJdbcUrl())
                .isEqualTo("jdbc:postgresql://db.internal:5432/gateway?sslmode=require");
    }

    @Test
    void testSslModeOverride()
    {
        Map<String, String> environment = Map.of(
                "DATABASE_URL", "postgres://gateway:secret@db.internal/gateway",
                "GATEWAY_DB_SSLMODE", "verify-full");
        assertThat(dataStoreConfiguration(environment).getJdbcUrl())
                .isEqualTo("jdbc:postgresql://db.internal:5432/gateway?sslmode=verify-full");
    }

    @Test
    void testDecodesUserInformation()
    {
        DataStoreConfiguration config = dataStoreConfiguration(Map.of("DATABASE_URL", "postgres://gate%40way:p%3Ass%2Fw+rd%25@db.internal/gateway"));

        assertThat(config.getUser()).isEqualTo("gate@way");
        assertThat(config.getPassword()).isEqualTo("p:ss/w+rd%");
    }

    @Test
    void testPasswordMayContainColon()
    {
        assertThat(dataStoreConfiguration(Map.of("DATABASE_URL", "postgres://gateway:a:b@db.internal/gateway")).getPassword())
                .isEqualTo("a:b");
    }

    @Test
    void testRequiresDatabaseUrl()
    {
        assertRejected(Map.of(), "DATABASE_URL is required");
        assertRejected(Map.of("DATABASE_URL", " "), "DATABASE_URL is required");
    }

    @Test
    void testRejectsInvalidUrls()
    {
        assertRejected("mysql://gateway:secret@db.internal/gateway", "DATABASE_URL must use the postgres or postgresql scheme");
        assertRejected("db.internal/gateway", "DATABASE_URL must use the postgres or postgresql scheme");
        assertRejected("postgres://db.internal/gateway", "DATABASE_URL must contain user information");
        assertRejected("postgres://gateway@db.internal/gateway", "DATABASE_URL must contain a username and password");
        assertRejected("postgres://gateway:secret@db.internal", "DATABASE_URL must contain a host and database");
        assertRejected("postgres://gateway:secret@db.internal/", "DATABASE_URL must contain a host and database");
    }

    @Test
    void testMigratesDatabase()
    {
        // The same database the transaction tests use: the CI PostgreSQL service, or a container locally.
        String url = System.getenv("TX_STORE_TEST_JDBC_URL");
        PostgreSQLContainer container = null;
        String username;
        String password;
        if (url == null) {
            container = createPostgreSqlContainer();
            container.start();
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
        else {
            username = requireNonNull(System.getenv("TX_STORE_TEST_USERNAME"), "TX_STORE_TEST_USERNAME is required");
            password = System.getenv().getOrDefault("TX_STORE_TEST_PASSWORD", "");
        }
        Jdbi admin = Jdbi.create(url, username, password);
        String database = "migrate_from_environment_" + UUID.randomUUID().toString().replace("-", "");
        admin.useHandle(handle -> handle.execute("CREATE DATABASE " + database));
        try {
            URI server = URI.create(url.substring("jdbc:".length()));
            Map<String, String> environment = Map.of(
                    "DATABASE_URL", "postgresql://%s:%s@%s:%d/%s".formatted(
                            URLEncoder.encode(username, UTF_8), URLEncoder.encode(password, UTF_8), server.getHost(), server.getPort(), database),
                    "GATEWAY_DB_SSLMODE", "disable");

            FlywayMigration.migrate(dataStoreConfiguration(environment));
            // A second run, as on every later sync, finds nothing to do.
            FlywayMigration.migrate(dataStoreConfiguration(environment));

            Jdbi migrated = Jdbi.create(url.replaceFirst("/[^/?]*(\\?|$)", "/" + database + "$1"), username, password);
            assertThat(count(migrated, "SELECT count(*) FROM flyway_schema_history WHERE NOT success")).isZero();
            assertThat(count(migrated, "SELECT count(*) FROM gateway_backend")).isZero();
            assertThat(count(migrated, "SELECT count(*) FROM transaction_backend")).isZero();
        }
        finally {
            admin.useHandle(handle -> handle.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)"));
            if (container != null) {
                container.close();
            }
        }
    }

    private static int count(Jdbi jdbi, String query)
    {
        return jdbi.withHandle(handle -> handle.createQuery(query).mapTo(int.class).one());
    }

    private static void assertRejected(String databaseUrl, String message)
    {
        assertRejected(Map.of("DATABASE_URL", databaseUrl), message);
    }

    private static void assertRejected(Map<String, String> environment, String message)
    {
        assertThatThrownBy(() -> dataStoreConfiguration(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
