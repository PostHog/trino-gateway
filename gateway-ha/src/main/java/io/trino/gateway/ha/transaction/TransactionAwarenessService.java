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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.airlift.http.client.HeaderName;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.StringResponseHandler.StringResponse;
import io.airlift.units.Duration;
import io.trino.gateway.ha.clustermonitor.ForMonitor;
import io.trino.gateway.ha.clustermonitor.TrinoStatus;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.config.PoolLifecycleConfiguration;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;
import io.trino.gateway.ha.config.TransactionAwarenessConfiguration;
import io.trino.gateway.ha.handler.schema.RoutingDestination;
import io.trino.gateway.ha.handler.schema.RoutingTargetResponse;
import io.trino.gateway.ha.router.GatewayBackendManager;
import io.trino.gateway.ha.router.RoutingGroupSelector;
import io.trino.gateway.ha.router.RoutingManager;
import io.trino.gateway.ha.router.TrinoRequestUser;
import io.trino.gateway.ha.router.schema.RoutingSelectorResponse;
import io.trino.gateway.ha.transaction.TransactionStore.Admission;
import io.trino.gateway.ha.transaction.TransactionStore.BackendRef;
import io.trino.gateway.ha.transaction.TransactionStore.QueryBinding;
import io.trino.gateway.ha.transaction.TransactionStore.ResponseObservation;
import io.trino.gateway.ha.transaction.TransactionStore.StoreException;
import io.trino.gateway.proxyserver.ProxyResponseHandler.ProxyResponse;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static io.trino.gateway.ha.handler.HttpUtils.TRINO_REQUEST_USER;
import static io.trino.gateway.ha.handler.ProxyUtils.buildUriWithNewCluster;
import static io.trino.gateway.ha.handler.ProxyUtils.extractQueryIdIfPresent;
import static io.trino.gateway.ha.persistence.DatabaseDeadline.withDeadline;
import static io.trino.gateway.ha.transaction.TransactionIdentity.canonicalTransactionId;
import static io.trino.gateway.ha.transaction.TransactionIdentity.error;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Singleton
public class TransactionAwarenessService
{
    private static final String ADMISSION_ATTRIBUTE = TransactionAwarenessService.class.getName() + ".admission";
    private static final String CAPACITY_ATTRIBUTE = TransactionAwarenessService.class.getName() + ".capacity";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final TransactionAwarenessConfiguration config;
    private final TransactionStore store;
    private final TransactionLifecycleStats lifecycleStats;
    private final TransactionIdentity identity;
    private final GatewayBackendManager backendManager;
    private final HttpClient httpClient;
    private final List<String> statementPaths;
    private final RoutingGroupSelector routingGroupSelector;
    private final TransactionRequestCapacity capacity;
    private final PoolStore pools;
    private final PoolLifecycleConfiguration poolConfig;
    private RoutingManager routingManager;

    /**
     * Cached cluster health is advisory only, so it is injected separately and a missing routing
     * manager simply means no ordering hint. Authoritative admission never depends on it.
     */
    @Inject
    public void setRoutingManager(RoutingManager routingManager)
    {
        this.routingManager = routingManager;
    }

    @Inject
    public TransactionAwarenessService(HaGatewayConfiguration configuration, Jdbi jdbi, GatewayBackendManager backendManager, @ForMonitor HttpClient httpClient, RoutingGroupSelector routingGroupSelector, TransactionLifecycleStats lifecycleStats)
    {
        this.lifecycleStats = requireNonNull(lifecycleStats, "lifecycleStats is null");
        config = configuration.getTransactionAwareness();
        config.validate(configuration.getDataStore(), configuration.getRouting());
        store = new TransactionStore(jdbi);
        pools = new PoolStore(jdbi);
        poolConfig = config.getPool();
        identity = new TransactionIdentity(config.getIdentityKey());
        this.backendManager = backendManager;
        this.httpClient = httpClient;
        statementPaths = configuration.getStatementPaths();
        this.routingGroupSelector = routingGroupSelector;
        capacity = new TransactionRequestCapacity(config, configuration.getRouting().getAsyncTimeout());
    }

    public boolean isEnabled()
    {
        return config.isEnabled();
    }

    public RoutingTargetResponse resolve(HttpServletRequest request)
    {
        return resolve(request, Optional.empty());
    }

    public RoutingTargetResponse resolve(HttpServletRequest request, Optional<String> pinnedBackend)
    {
        String path = request.getRequestURI();
        if (!canonicalPath(path)) {
            throw error(400, "Proxy requests require a canonical path");
        }
        boolean submission = request.getMethod().equals("POST") && statementPaths.contains(path);
        boolean continuation = !request.getMethod().equals("POST") && (statementPaths.stream().anyMatch(statement -> path.startsWith(statement + "/")) || path.startsWith("/v1/query/"));
        if (request.getMethod().equals("POST") && !submission) {
            throw error(400, "Query submission requires an exact configured statement path");
        }
        if (request.getMethod().equals("PUT") || (continuation && !List.of("GET", "HEAD", "DELETE").contains(request.getMethod()))) {
            throw error(405, "This proxy method is not supported");
        }
        RoutingSelectorResponse selection = routingGroupSelector.findRoutingDestination(request);
        String group = selection.routingGroup();
        if (group == null || group.isBlank()) {
            throw error(421, "The request hostname does not identify a pool");
        }
        if (!submission && !continuation) {
            return resolveAuxiliary(request, group, pinnedBackend);
        }
        TransactionRequestCapacity.Lease lease = capacity.acquire();
        request.setAttribute(CAPACITY_ATTRIBUTE, lease);
        try {
            return guarded(() -> withDeadline(lease.deadlineNanos(), true, () -> {
                lease.remaining();
                Optional<String> transaction = TransactionIdentity.transactionId(request);
                Admission admission;
                if (submission) {
                    String owner = identity.owner(request);
                    if (transaction.isPresent()) {
                        admission = store.admitTransaction(transaction.orElseThrow(), owner);
                    }
                    else {
                        PoolStore.PoolRouting pooled = pools.routing(group);
                        if (!pooled.pooled()) {
                            throw classifiedRoutingError(503, "ROUTING_STATE_NOT_ACTIVE", "The hostname's pool is not active");
                        }
                        admission = admitPooled(group, owner, lease, request, pooled);
                    }
                }
                else {
                    if (path.startsWith("/v1/query/") && TransactionIdentity.singleHeader(request, "Authorization").isEmpty()) {
                        throw error(401, "Query metadata and query-ID cancellation require owner credentials");
                    }
                    String queryId = extractQueryIdIfPresent(path, null, statementPaths)
                            .orElseThrow(() -> error(400, "Invalid query continuation path"));
                    QueryBinding query = store.getQuery(queryId).orElseThrow(() -> error(404, "Unknown query identifier"));
                    requirePool(query.backend(), group);
                    identity.validateContinuation(request, query.ownerHash());
                    Optional<String> capability = path.startsWith("/v1/query/") ? Optional.empty() : Optional.of(capabilityHash(path));
                    admission = store.admitQuery(queryId, Optional.of(query.ownerHash()), transaction, capability);
                }
                request.setAttribute(ADMISSION_ATTRIBUTE, admission);
                requirePool(admission.backend(), group);
                lease.remaining();
                verifyProcess(admission.backend(), lease.remaining());
                lease.remaining();
                HttpServletRequest forwardedRequest = inlineResults(withoutClientForwardedHeaders(request));
                forwardedRequest.setAttribute(ADMISSION_ATTRIBUTE, admission);
                return destination(admission.backend(), forwardedRequest);
            }));
        }
        catch (RuntimeException failure) {
            try {
                requestRejectedBeforeDispatch(request);
            }
            catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private RoutingTargetResponse resolveAuxiliary(HttpServletRequest request, String group, Optional<String> pinnedBackend)
    {
        return guarded(() -> {
            PoolStore.PoolRouting pooled = pools.routing(group);
            if (!pooled.pooled()) {
                throw classifiedRoutingError(503, "ROUTING_STATE_NOT_ACTIVE", "The hostname's pool is not active");
            }
            List<PoolStore.Candidate> candidates = candidates(group, pooled);
            if (pinnedBackend.isPresent()) {
                candidates.removeIf(candidate -> !sameAuthority(candidate.url(), pinnedBackend.orElseThrow()));
            }
            for (PoolStore.Candidate candidate : candidates) {
                BackendRef backend = store.getBackend(candidate.backendName()).orElseThrow(() -> error(503, "Unknown pool member"));
                requirePool(backend, group);
                if (!backend.incarnation().equals(candidate.incarnation()) || !backend.url().equals(candidate.url()) ||
                        (pinnedBackend.isPresent() && !sameAuthority(backend.url(), pinnedBackend.orElseThrow()))) {
                    throw error(409, "The original coordinator process is no longer available");
                }
                verifyProcess(backend, new Duration(config.getProcessInfoTimeoutMillis(), MILLISECONDS));
                return destination(backend, withoutClientForwardedHeaders(request));
            }
            throw classifiedRoutingError(503, "ROUTING_STATE_NOT_ACTIVE", "No pool member is currently eligible");
        });
    }

    public boolean isPooledBackendAvailable(HttpServletRequest request, String backendUrl)
    {
        return guarded(() -> {
            String group = routingGroupSelector.findRoutingDestination(request).routingGroup();
            PoolStore.PoolRouting pooled = pools.routing(group);
            return pooled.pooled() && candidates(group, pooled).stream()
                    .anyMatch(candidate -> sameAuthority(candidate.url(), backendUrl) && cachedHealth(candidate.backendName()));
        });
    }

    private static boolean sameAuthority(String left, String right)
    {
        URI first = URI.create(left);
        URI second = URI.create(right);
        return first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getAuthority().equalsIgnoreCase(second.getAuthority());
    }

    private static void requirePool(BackendRef backend, String group)
    {
        if (!group.equals(backend.poolId()) || !group.equals(backend.routingGroup())) {
            throw error(403, "The request hostname does not own this query or transaction");
        }
    }

    private static RoutingTargetResponse destination(BackendRef backend, HttpServletRequest request)
    {
        return new RoutingTargetResponse(new RoutingDestination(backend.routingGroup(), backend.url(), buildUriWithNewCluster(backend.url(), request), backend.externalUrl()), request);
    }

    private List<PoolStore.Candidate> candidates(String group, PoolStore.PoolRouting pooled)
    {
        Set<String> registrations = backendManager.getAllBackends().stream()
                .filter(backend -> group.equals(backend.getRoutingGroup()))
                .map(ProxyBackendConfiguration::getName)
                .collect(java.util.stream.Collectors.toSet());
        List<PoolStore.Candidate> eligible = new ArrayList<>(pooled.candidates().stream()
                .filter(candidate -> registrations.contains(candidate.backendName()))
                .toList());
        Collections.shuffle(eligible);
        eligible.sort(java.util.Comparator.comparing(candidate -> cachedHealth(candidate.backendName()) ? 0 : 1));
        return eligible;
    }

    /**
     * Selects one eligible pooled member for new independent work.
     * <p>
     * The candidate list joins the authoritative member phase with the legacy active-backend list, so
     * ordinary selection can no longer pick a draining member. Cached health only orders candidates.
     * A rejected candidate is reselected strictly before dispatch, bounded by
     * {@code maxPreDispatchCandidates} and by the original request deadline. After dispatch nothing is
     * reselected, retried or replayed.
     */
    private Admission admitPooled(String group, String owner, TransactionRequestCapacity.Lease lease, HttpServletRequest request, PoolStore.PoolRouting pooled)
    {
        // Deny-only restriction, evaluated inside the admission transaction: it never grants access,
        // and it is not consulted for continuations, so already dispatched work stays pinned.
        TransactionStore.TenantGate gate = poolConfig.hasVerifiedTenantIdentity()
                ? new TransactionStore.TenantGate(TenantPrincipals.authenticatedPrincipal(
                request,
                poolConfig.getHostQualificationDomains(),
                Set.copyOf(poolConfig.getExcludedHostLabels())).orElse(null))
                : null;
        List<PoolStore.Candidate> eligible = candidates(group, pooled);
        if (eligible.isEmpty()) {
            throw classifiedRoutingError(503, "ROUTING_STATE_NOT_ACTIVE", "No pool member is currently eligible to admit new work");
        }
        StoreException lastRejection = null;
        int attempts = 0;
        for (PoolStore.Candidate candidate : eligible) {
            if (attempts >= poolConfig.getMaxPreDispatchCandidates()) {
                break;
            }
            attempts++;
            lease.remaining();
            try {
                return store.admitPooledMember(group, candidate.backendName(), owner, gate);
            }
            catch (StoreException rejection) {
                if (rejection.code() == TransactionStore.ErrorCode.TENANT_NOT_ADMITTED) {
                    throw classifiedRoutingError(
                            403,
                            "TENANT_NOT_ADMITTED",
                            "This tenant is not admitted to the pool yet; the request was not dispatched");
                }
                if (!List.of(TransactionStore.ErrorCode.NOT_ACTIVE, TransactionStore.ErrorCode.NOT_FOUND, TransactionStore.ErrorCode.CONFLICT).contains(rejection.code())) {
                    throw rejection;
                }
                lastRejection = rejection;
            }
        }
        if (lastRejection != null) {
            throw classifiedRoutingError(503, "ROUTING_STATE_NOT_ACTIVE", "No pool member could admit new work within the request budget");
        }
        throw classifiedRoutingError(503, "ROUTING_STATE_NOT_ACTIVE", "No pool member is currently eligible to admit new work");
    }

    /**
     * Advisory: an unknown or unhealthy cached status only deprioritizes a candidate.
     */
    private boolean cachedHealth(String backendName)
    {
        return routingManager != null && routingManager.getBackEndHealth(backendName)
                .filter(status -> status == TrinoStatus.HEALTHY).isPresent();
    }

    public RequestContext captureRequestContext(HttpServletRequest request)
    {
        if (!(request.getAttribute(CAPACITY_ATTRIBUTE) instanceof TransactionRequestCapacity.Lease lease)) {
            return null;
        }
        Optional<String> username = request.getAttribute(TRINO_REQUEST_USER) instanceof TrinoRequestUser user ? user.getUser() : Optional.empty();
        return new RequestContext(admission(request), lease, request.getMethod(), request.getRequestURI(), username);
    }

    public static final class RequestContext
            implements AutoCloseable
    {
        private final Admission admission;
        private final TransactionRequestCapacity.Lease lease;
        private final String method;
        private final String requestUri;
        private final Optional<String> username;

        private RequestContext(Admission admission, TransactionRequestCapacity.Lease lease, String method, String requestUri, Optional<String> username)
        {
            this.admission = admission;
            this.lease = lease;
            this.method = method;
            this.requestUri = requestUri;
            this.username = username;
        }

        public Optional<String> username()
        {
            return username;
        }

        public Duration remainingTime()
        {
            return lease.remaining();
        }

        public void beforeDispatch()
        {
            lease.dispatch();
        }

        public boolean wasDispatched()
        {
            return lease.isDispatched();
        }

        public void manageCompletion()
        {
            lease.manageCompletion();
        }

        public boolean isCompletionManaged()
        {
            return lease.isCompletionManaged();
        }

        @Override
        public void close()
        {
            lease.close();
        }
    }

    public Executor completionExecutor()
    {
        return capacity.completions();
    }

    public <T> T completionPhase(Supplier<T> action)
    {
        return withDeadline(System.nanoTime() + MILLISECONDS.toNanos(config.getCompletionTimeoutMillis()), false, action);
    }

    public void completeRequest(HttpServletRequest request)
    {
        if (request.getAttribute(CAPACITY_ATTRIBUTE) instanceof TransactionRequestCapacity.Lease lease) {
            lease.close();
        }
    }

    @PreDestroy
    public void shutdown()
    {
        if (capacity != null) {
            capacity.shutdown();
        }
    }

    public ProxyResponse recordResponse(HttpServletRequest request, ProxyResponse response)
    {
        return recordResponse(admission(request), request.getMethod(), request.getRequestURI(), response);
    }

    public ProxyResponse recordResponse(RequestContext context, ProxyResponse response)
    {
        return recordResponse(context.admission, context.method, context.requestUri, response);
    }

    private ProxyResponse recordResponse(Admission admission, String method, String requestUri, ProxyResponse response)
    {
        if (admission == null) {
            return response;
        }
        return guarded(() -> {
            boolean rejectedContinuation = response.statusCode() == 404 && List.of("GET", "HEAD", "DELETE").contains(method);
            boolean completedCancellation = response.statusCode() == 204 && method.equals("DELETE");
            if ((rejectedContinuation || completedCancellation) && admission.queryId() != null &&
                    responseHeader(response, "X-Trino-Started-Transaction-Id").isEmpty() && responseHeader(response, "X-Trino-Clear-Transaction-Id").isEmpty()) {
                boolean forgottenExecutingQuery = rejectedContinuation && method.equals("GET") && isExecutingQueryContinuation(requestUri, admission.queryId());
                if (rejectedContinuation) {
                    lifecycleStats.notFoundContinuation(admission, forgottenExecutingQuery);
                }
                if ((completedCancellation && isWholeQueryCancellation(requestUri, admission.queryId())) || forgottenExecutingQuery) {
                    store.recordResponse(admission.id(), new ResponseObservation(admission.queryId(), null, false, true, config.getTerminalRetentionSeconds()));
                    if (completedCancellation) {
                        lifecycleStats.terminalResult(true);
                    }
                }
                else {
                    store.rejectAdmission(admission.id());
                }
                return response;
            }
            if ((response.statusCode() == 401 || response.statusCode() == 403) &&
                    responseHeader(response, "X-Trino-Started-Transaction-Id").isEmpty() && responseHeader(response, "X-Trino-Clear-Transaction-Id").isEmpty() &&
                    !response.body().stripLeading().startsWith("{")) {
                store.rejectAdmission(admission.id());
                return response;
            }
            if (response.statusCode() != 200) {
                markUncertain(admission, response.statusCode());
                return response;
            }
            if (method.equals("HEAD")) {
                if (responseHeader(response, "X-Trino-Started-Transaction-Id").isPresent() || responseHeader(response, "X-Trino-Clear-Transaction-Id").isPresent()) {
                    throw error(502, "Backend returned transaction lifecycle headers on a heartbeat");
                }
                store.recordResponse(admission.id(), new ResponseObservation(admission.queryId(), null, false, false, config.getTerminalRetentionSeconds()));
                return response;
            }
            JsonNode body;
            try {
                body = JSON.readTree(response.body());
            }
            catch (IOException e) {
                throw error(502, "Backend returned malformed query results");
            }
            if (requestUri.startsWith("/v1/query/") && method.equals("GET")) {
                if (responseHeader(response, "X-Trino-Started-Transaction-Id").isPresent() || responseHeader(response, "X-Trino-Clear-Transaction-Id").isPresent()) {
                    throw error(502, "Backend returned transaction lifecycle headers on query metadata");
                }
                if (body == null || !body.isObject() || !body.path("queryId").asText().equals(admission.queryId())) {
                    throw error(502, "Backend returned invalid query metadata");
                }
                store.recordResponse(admission.id(), new ResponseObservation(admission.queryId(), null, false, false, config.getTerminalRetentionSeconds()));
                return response;
            }
            if (body == null || !body.isObject() || !body.path("id").isTextual() || !body.path("stats").isObject()) {
                throw error(502, "Backend returned query results without a valid identifier");
            }
            if (body.hasNonNull("data") && !body.path("data").isArray()) {
                throw error(502, "Transaction-aware routing currently requires inline query results");
            }
            String queryId = body.path("id").asText();
            if (!queryId.matches("[0-9]+_[0-9]+_[0-9]+_[a-zA-Z0-9]+") || !queryId.endsWith("_" + admission.backend().coordinatorId())) {
                throw error(502, "Backend query identity does not match its registered process");
            }
            Optional<String> started = responseHeader(response, "X-Trino-Started-Transaction-Id");
            if (started.isPresent()) {
                try {
                    started = Optional.of(canonicalTransactionId(started.orElseThrow()));
                }
                catch (WebApplicationException e) {
                    throw error(502, "Backend returned a malformed transaction identifier");
                }
            }
            boolean clear = responseHeader(response, "X-Trino-Clear-Transaction-Id").isPresent();
            if (clear && started.isPresent()) {
                throw error(502, "Backend returned contradictory transaction lifecycle headers");
            }
            boolean terminal = !body.hasNonNull("nextUri");
            JsonNode state = body.path("stats").path("state");
            if (!state.isTextual() || (terminal && !List.of("FINISHED", "FAILED").contains(state.asText()))) {
                throw error(502, "Backend returned inconsistent query completion state");
            }
            List<String> capabilities = new ArrayList<>();
            for (String field : List.of("nextUri", "partialCancelUri")) {
                if (body.hasNonNull(field)) {
                    capabilities.add(responseCapability(body.path(field), queryId));
                }
            }
            store.recordResponse(admission.id(), new ResponseObservation(queryId, started.orElse(null), clear, terminal, config.getTerminalRetentionSeconds(), capabilities));
            if (terminal) {
                lifecycleStats.terminalResult(false);
            }
            if (started.isPresent() && !store.getTransaction(started.orElseThrow()).orElseThrow().state().equals("OPEN")) {
                throw error(409, "A completed transaction cannot be started again by a replayed response");
            }
            return response;
        });
    }

    private boolean isWholeQueryCancellation(String requestUri, String queryId)
    {
        if (requestUri.equals("/v1/query/" + queryId)) {
            return true;
        }
        for (String path : statementPaths) {
            for (String state : List.of("queued", "executing")) {
                String prefix = path + "/" + state + "/" + queryId + "/";
                if (requestUri.startsWith(prefix)) {
                    String[] capability = requestUri.substring(prefix.length()).split("/", -1);
                    return capability.length == 2 && !capability[0].isEmpty() && capability[1].matches("[0-9]+");
                }
            }
        }
        return false;
    }

    private boolean isExecutingQueryContinuation(String requestUri, String queryId)
    {
        // Admission verifies the stored capability before dispatch. A missing issued executing
        // result ends this query obligation; HEAD and queued requests do not provide this guarantee.
        return statementPaths.stream().anyMatch(path -> requestUri.startsWith(path + "/executing/" + queryId + "/")) &&
                isWholeQueryCancellation(requestUri, queryId);
    }

    public void requestFailed(HttpServletRequest request)
    {
        requestFailed(admission(request));
    }

    public void requestFailed(RequestContext context)
    {
        requestFailed(context.admission);
    }

    private void requestFailed(Admission admission)
    {
        if (admission != null) {
            try {
                markUncertain(admission, 0);
            }
            catch (RuntimeException ignored) {
                // The durable admission remains a drain blocker when the database is unavailable.
            }
        }
    }

    private void markUncertain(Admission admission, int status)
    {
        try {
            store.markUncertain(admission.id());
        }
        catch (RuntimeException failure) {
            lifecycleStats.uncertain(admission, status, false);
            throw failure;
        }
        lifecycleStats.uncertain(admission, status, true);
    }

    public void requestRejectedBeforeDispatch(HttpServletRequest request)
    {
        try {
            Admission admission = admission(request);
            if (admission != null) {
                completionPhase(() -> guarded(() -> {
                    store.rejectAdmission(admission.id());
                    return null;
                }));
            }
        }
        finally {
            completeRequest(request);
        }
    }

    public void requestRejectedBeforeDispatch(RequestContext context)
    {
        try {
            if (context.admission != null) {
                completionPhase(() -> guarded(() -> {
                    store.rejectAdmission(context.admission.id());
                    return null;
                }));
            }
        }
        finally {
            context.close();
        }
    }

    public void requireAdmin(HttpServletRequest request)
    {
        if (!isEnabled()) {
            throw error(404, "Transaction awareness is disabled");
        }
        String authorization = TransactionIdentity.singleHeader(request, "Authorization").orElse("");
        Optional<String> separateToken = TransactionIdentity.singleHeader(request, "X-Gateway-Transaction-Admin-Token");
        String supplied = separateToken.orElse(authorization);
        String expected = separateToken.isPresent() ? config.getAdminToken() : "Bearer " + config.getAdminToken();
        if (!MessageDigest.isEqual(supplied.getBytes(UTF_8), expected.getBytes(UTF_8))) {
            throw error(403, "Transaction administration requires its configured token");
        }
    }

    public Map<String, Object> transaction(String transactionId)
    {
        return guarded(() -> {
            TransactionStore.TransactionBinding binding = store.getTransaction(canonicalTransactionId(transactionId))
                    .orElseThrow(() -> error(404, "Unknown transaction identifier"));
            return Map.of("transactionId", binding.transactionId(), "state", binding.state(), "backendName", binding.backend().name(), "backendUrl", binding.backend().url(), "incarnation", binding.backend().incarnation());
        });
    }

    private void verifyProcess(BackendRef backend, Duration remaining)
    {
        JsonNode info = processInfo(backend.url(), remaining, poolConfig.isForwardedProtoHttps());
        if (!backend.nodeId().equals(info.path("nodeId").asText()) || !backend.coordinatorId().equals(info.path("coordinatorId").asText())) {
            throw error(409, "The original coordinator process is no longer available");
        }
    }

    /**
     * Observed coordinator process identity of a <em>pooled</em> member. The Gateway verifies this
     * itself; an operator assertion is never accepted as a Gateway probe.
     */
    JsonNode processIdentity(String backendUrl)
    {
        return processInfo(backendUrl, new Duration(config.getProcessInfoTimeoutMillis(), MILLISECONDS), poolConfig.isForwardedProtoHttps());
    }

    private JsonNode processInfo(String backend, Duration remaining, boolean forwardedProtoHttps)
    {
        Duration timeout = new Duration(Math.max(1, Math.min(config.getProcessInfoTimeoutMillis(), remaining.toMillis())), MILLISECONDS);
        io.airlift.http.client.Request.Builder probe = prepareGet().setUri(URI.create(backend + "/v1/info"))
                .setRequestTimeout(timeout).setIdleTimeout(timeout).setFollowRedirects(false);
        if (forwardedProtoHttps) {
            // Only the protocol is asserted. No forwarded host is sent: a management probe must not be
            // able to host-qualify its own principal against an internal Service name, and this probe
            // carries no tenant credential at all.
            probe.addHeader(io.airlift.http.client.HeaderNames.X_FORWARDED_PROTO, "https");
        }
        StringResponse response = httpClient.execute(probe.build(), createStringResponseHandler());
        try {
            JsonNode info = JSON.readTree(response.getBody());
            if (response.getStatusCode() != 200 || info == null || !info.path("coordinator").asBoolean() || info.path("starting").asBoolean(true) || info.path("nodeId").asText().isBlank() || !info.path("coordinatorId").asText().matches("[a-zA-Z0-9]{5}")) {
                throw error(503, "Backend must expose a ready Trino coordinator process identity");
            }
            return info;
        }
        catch (IOException e) {
            throw error(503, "Backend process identity is unavailable");
        }
    }

    private static Optional<String> responseHeader(ProxyResponse response, String name)
    {
        List<String> values = response.headers().get(HeaderName.of(name));
        if (values.size() > 1 || (values.size() == 1 && values.getFirst().isEmpty())) {
            throw error(502, "Backend returned ambiguous transaction lifecycle headers");
        }
        return values.stream().findFirst();
    }

    private static Admission admission(HttpServletRequest request)
    {
        return (Admission) request.getAttribute(ADMISSION_ATTRIBUTE);
    }

    private String responseCapability(JsonNode value, String queryId)
    {
        if (!value.isTextual()) {
            throw error(502, "Backend returned an invalid result capability");
        }
        URI uri;
        try {
            uri = URI.create(value.asText());
        }
        catch (IllegalArgumentException e) {
            throw error(502, "Backend returned an invalid result capability");
        }
        if (uri.getScheme() == null || !List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null ||
                !canonicalPath(uri.getRawPath()) || !extractQueryIdIfPresent(uri.getRawPath(), null, statementPaths).equals(Optional.of(queryId))) {
            throw error(502, "Backend result capability does not match its query");
        }
        return capabilityHash(uri.getRawPath());
    }

    private static boolean canonicalPath(String path)
    {
        try {
            return path != null && path.startsWith("/") && !path.contains(";") && !path.contains("%") && !path.contains("\\") && !path.contains("//") && URI.create(path).normalize().getPath().equals(path);
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String capabilityHash(String path)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Hides client-supplied {@code Forwarded} and {@code X-Forwarded-*} headers from the proxied
     * request. The Gateway's own forwarding headers are added later by the proxy, so the coordinator
     * sees exactly one, trusted, externally routed host.
     */
    static HttpServletRequest withoutClientForwardedHeaders(HttpServletRequest request)
    {
        return new HttpServletRequestWrapper(request)
        {
            @Override
            public String getHeader(String name)
            {
                return TenantPrincipals.isForwardedHeader(name) ? null : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name)
            {
                return TenantPrincipals.isForwardedHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames()
            {
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                        .filter(name -> !TenantPrincipals.isForwardedHeader(name))
                        .toList());
            }
        };
    }

    static HttpServletRequest inlineResults(HttpServletRequest request)
    {
        return new HttpServletRequestWrapper(request)
        {
            @Override
            public String getHeader(String name)
            {
                return name.equalsIgnoreCase("X-Trino-Query-Data-Encoding") ? null : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name)
            {
                return name.equalsIgnoreCase("X-Trino-Query-Data-Encoding") ? Collections.emptyEnumeration() : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames()
            {
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                        .filter(name -> !name.equalsIgnoreCase("X-Trino-Query-Data-Encoding"))
                        .toList());
            }
        };
    }

    private static <T> T guarded(Supplier<T> action)
    {
        try {
            return action.get();
        }
        catch (WebApplicationException e) {
            throw e;
        }
        catch (StoreException e) {
            int status = switch (e.code()) {
                case NOT_FOUND -> 404;
                case OWNER_MISMATCH, TENANT_NOT_ADMITTED -> 403;
                case NOT_ACTIVE -> 503;
                default -> 409;
            };
            throw classifiedRoutingError(status, "ROUTING_STATE_" + e.code(), "Transaction routing state rejected the operation: " + e.code());
        }
        catch (RuntimeException e) {
            throw classifiedRoutingError(503, "ROUTING_STATE_UNAVAILABLE", "Transaction routing state is unavailable; the request was not reassigned");
        }
    }

    private static WebApplicationException classifiedRoutingError(int status, String code, String message)
    {
        return new WebApplicationException(Response.status(status).type("text/plain")
                .header("X-Trino-Gateway-Error", code).entity(message).build());
    }
}
