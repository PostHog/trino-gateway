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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import io.trino.gateway.ha.transaction.RolloutStore;
import io.trino.gateway.ha.transaction.TransactionAwarenessService;
import io.trino.gateway.ha.transaction.TransactionIdentity;
import io.trino.gateway.ha.transaction.TransactionStore.RouteStatus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;

import java.util.Map;
import java.util.UUID;

import static io.trino.gateway.ha.transaction.TransactionIdentity.error;
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

    @GET
    @Path("/backends/{name}/drain")
    public Map<String, Object> status(@PathParam("name") String name, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.drain(name, false);
    }

    @POST
    @Path("/backends/{name}/drain")
    public Map<String, Object> drain(@PathParam("name") String name, DrainRequest body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body != null) {
            if (body.expectedIncarnation() == null || body.expectedGeneration() == null || body.expectedGeneration() < 0) {
                throw error(400, "Both observed incarnation and generation are required");
            }
            return service.drain(name, body.expectedIncarnation(), body.expectedGeneration(), operation(request));
        }
        return service.drain(name, true, operation(request));
    }

    public record DrainRequest(UUID expectedIncarnation, Long expectedGeneration) {}

    @GET
    @Path("/routes/{routingGroup}")
    public RouteStatus route(@PathParam("routingGroup") String routingGroup, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.routeStatus(routingGroup);
    }

    @PUT
    @Path("/routes/{routingGroup}")
    @Consumes(APPLICATION_JSON)
    public RouteStatus compareAndSetRoute(@PathParam("routingGroup") String routingGroup, RouteRequest body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body == null || body.expectedGeneration() == null || body.expectedGeneration() < 0 ||
                body.backendName() == null || body.backendName().isBlank() || body.backendIncarnation() == null) {
            throw error(400, "Observed route generation and destination name and incarnation are required");
        }
        return service.compareAndSetRoute(routingGroup, body.expectedGeneration(), body.expectedBackendName(), body.backendName(), body.backendIncarnation(), operation(request));
    }

    public record RouteRequest(Long expectedGeneration, String expectedBackendName, String backendName, UUID backendIncarnation) {}

    @POST
    @Path("/backends/{name}/seal")
    @Consumes(APPLICATION_JSON)
    public Map<String, Object> seal(@PathParam("name") String name, Map<String, Long> body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.seal(name, generation(body), operation(request));
    }

    @POST
    @Path("/backends/{name}/resume")
    @Consumes(APPLICATION_JSON)
    public Map<String, Object> resume(@PathParam("name") String name, Map<String, Long> body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.resume(name, generation(body), operation(request));
    }

    @POST
    @Path("/cutover")
    @Consumes(APPLICATION_JSON)
    public Map<String, Object> cutover(Map<String, String> body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body == null || body.get("routingGroup") == null || body.get("backendName") == null) {
            throw error(400, "routingGroup and backendName are required");
        }
        return service.cutover(body.get("routingGroup"), body.get("backendName"), operation(request));
    }

    @POST
    @Path("/backends/{name}/reincarnate")
    @Consumes(APPLICATION_JSON)
    public Map<String, Object> reincarnate(@PathParam("name") String name, ReincarnationRequest body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body == null || body.incarnation() == null || body.generation() == null || body.generation() < 0) {
            throw error(400, "The observed incarnation and generation are required");
        }
        return service.reincarnate(name, body.incarnation(), body.generation(), operation(request));
    }

    public record ReincarnationRequest(UUID incarnation, Long generation) {}

    @DELETE
    @Path("/cutover/{routingGroup}")
    public Map<String, Object> clearRoute(@PathParam("routingGroup") String routingGroup, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.clearRoute(routingGroup, operation(request));
    }

    @POST
    @Path("/rollouts/{routingGroup}/acquire")
    @Consumes(APPLICATION_JSON)
    public RolloutStore.Operation acquire(@PathParam("routingGroup") String group, AcquireRequest body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body == null || body.expectedRouteGeneration() == null) {
            throw error(400, "The immutable rollout plan is required");
        }
        return service.acquireRollout(group, body.operationId(), new RolloutStore.Plan(body.planHash(), body.expectedRouteGeneration(), body.sourceBackend(), body.sourceIncarnation(), body.targetBackend(), body.targetIncarnation()));
    }

    public record AcquireRequest(String operationId, String planHash, Long expectedRouteGeneration, String sourceBackend, UUID sourceIncarnation, String targetBackend, UUID targetIncarnation) {}

    @GET
    @Path("/rollouts/{routingGroup}")
    public RolloutStore.Operation rollout(@PathParam("routingGroup") String group, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        return service.currentRollout(group);
    }

    @PUT
    @Path("/rollouts/{routingGroup}/checkpoint")
    @Consumes(APPLICATION_JSON)
    public RolloutStore.Operation checkpoint(@PathParam("routingGroup") String group, CheckpointRequest body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body == null || body.operationId() == null || body.expectedVersion() == null || body.expectedVersion() < 0 || body.phase() == null || body.evidence() == null) {
            throw error(400, "The rollout identity, checkpoint version, phase, and evidence are required");
        }
        return service.checkpointRollout(group, body.operationId(), body.expectedVersion(), body.phase(), body.evidence().toString());
    }

    public record CheckpointRequest(String operationId, Long expectedVersion, String phase, JsonNode evidence) {}

    @POST
    @Path("/rollouts/{routingGroup}/publications/{kind}/claim")
    @Consumes(APPLICATION_JSON)
    public RolloutStore.Operation claimPublication(@PathParam("routingGroup") String group, @PathParam("kind") String kind, PublicationRequest body, @Context HttpServletRequest request)
    {
        service.requireAdmin(request);
        if (body == null || body.operationId() == null || body.expectedVersion() == null || body.expectedVersion() < 0 || body.planHash() == null) {
            throw error(400, "Publication owner, checkpoint version, and plan hash are required");
        }
        return service.claimPublication(group, kind, body.operationId(), body.expectedVersion(), body.planHash());
    }

    public record PublicationRequest(String operationId, Long expectedVersion, String planHash) {}

    private static RolloutStore.Guard operation(HttpServletRequest request)
    {
        var id = TransactionIdentity.singleHeader(request, "X-Gateway-Operation-Id");
        var version = TransactionIdentity.singleHeader(request, "X-Gateway-Operation-Version");
        if (id.isEmpty() && version.isEmpty()) {
            return null;
        }
        if (id.isEmpty() || version.isEmpty() || !id.get().matches("[A-Za-z0-9_.:-]{1,256}") || !version.get().matches("[0-9]{1,19}")) {
            throw error(400, "Both rollout identity and checkpoint version are required");
        }
        try {
            return new RolloutStore.Guard(id.get(), Long.parseLong(version.get()));
        }
        catch (NumberFormatException e) {
            throw error(400, "Rollout checkpoint version is invalid");
        }
    }

    private static long generation(Map<String, Long> body)
    {
        if (body == null || body.get("generation") == null || body.get("generation") < 0) {
            throw error(400, "The observed backend generation is required");
        }
        return body.get("generation");
    }
}
