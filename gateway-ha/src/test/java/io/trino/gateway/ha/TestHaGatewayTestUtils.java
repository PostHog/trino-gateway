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
package io.trino.gateway.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestHaGatewayTestUtils
{
    private static final Map<String, String> VARIABLES = Map.of(
            "REQUEST_ROUTER_PORT", "8080",
            "POSTGRESQL_JDBC_URL", "jdbc:postgresql://localhost/unused_fixture_database",
            "POSTGRESQL_USER", "synthetic-fixture",
            "POSTGRESQL_PASSWORD", "synthetic-fixture-password");

    @Test
    void stockFixturesAreNotForcedIntoPooledMode()
            throws Exception
    {
        JsonNode configuration = configuration("test-config-with-forwarded-headers-disabled-template.yml");
        assertThat(configuration.path("routing").path("forwardedHeadersEnabled").asBoolean()).isFalse();
        assertThat(configuration.path("transactionAwareness").isMissingNode()).isTrue();
        assertThat(configuration.path("dataStore").path("jdbcUrl").asText()).isEqualTo(VARIABLES.get("POSTGRESQL_JDBC_URL"));
    }

    @Test
    void stockFixturePreservesAuthenticationAndAuthorization()
            throws Exception
    {
        JsonNode configuration = configuration("auth/auth-test-config.yml");
        assertThat(configuration.path("authentication").path("defaultType").get(0).asText()).isEqualTo("form");
        assertThat(configuration.path("authorization").path("admin").asText()).isEqualTo(".*FOO.*");
        assertThat(configuration.path("presetUsers").path("admin1").path("privileges").asText()).isEqualTo("FOO_BAR");
    }

    private static JsonNode configuration(String resource)
            throws Exception
    {
        File file = HaGatewayTestUtils.buildGatewayConfig(resource, VARIABLES);
        try {
            return new ObjectMapper(new YAMLFactory()).readTree(file);
        }
        finally {
            Files.delete(file.toPath());
        }
    }
}
