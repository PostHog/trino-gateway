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

import io.trino.gateway.ha.HaGatewayLauncher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.trino.gateway.ha.HaGatewayTestUtils.buildGatewayConfig;
import static io.trino.gateway.ha.HaGatewayTestUtils.prepareMockBackend;
import static io.trino.gateway.ha.HaGatewayTestUtils.setUpBackend;
import static io.trino.gateway.ha.util.TestcontainersUtils.createPostgreSqlContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

/**
 * The Gateway behind a TLS-terminating load balancer, on an ordinary (non-pooled) route: with
 * {@code routing.forwardedProto} and {@code routing.forwardedPort} configured, the proxy asserts the
 * external protocol and port to the backend and drops every client-supplied forwarded header, both the
 * legacy {@code X-Forwarded-*} family and RFC 7239 {@code Forwarded}. A coordinator running with
 * {@code http-server.process-forwarded=true} would otherwise be free to prefer the client's claim.
 */
@TestInstance(PER_CLASS)
final class TestForwardedHeadersAsserted
{
    private static final MediaType MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String CUSTOM_ENDPOINT = "/v1/custom";

    private final OkHttpClient httpClient = new OkHttpClient();
    private final MockWebServer backend = new MockWebServer();
    private final PostgreSQLContainer postgresql = createPostgreSqlContainer();
    private final int routerPort = 23001 + (int) (Math.random() * 1000);
    private final int backendPort = 24001 + (int) (Math.random() * 1000);

    @BeforeAll
    void setup()
            throws Exception
    {
        prepareMockBackend(backend, backendPort, "default response");
        backend.setDispatcher(new Dispatcher()
        {
            @Override
            public MockResponse dispatch(RecordedRequest request)
            {
                if (request.getPath().equals("/v1/info")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader(CONTENT_TYPE, JSON_UTF_8)
                            .setBody("{\"starting\": false}");
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader(CONTENT_TYPE, JSON_UTF_8)
                        .setBody("OK");
            }
        });

        postgresql.start();
        File configFile = buildGatewayConfig(postgresql, routerPort, "test-config-with-forwarded-headers-asserted-template.yml");
        HaGatewayLauncher.main(new String[] {configFile.getAbsolutePath()});
        setUpBackend("asserted", "http://localhost:" + backendPort, "externalUrl", true, "adhoc", routerPort);
    }

    @AfterAll
    void cleanup()
            throws Exception
    {
        backend.shutdown();
    }

    @Test
    void theBackendSeesOnlyTheAssertedForwardedMetadata()
            throws Exception
    {
        Request request = new Request.Builder()
                .url("http://localhost:" + routerPort + CUSTOM_ENDPOINT)
                .put(RequestBody.create("SELECT 1", MEDIA_TYPE))
                // The server drops X-Forwarded-* itself (process-forwarded: ignore); Forwarded reaches
                // the proxy and must be dropped there.
                .header("Forwarded", "host=other-tenant.example;proto=https")
                .header("X-Forwarded-Host", "other-tenant.example")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "8080")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }

        RecordedRequest recorded = takeCustomRequest();
        assertThat(recorded.getHeaders().values("Forwarded")).isEmpty();
        assertThat(recorded.getHeaders().values("X-Forwarded-Proto")).containsExactly("https");
        assertThat(recorded.getHeaders().values("X-Forwarded-Port")).containsExactly("443");
        assertThat(recorded.getHeaders().values("X-Forwarded-Host")).containsExactly("localhost");
        assertThat(recorded.getHeaders().values("X-Forwarded-For")).hasSize(1);
        assertThat(recorded.getHeaders().values("X-Forwarded-For")).doesNotContain("other-tenant.example");
    }

    private RecordedRequest takeCustomRequest()
            throws InterruptedException
    {
        while (true) {
            RecordedRequest recorded = backend.takeRequest(10, TimeUnit.SECONDS);
            assertThat(recorded).isNotNull();
            if (recorded.getPath().equals(CUSTOM_ENDPOINT)) {
                return recorded;
            }
        }
    }
}
