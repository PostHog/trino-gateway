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
package io.trino.gateway.ha.handler;

import io.airlift.http.client.Request;
import io.trino.gateway.ha.router.QueryHistoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.Optional;

import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.trino.gateway.ha.handler.QueryTextRedaction.REDACTED_STATEMENT;
import static io.trino.gateway.ha.handler.QueryTextRedaction.mayDefineCatalogProperties;
import static io.trino.gateway.ha.handler.QueryTextRedaction.recordedText;
import static io.trino.gateway.proxyserver.ProxyRequestHandler.getQueryDetailsFromRequest;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

final class TestQueryTextRedaction
{
    private static final String SECRET = "redaction-test-secret";
    private static final String CREATE_CATALOG = "CREATE CATALOG ext_tenant__db USING postgresql WITH (\"connection-password\" = '" + SECRET + "')";

    @Test
    void testLexicalCheck()
    {
        assertThat(mayDefineCatalogProperties(CREATE_CATALOG)).isTrue();
        assertThat(mayDefineCatalogProperties("  create\n\tcatalog c")).isTrue();
        assertThat(mayDefineCatalogProperties("CREATE/* c */CATALOG")).isTrue();
        assertThat(mayDefineCatalogProperties("EXECUTE IMMEDIATE 'CREATE CATALOG c USING x'")).isTrue();
        assertThat(mayDefineCatalogProperties("SELECT '--' ; CREATE/*x*/CATALOG c USING x")).isTrue();
        assertThat(mayDefineCatalogProperties("{\"query\":\"create catalog c using x\"}")).isTrue();

        assertThat(mayDefineCatalogProperties("SELECT 1")).isFalse();
        assertThat(mayDefineCatalogProperties("SELECT * FROM create_catalog")).isFalse();
        assertThat(mayDefineCatalogProperties("DROP CATALOG c")).isFalse();
        assertThat(mayDefineCatalogProperties("CATALOG then CREATE")).isFalse();
    }

    @Test
    @Timeout(10)
    void testLexicalCheckIsLinear()
    {
        assertThat(mayDefineCatalogProperties("CREATE ".repeat(500_000))).isFalse();
        assertThat(mayDefineCatalogProperties("CREATE/*" + "*//*".repeat(500_000) + "CATALOG")).isTrue();
    }

    @Test
    void testRecordedText()
    {
        assertThat(recordedText(CREATE_CATALOG)).isEqualTo(REDACTED_STATEMENT);
        assertThat(recordedText("SELECT 1")).isEqualTo("SELECT 1");
        assertThat(recordedText(null)).isNull();
    }

    @Test
    void testQueryHistoryNeverRecordsCatalogProperties()
    {
        QueryHistoryManager.QueryDetail detail = getQueryDetailsFromRequest(statement(CREATE_CATALOG), Optional.of("tenant.root"));
        assertThat(detail.getQueryText()).isEqualTo(REDACTED_STATEMENT).doesNotContain(SECRET);

        detail = getQueryDetailsFromRequest(statement("SELECT 42"), Optional.of("tenant.root"));
        assertThat(detail.getQueryText()).isEqualTo("SELECT 42");
    }

    private static Request statement(String sql)
    {
        return Request.builder()
                .setUri(URI.create("http://coordinator:8080/v1/statement"))
                .setMethod("POST")
                .setBodyGenerator(createStaticBodyGenerator(sql, UTF_8))
                .build();
    }
}
