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
package io.trino.gateway.proxyserver;

import io.trino.gateway.ha.router.QueryHistoryManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.trino.gateway.ha.handler.HttpUtils.V1_STATEMENT_PATH;
import static io.trino.gateway.ha.handler.ProxyUtils.SOURCE_HEADER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

final class TestProxyRequestDetails
{
    @Test
    void testGetQueryDetailsFromRequest()
    {
        // A sample query longer than 200 characters to test against truncation.
        String longQuery =
                """
                SELECT
                    c.customer_name,
                    c.customer_region,
                    COUNT(o.order_id) AS total_orders,
                    SUM(o.order_value) AS total_revenue
                FROM
                    hive.sales_data.customers AS c
                JOIN
                    hive.sales_data.orders AS o
                        ON c.customer_id = o.customer_id
                WHERE
                o.order_date >= date '2023-01-01'""";

        io.airlift.http.client.Request request = preparePost()
                .setUri(URI.create("http://localhost:8080" + V1_STATEMENT_PATH))
                .addHeader(SOURCE_HEADER, "trino-cli")
                .setBodyGenerator(createStaticBodyGenerator(longQuery, UTF_8))
                .build();

        Optional<String> username = Optional.of("test_user");

        QueryHistoryManager.QueryDetail queryDetail = ProxyRequestHandler.getQueryDetailsFromRequest(request, username);

        assertThat(queryDetail.getQueryText()).isEqualTo(longQuery).hasSizeGreaterThan(200);
        assertThat(queryDetail.getUser()).isEqualTo(username.get());
        assertThat(queryDetail.getSource()).isEqualTo("trino-cli");
        assertThat(queryDetail.getBackendUrl()).isEqualTo("http://localhost:8080");
    }
}
