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
package io.trino.gateway.ha.resource;

import com.google.inject.Inject;
import io.trino.gateway.ha.transaction.TransactionAwarenessService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;

import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/gateway/transactions")
@Produces(APPLICATION_JSON)
@RolesAllowed("API")
public class TransactionResource
{
    private final TransactionAwarenessService service;

    @Inject
    public TransactionResource(TransactionAwarenessService service)
    {
        this.service = service;
    }

    @GET
    @Path("/{transactionId}")
    public Map<String, Object> transaction(@PathParam("transactionId") String transactionId, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.transaction(transactionId);
    }
}
