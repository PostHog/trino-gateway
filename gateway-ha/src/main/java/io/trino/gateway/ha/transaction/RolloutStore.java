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
package io.trino.gateway.ha.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.CONFLICT;
import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.STALE_GENERATION;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class RolloutStore
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PHASES = List.of("CLAIMED", "WARMED", "VERIFIED", "CUTOVER", "DRAINING", "SEALED", "STOPPED", "COMPLETE");
    private final Jdbi jdbi;

    public record Guard(String operationId, long version) {}

    public record Plan(String planHash, long expectedRouteGeneration, String sourceBackend, UUID sourceIncarnation, String targetBackend, @Nullable UUID targetIncarnation) {}

    public record Operation(String routingGroup, String operationId, Plan plan, String phase, long version, JsonNode evidence, JsonNode publications) {}

    public RolloutStore(Jdbi jdbi)
    {
        this.jdbi = jdbi;
    }

    public Operation acquire(String group, String operationId, Plan plan)
    {
        require(operationId != null && operationId.matches("[A-Za-z0-9_.:-]{1,256}") && group != null && !group.isBlank() && group.length() <= 256, "Invalid rollout identity");
        require(plan != null && plan.planHash() != null && plan.planHash().matches("[a-f0-9]{64}") && plan.expectedRouteGeneration() >= 0 &&
                validBackendName(plan.sourceBackend()) && validBackendName(plan.targetBackend()) && !plan.sourceBackend().equals(plan.targetBackend()) && plan.sourceIncarnation() != null, "Invalid rollout plan");
        return jdbi.inTransaction(handle -> {
            TransactionStore.lockRoute(handle, group);
            Optional<Operation> previous = find(handle, "operation_id = :value", operationId);
            if (previous.isPresent()) {
                require(previous.get().routingGroup().equals(group) && previous.get().plan().equals(plan), "Operation identity has a different immutable plan");
                return previous.get();
            }
            require(find(handle, "routing_group = :value AND phase <> 'COMPLETE'", group).isEmpty(), "Routing group already has an unfinished rollout");
            var route = TransactionStore.routeStatus(handle, group);
            require(route.generation() == plan.expectedRouteGeneration() && plan.sourceBackend().equals(route.backendName()) && plan.sourceIncarnation().equals(route.backendIncarnation()), "Observed source route changed");
            require(handle.createQuery("SELECT state = 'ACTIVE' FROM transaction_backend WHERE incarnation = :id").bind("id", plan.sourceIncarnation()).mapTo(Boolean.class).one(), "Rollout source is not active");
            var targetGroup = handle.createQuery("SELECT routing_group FROM transaction_backend WHERE current_name = :name").bind("name", plan.targetBackend()).mapTo(String.class).findOne();
            require(targetGroup.isEmpty() || targetGroup.get().equals(group), "Destination belongs to another routing group");
            var target = handle.createQuery("SELECT incarnation FROM transaction_backend WHERE current_name = :name")
                    .bind("name", plan.targetBackend()).mapTo(UUID.class).findOne();
            require(target.equals(Optional.ofNullable(plan.targetIncarnation())), "Observed destination incarnation changed");
            handle.createUpdate("INSERT INTO transaction_rollout (operation_id, routing_group, plan, phase) VALUES (:id, :group, CAST(:plan AS jsonb), 'CLAIMED')")
                    .bind("id", operationId).bind("group", group).bind("plan", encode(plan)).execute();
            return find(handle, "operation_id = :value", operationId).orElseThrow();
        });
    }

    public Optional<Operation> current(String group)
    {
        return jdbi.withHandle(handle -> find(handle, "routing_group = :value ORDER BY (phase <> 'COMPLETE') DESC, created_at DESC, operation_id DESC LIMIT 1", group));
    }

    public Operation checkpoint(String group, String operationId, long expectedVersion, String phase, String evidence)
    {
        JsonNode document = decodeEvidence(evidence);
        return jdbi.inTransaction(handle -> {
            TransactionStore.lockRoute(handle, group);
            Operation operation = requireGuard(handle, group, new Guard(operationId, expectedVersion));
            int current = PHASES.indexOf(operation.phase());
            int proposed = PHASES.indexOf(phase);
            require(proposed >= 0 && (proposed == current || proposed == current + 1), "Rollout phases cannot be skipped or reversed");
            ObjectNode merged = operation.evidence().deepCopy();
            document.properties().forEach(entry -> {
                require(!merged.has(entry.getKey()) || merged.get(entry.getKey()).equals(entry.getValue()), "Recorded rollout evidence is immutable");
                merged.set(entry.getKey(), entry.getValue());
            });
            require(encode(merged).getBytes(UTF_8).length <= 65536, "Stored rollout evidence exceeds its bound");
            if (proposed >= PHASES.indexOf("WARMED")) {
                requirePublication(operation, merged, "warm");
            }
            if (proposed >= PHASES.indexOf("STOPPED")) {
                requirePublication(operation, merged, "stop");
            }
            if (proposed >= PHASES.indexOf("CUTOVER")) {
                var route = TransactionStore.routeStatus(handle, group);
                require(operation.plan().targetBackend().equals(route.backendName()) && route.generation() == operation.plan().expectedRouteGeneration() + 1, "Rollout destination route is not committed");
            }
            if (proposed >= PHASES.indexOf("DRAINING")) {
                String sourceState = handle.createQuery("SELECT state FROM transaction_backend WHERE current_name = :name AND incarnation = :incarnation")
                        .bind("name", operation.plan().sourceBackend()).bind("incarnation", operation.plan().sourceIncarnation()).mapTo(String.class).findOne().orElse("");
                require(proposed == PHASES.indexOf("DRAINING") ? List.of("DRAINING", "SEALED").contains(sourceState) : sourceState.equals("SEALED"), "Rollout source has not reached its required drain state");
            }
            handle.createUpdate("UPDATE transaction_rollout SET phase = :phase, version = version + 1, evidence = CAST(:evidence AS jsonb) WHERE operation_id = :id")
                    .bind("phase", phase).bind("evidence", encode(merged)).bind("id", operationId).execute();
            return find(handle, "operation_id = :value", operationId).orElseThrow();
        });
    }

    public Operation claimPublication(String group, String kind, Guard guard, String planHash)
    {
        require(List.of("warm", "stop").contains(kind), "Unknown publication phase");
        return jdbi.inTransaction(handle -> {
            TransactionStore.lockRoute(handle, group);
            Operation operation = requireGuard(handle, group, guard);
            require(operation.plan().planHash().equals(planHash), "Publication plan changed");
            require(operation.phase().equals(kind.equals("warm") ? "CLAIMED" : "SEALED"), "Publication is out of phase");
            ObjectNode publications = operation.publications().deepCopy();
            require(!publications.has(kind), "Publication was already claimed and cannot be replayed");
            publications.put(kind, true);
            handle.createUpdate("UPDATE transaction_rollout SET publications = CAST(:publications AS jsonb), version = version + 1 WHERE operation_id = :id")
                    .bind("publications", encode(publications)).bind("id", guard.operationId()).execute();
            return find(handle, "operation_id = :value", guard.operationId()).orElseThrow();
        });
    }

    private static void requirePublication(Operation operation, JsonNode evidence, String kind)
    {
        JsonNode publication = evidence.path(kind + "Publication");
        require(operation.publications().path(kind).asBoolean() && publication.isObject() &&
                publication.path("branch").isTextual() && !publication.path("branch").asText().isBlank() &&
                publication.path("baseSha").asText().matches("[a-f0-9]{40}") && publication.path("headSha").asText().matches("[a-f0-9]{40}") &&
                publication.path("pullRequest").isIntegralNumber() && publication.path("pullRequest").canConvertToLong() && publication.path("pullRequest").asLong() > 0, "Immutable publication identity is required");
    }

    static Operation requireGuard(Handle handle, String group, @Nullable Guard guard)
    {
        Optional<Operation> active = find(handle, "routing_group = :value AND phase <> 'COMPLETE'", group);
        if (guard == null) {
            require(active.isEmpty(), "Administrative mutation requires the active rollout owner");
            return null;
        }
        require(active.isPresent() && active.get().operationId().equals(guard.operationId()), "Rollout ownership changed or completed");
        if (active.get().version() != guard.version()) {
            throw new TransactionStore.StoreException(STALE_GENERATION, "Rollout checkpoint version changed");
        }
        return active.get();
    }

    private static Optional<Operation> find(Handle handle, String predicate, String value)
    {
        return handle.createQuery("SELECT routing_group, operation_id, plan::text, phase, version, evidence::text, publications::text FROM transaction_rollout WHERE " + predicate)
                .bind("value", value).map((rs, _) -> new Operation(rs.getString("routing_group"), rs.getString("operation_id"), decodePlan(rs.getString("plan")), rs.getString("phase"), rs.getLong("version"), decodeEvidence(rs.getString("evidence")), decodeEvidence(rs.getString("publications")))).findOne();
    }

    private static String encode(Object value)
    {
        try {
            return JSON.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new TransactionStore.StoreException(CONFLICT, "Rollout document is invalid");
        }
    }

    private static Plan decodePlan(String value)
    {
        try {
            return JSON.readValue(value, Plan.class);
        }
        catch (JsonProcessingException e) {
            throw new TransactionStore.StoreException(CONFLICT, "Stored rollout plan is invalid");
        }
    }

    private static JsonNode decodeEvidence(String value)
    {
        require(value != null && value.getBytes(UTF_8).length <= 65536, "Rollout evidence exceeds its bound");
        try {
            JsonNode document = JSON.readTree(value);
            require(document != null && document.isObject(), "Rollout evidence must be an object");
            return document;
        }
        catch (JsonProcessingException e) {
            throw new TransactionStore.StoreException(CONFLICT, "Rollout evidence is invalid");
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new TransactionStore.StoreException(CONFLICT, message);
        }
    }

    private static boolean validBackendName(String value)
    {
        return value != null && !value.isBlank() && value.length() <= 256 && value.codePoints().noneMatch(Character::isISOControl);
    }
}
