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
import io.trino.gateway.ha.handler.schema.RoutingDestination;
import io.trino.gateway.ha.handler.schema.RoutingTargetResponse;
import io.trino.gateway.ha.router.OAuth2RoutingStore;
import io.trino.gateway.ha.router.RoutingGroupSelector;
import io.trino.gateway.ha.router.RoutingManager;
import io.trino.gateway.ha.transaction.TransactionAwarenessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPooledRoutingDispatch
{
    @Test
    void pooledDispatchNeverUsesStockSelectorsOrRouteCookies()
    {
        HaGatewayConfiguration configuration = new HaGatewayConfiguration();
        configuration.getTransactionAwareness().setEnabled(true);
        configuration.getTransactionAwareness().getPool().setEnabled(true);
        configuration.getGatewayCookieConfiguration().setEnabled(true);
        configuration.getRoutingRules().setRulesEngineEnabled(true);
        RoutingManager stock = mock(RoutingManager.class);
        RoutingGroupSelector selector = mock(RoutingGroupSelector.class);
        OAuth2RoutingStore oauth = mock(OAuth2RoutingStore.class);
        TransactionAwarenessService pooled = mock(TransactionAwarenessService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        RoutingTargetResponse expected = new RoutingTargetResponse(
                new RoutingDestination("pool", "http://member.example.test", URI.create("http://member.example.test/v1/statement"), "http://member.example.test"), request);
        when(pooled.isEnabled()).thenReturn(true);
        when(pooled.resolve(request, Optional.empty())).thenReturn(expected);
        RoutingTargetHandler handler = new RoutingTargetHandler(stock, oauth, selector, configuration);
        handler.setTransactionAwareness(pooled);

        assertThat(handler.resolveRouting(request)).isSameAs(expected);
        verifyNoInteractions(stock, selector, oauth, request);
    }
}
