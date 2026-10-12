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

import java.net.URI;
import java.net.URLDecoder;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Runs the gateway database migrations against the PostgreSQL database named by
 * {@code DATABASE_URL} ({@code postgres://user:password@host[:port]/database}).
 * {@code GATEWAY_DB_SSLMODE} sets the JDBC sslmode and defaults to {@code require}.
 * Deployments run this class as the pre-sync migration job.
 */
public final class MigrateFromEnvironment
{
    private MigrateFromEnvironment() {}

    public static void main(String[] args)
    {
        FlywayMigration.migrate(dataStoreConfiguration(System.getenv()));
    }

    static DataStoreConfiguration dataStoreConfiguration(Map<String, String> environment)
    {
        URI source = URI.create(required(environment, "DATABASE_URL"));
        if (!"postgres".equals(source.getScheme()) && !"postgresql".equals(source.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must use the postgres or postgresql scheme");
        }
        String rawUserInfo = source.getRawUserInfo();
        if (rawUserInfo == null) {
            throw new IllegalArgumentException("DATABASE_URL must contain user information");
        }
        String[] userInfo = rawUserInfo.split(":", 2);
        if (userInfo.length != 2) {
            throw new IllegalArgumentException("DATABASE_URL must contain a username and password");
        }
        String user = decodeUserInfo(userInfo[0]);
        String password = decodeUserInfo(userInfo[1]);
        if (source.getHost() == null || source.getRawPath() == null || source.getRawPath().length() < 2) {
            throw new IllegalArgumentException("DATABASE_URL must contain a host and database");
        }
        int port = source.getPort() == -1 ? 5432 : source.getPort();
        String sslMode = environment.getOrDefault("GATEWAY_DB_SSLMODE", "require");
        String jdbcUrl = "jdbc:postgresql://%s:%d%s?sslmode=%s".formatted(source.getHost(), port, source.getRawPath(), sslMode);

        DataStoreConfiguration config = new DataStoreConfiguration();
        config.setJdbcUrl(jdbcUrl);
        config.setUser(user);
        config.setPassword(password);
        config.setDriver("org.postgresql.Driver");
        config.setQueryHistoryEnabled(true);
        config.setQueryHistoryHoursRetention(24);
        config.setRunMigrationsEnabled(true);
        return config;
    }

    // URLDecoder turns '+' into a space; user information is not form encoded, so keep it literal.
    private static String decodeUserInfo(String value)
    {
        return URLDecoder.decode(value.replace("+", "%2B"), UTF_8);
    }

    private static String required(Map<String, String> environment, String name)
    {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
