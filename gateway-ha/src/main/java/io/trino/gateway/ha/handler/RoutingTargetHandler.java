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

import com.google.inject.Inject;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.handler.schema.RoutingTargetResponse;
import io.trino.gateway.ha.router.OAuth2RoutingStore;
import io.trino.gateway.ha.router.OAuth2RoutingUtils;
import io.trino.gateway.ha.transaction.TransactionAwarenessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class RoutingTargetHandler
{
    private final TransactionAwarenessService transactions;
    private final OAuth2RoutingStore oauth2RoutingStore;
    private final boolean oauth2RoutingEnabled;

    @Inject
    public RoutingTargetHandler(TransactionAwarenessService transactions, OAuth2RoutingStore oauth2RoutingStore, HaGatewayConfiguration configuration)
    {
        this.transactions = requireNonNull(transactions, "transactions is null");
        this.oauth2RoutingStore = requireNonNull(oauth2RoutingStore, "oauth2RoutingStore is null");
        oauth2RoutingEnabled = configuration.getRouting().isOauth2RoutingEnabled();
    }

    public RoutingTargetResponse resolveRouting(HttpServletRequest request)
    {
        Optional<String> pinned = Optional.empty();
        if (oauth2RoutingEnabled) {
            Optional<String> id = OAuth2RoutingUtils.oauthIdFromCallback(request.getRequestURI(), request.getQueryString())
                    .or(() -> OAuth2RoutingUtils.oauthIdFromRequestPath(request.getRequestURI()));
            if (id.isPresent()) {
                pinned = oauth2RoutingStore.findBackend(id.orElseThrow());
                if (pinned.isEmpty() || !transactions.isPooledBackendAvailable(request, pinned.orElseThrow())) {
                    oauth2RoutingStore.removeBackend(id.orElseThrow());
                    throw new WebApplicationException(OAuth2RoutingUtils.forceReAuthResponse(request.getRequestURI()));
                }
            }
        }
        return transactions.resolve(request, pinned);
    }
}
