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

import com.google.common.collect.ImmutableListMultimap;
import io.airlift.http.client.HeaderName;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Request;
import io.airlift.http.client.testing.TestingHttpClient;
import io.airlift.http.client.testing.TestingResponse;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.handler.schema.RoutingDestination;
import io.trino.gateway.ha.router.HostnameRoutingGroupSelector;
import io.trino.gateway.ha.router.OAuth2RoutingStore;
import io.trino.gateway.ha.router.QueryHistoryManager;
import io.trino.gateway.ha.router.TrinoRequestUser;
import io.trino.gateway.ha.testing.TestingHttpServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.trino.gateway.ha.handler.HttpUtils.TRINO_REQUEST_USER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.enumeration;
import static java.util.Collections.list;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

final class TestHealthOnlyProxyHeaders
{
    @Test
    void testLegacyRouteCookieCannotOverrideHostname()
    {
        HaGatewayConfiguration config = new HaGatewayConfiguration();
        config.getRouting().setHostnameToPool(Map.of("pool-a.example", "pool-a", "pool-b.example", "pool-b"));
        HostnameRoutingGroupSelector selector = new HostnameRoutingGroupSelector(config.getRouting());
        TestingHttpServletRequest request = new TestingHttpServletRequest("/v1/statement")
        {
            @Override
            public Cookie[] getCookies()
            {
                throw new AssertionError("Hostname selection must not inspect backend-routing cookies");
            }
        };
        request.addHeader("Host", "pool-a.example");
        request.addHeader("Cookie", "TG.OAUTH2=backend-b; TG.ROUTE=pool-b; Trino-UI-Token=synthetic");
        request.addHeader("X-Trino-Routing-Group", "pool-b");
        assertThat(selector.findRoutingDestination(request).routingGroup()).isEqualTo("pool-a");

        TestingHttpServletRequest unknown = new TestingHttpServletRequest("/v1/statement");
        unknown.addHeader("Host", "unknown.example");
        unknown.addHeader("Cookie", "TG.OAUTH2=backend-b; TG.ROUTE=pool-b");
        unknown.addHeader("X-Trino-Routing-Group", "pool-b");
        assertThatThrownBy(() -> selector.findRoutingDestination(unknown))
                .isInstanceOfSatisfying(WebApplicationException.class, failure -> assertThat(failure.getResponse().getStatus()).isEqualTo(421));
    }

    @Test
    void testCoordinatorAuthCookiesPassThroughWithoutGatewayRouteCookie()
    {
        HaGatewayConfiguration config = new HaGatewayConfiguration();
        AtomicReference<Request> forwarded = new AtomicReference<>();
        try (TestingHttpClient client = new TestingHttpClient(request -> {
            forwarded.set(request);
            return new TestingResponse(
                    HttpStatus.OK,
                    ImmutableListMultimap.of(HeaderName.of("Set-Cookie"), "Trino-UI-Token=synthetic; Secure; HttpOnly"),
                    "{}".getBytes(UTF_8));
        })) {
            ProxyRequestHandler handler = new ProxyRequestHandler(client, mock(QueryHistoryManager.class), mock(OAuth2RoutingStore.class), config);
            try {
                TestingHttpServletRequest request = new TestingHttpServletRequest("/oauth2/token/initiate/HASH");
                request.addHeader("Cookie", "Trino-UI-Token=synthetic; TG.OAUTH2=obsolete");
                AsyncResponse response = mock(AsyncResponse.class);
                handler.getRequest(
                        request,
                        response,
                        new RoutingDestination("pool-a", "backend-a", URI.create("http://backend-a:8080/oauth2/token/initiate/HASH"), null));
                ArgumentCaptor<Response> completed = ArgumentCaptor.forClass(Response.class);
                verify(response, timeout(5000)).resume(completed.capture());
                assertThat(forwarded.get().getHeader("Cookie")).isEqualTo("Trino-UI-Token=synthetic; TG.OAUTH2=obsolete");
                assertThat(completed.getValue().getStringHeaders().get("Set-Cookie"))
                        .containsExactly("Trino-UI-Token=synthetic; Secure; HttpOnly");
            }
            finally {
                handler.shutdown();
            }
        }
    }

    @Test
    void testQueryHistoryRemainsIndependentOfRouting()
    {
        HaGatewayConfiguration config = new HaGatewayConfiguration();
        QueryHistoryManager history = mock(QueryHistoryManager.class);
        try (TestingHttpClient client = new TestingHttpClient(_ -> TestingResponse.mockResponse(HttpStatus.OK, JSON_UTF_8, "{\"id\":\"query-a\"}"))) {
            ProxyRequestHandler handler = new ProxyRequestHandler(client, history, mock(OAuth2RoutingStore.class), config);
            try {
                TestingHttpServletRequest request = new TestingHttpServletRequest("/v1/statement");
                request.setAttribute(TRINO_REQUEST_USER, new TrinoRequestUser(Optional.of("alice"), null));
                handler.postRequest(
                        "SELECT 1",
                        request,
                        mock(AsyncResponse.class),
                        new RoutingDestination("pool-a", "backend-a", URI.create("http://backend-a:8080/v1/statement"), "https://backend-a.example"));
                ArgumentCaptor<QueryHistoryManager.QueryDetail> detail = ArgumentCaptor.forClass(QueryHistoryManager.QueryDetail.class);
                verify(history, timeout(5000)).submitQueryDetail(detail.capture());
                assertThat(detail.getValue().getQueryId()).isEqualTo("query-a");
                assertThat(detail.getValue().getBackendUrl()).isEqualTo("http://backend-a:8080");
                assertThat(detail.getValue().getRoutingGroup()).isEqualTo("pool-a");
                assertThat(detail.getValue().getExternalUrl()).isEqualTo("https://backend-a.example");
                assertThat(detail.getValue().getQueryText()).isEqualTo("SELECT 1");
                assertThat(detail.getValue().getUser()).isEqualTo("alice");
            }
            finally {
                handler.shutdown();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "X-Trino-Routing-Group, true",
            "x-trino-routing-group, true",
            "X-TRINO-ROUTING-GROUP, true",
            "X-Trino-Routing-Group, false",
            "x-trino-routing-group, false",
            "X-TRINO-ROUTING-GROUP, false",
    })
    void testLegacyRoutingHeaderCannotReachBackend(String routingHeader, boolean forwardedEnabled)
            throws InterruptedException
    {
        HaGatewayConfiguration config = new HaGatewayConfiguration();
        config.getRouting().setForwardedHeadersEnabled(forwardedEnabled);
        if (forwardedEnabled) {
            config.getRouting().setForwardedProto("https");
            config.getRouting().setForwardedPort(443);
        }
        AtomicReference<Request> forwarded = new AtomicReference<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        try (TestingHttpClient client = new TestingHttpClient(request -> {
            forwarded.set(request);
            dispatched.countDown();
            return TestingResponse.mockResponse(HttpStatus.OK, JSON_UTF_8, "{}");
        })) {
            ProxyRequestHandler handler = new ProxyRequestHandler(client, mock(QueryHistoryManager.class), mock(OAuth2RoutingStore.class), config);
            try {
                TestingHttpServletRequest request = new TestingHttpServletRequest("/v1/custom")
                {
                    @Override
                    public Enumeration<String> getHeaderNames()
                    {
                        // Preserve the legacy header's actual casing in the enumeration consumed
                        // by the proxy, rather than the testing request's normalized map key.
                        return enumeration(list(super.getHeaderNames()).stream()
                                .map(name -> name.equalsIgnoreCase(routingHeader) ? routingHeader : name)
                                .toList());
                    }

                    @Override
                    public String getServerName()
                    {
                        return "gateway.example";
                    }

                    @Override
                    public String getRemoteAddr()
                    {
                        return "192.0.2.10";
                    }
                };
                request.addHeader(routingHeader, "legacy-group");
                request.addHeader(routingHeader, "another-group");
                request.addHeader("Authorization", "Bearer synthetic-token");
                request.addHeader("X-Trino-User", "alice");
                request.addHeader("Cookie", "Trino-UI-Token=synthetic; TG.OAUTH2=obsolete");
                request.addHeader("Forwarded", "host=untrusted.example;proto=http");
                request.addHeader("X-Forwarded-Host", "untrusted.example");
                request.addHeader("X-Forwarded-Proto", "http");
                request.addHeader("X-Forwarded-Port", "8080");
                request.addHeader("X-Forwarded-Custom", "untrusted");
                request.addHeader("X-Forwarded-For", "198.51.100.10");
                handler.getRequest(request, mock(AsyncResponse.class), new RoutingDestination("pool-a", "backend-a", URI.create("http://backend-a:8080/v1/custom"), null));
                assertThat(dispatched.await(5, SECONDS)).isTrue();
                Request actual = forwarded.get();
                assertThat(actual.getHeader("X-Trino-Routing-Group")).isNull();
                assertThat(actual.getHeader("Authorization")).isEqualTo("Bearer synthetic-token");
                assertThat(actual.getHeader("X-Trino-User")).isEqualTo("alice");
                assertThat(actual.getHeader("Cookie")).isEqualTo("Trino-UI-Token=synthetic; TG.OAUTH2=obsolete");
                assertThat(actual.getHeader("Forwarded")).isNull();
                assertThat(actual.getHeader("X-Forwarded-Custom")).isNull();
                if (forwardedEnabled) {
                    assertThat(actual.getHeader("X-Forwarded-Host")).isEqualTo("gateway.example");
                    assertThat(actual.getHeader("X-Forwarded-Proto")).isEqualTo("https");
                    assertThat(actual.getHeader("X-Forwarded-Port")).isEqualTo("443");
                    assertThat(actual.getHeader("X-Forwarded-For")).isEqualTo("192.0.2.10");
                }
                else {
                    for (String name : List.of("Forwarded", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-For", "X-Forwarded-Custom")) {
                        assertThat(actual.getHeader(name)).isNull();
                    }
                }
            }
            finally {
                handler.shutdown();
            }
        }
    }
}
