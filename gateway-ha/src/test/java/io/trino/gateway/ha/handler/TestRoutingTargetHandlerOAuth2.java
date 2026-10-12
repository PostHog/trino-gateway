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

import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.handler.schema.RoutingTargetResponse;
import io.trino.gateway.ha.router.OAuth2RoutingStore;
import io.trino.gateway.ha.transaction.TransactionAwarenessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestInstance(Lifecycle.PER_CLASS)
final class TestRoutingTargetHandlerOAuth2
{
    private static RoutingTargetHandler handler(TransactionAwarenessService transactions, OAuth2RoutingStore store)
    {
        HaGatewayConfiguration config = new HaGatewayConfiguration();
        config.getRouting().setOauth2RoutingEnabled(true);
        when(transactions.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            HttpServletRequest request = call.getArgument(0);
            Optional<String> pinned = call.getArgument(1);
            String backend = pinned.orElseThrow();
            return new RoutingTargetResponse(new io.trino.gateway.ha.handler.schema.RoutingDestination("pool", backend, java.net.URI.create(backend + request.getRequestURI()), backend), request);
        });
        return new RoutingTargetHandler(transactions, store, config);
    }

    private static HttpServletRequest oauthRequest(String path)
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }

    @Test
    void testUnknownExchangeNeverSelectsAnotherMember()
    {
        TransactionAwarenessService transactions = mock(TransactionAwarenessService.class);
        OAuth2RoutingStore store = mock(OAuth2RoutingStore.class);
        when(store.findBackend("unknown")).thenReturn(Optional.empty());
        HttpServletRequest request = oauthRequest("/oauth2/token/unknown");
        assertThatThrownBy(() -> handler(transactions, store).resolveRouting(request))
                .isInstanceOfSatisfying(WebApplicationException.class,
                        failure -> assertThat(failure.getResponse().getStatus()).isEqualTo(200));
        verify(transactions, never()).resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testForcesReAuthAndDropsPinWhenPinnedBackendUnavailable()
    {
        TransactionAwarenessService transactions = mock(TransactionAwarenessService.class);
        OAuth2RoutingStore store = mock(OAuth2RoutingStore.class);
        when(store.findBackend("authABC")).thenReturn(Optional.of("http://dead:8080"));
        // No longer active and healthy (deactivated, unhealthy, or removed from the fleet).
        when(transactions.isPooledBackendAvailable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("http://dead:8080"))).thenReturn(false);

        HttpServletRequest request = oauthRequest("/oauth2/token/authABC");

        assertThatThrownBy(() -> handler(transactions, store).resolveRouting(request))
                .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                    // Trino token-poll failure contract: 200 with a JSON error body.
                    assertThat(e.getResponse().getStatus()).isEqualTo(200);
                    assertThat((String) e.getResponse().getEntity()).contains("\"error\"");
                });

        // The stale pin is dropped so the client's next attempt re-authenticates.
        verify(store).removeBackend("authABC");
    }

    @Test
    void testForcesReAuthWithUnauthorizedForInitiateLegWhenPinnedBackendUnavailable()
    {
        TransactionAwarenessService transactions = mock(TransactionAwarenessService.class);
        OAuth2RoutingStore store = mock(OAuth2RoutingStore.class);
        when(store.findBackend("hashABC")).thenReturn(Optional.of("http://dead:8080"));
        // No longer active and healthy (deactivated, unhealthy, or removed from the fleet).
        when(transactions.isPooledBackendAvailable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("http://dead:8080"))).thenReturn(false);

        HttpServletRequest request = oauthRequest("/oauth2/token/initiate/hashABC");

        assertThatThrownBy(() -> handler(transactions, store).resolveRouting(request))
                .isInstanceOfSatisfying(WebApplicationException.class, e ->
                        // The browser initiate leg has no token-poll contract to satisfy: a plain 401
                        // ends it, unlike the driver poll leg's 200-with-error-body contract.
                        assertThat(e.getResponse().getStatus()).isEqualTo(401));

        // The stale pin is dropped so the client's next attempt re-authenticates.
        verify(store).removeBackend("hashABC");
    }

    @Test
    void testRoutesToPinnedBackendWhenActiveAndHealthy()
    {
        TransactionAwarenessService transactions = mock(TransactionAwarenessService.class);
        OAuth2RoutingStore store = mock(OAuth2RoutingStore.class);
        when(store.findBackend("authXYZ")).thenReturn(Optional.of("http://live:8080"));
        // Active and healthy -> route the in-flight handshake there.
        when(transactions.isPooledBackendAvailable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("http://live:8080"))).thenReturn(true);

        HttpServletRequest request = oauthRequest("/oauth2/token/authXYZ");

        RoutingTargetResponse response = handler(transactions, store).resolveRouting(request);

        assertThat(response.routingDestination().clusterHost()).isEqualTo("http://live:8080");
        verify(store, never()).removeBackend("authXYZ");
    }

    @Test
    void testRoutesCallbackToMintingBackendViaState()
    {
        TransactionAwarenessService transactions = mock(TransactionAwarenessService.class);
        OAuth2RoutingStore store = mock(OAuth2RoutingStore.class);
        // The callback carries no id in its path; it is pinned by the authIdHash inside the state JWT,
        // reusing the pin recorded for the initiate leg.
        when(store.findBackend("hashHHH")).thenReturn(Optional.of("http://minting:8080"));
        when(transactions.isPooledBackendAvailable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("http://minting:8080"))).thenReturn(true);

        HttpServletRequest request = oauthRequest("/oauth2/callback");
        when(request.getQueryString()).thenReturn("code=abc&state=" + stateJwt("hashHHH"));

        RoutingTargetResponse response = handler(transactions, store).resolveRouting(request);

        assertThat(response.routingDestination().clusterHost()).isEqualTo("http://minting:8080");
        verify(store, never()).removeBackend("hashHHH");
    }

    private static String stateJwt(String handlerState)
    {
        String header = base64Url("{\"alg\":\"HS256\"}");
        String payload = base64Url("{\"aud\":\"trino_oauth_ui\",\"handler_state\":\"" + handlerState + "\"}");
        return header + "." + payload + ".signature";
    }

    private static String base64Url(String value)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
