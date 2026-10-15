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

import io.trino.gateway.ha.persistence.GatewayDatabase;
import jakarta.annotation.Nullable;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

public final class TransactionStore
{
    private static final Pattern SERVICE_PRINCIPAL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?[.]svc_[0-9a-f]{24}");
    static final int QUERY_LOCK_NAMESPACE = 0x54585152;
    private static final int TRANSACTION_LOCK_NAMESPACE = 0x54585452;
    private static final int ADMISSION_BARRIER_NAMESPACE = 0x5458534C;

    // One statement snapshot must cover every obligation transferred atomically by completion.
    static final String DRAIN_STATUS_SQL =
            """
            SELECT b.state, b.generation,
              (SELECT count(*) FROM transaction_admission a WHERE a.incarnation = b.incarnation AND a.state <> 'COMPLETE') AS pending,
              (SELECT count(*) FROM transaction_binding t WHERE t.incarnation = b.incarnation AND t.state = 'OPEN') AS transactions,
              (SELECT count(*) FROM transaction_query q WHERE q.incarnation = b.incarnation AND NOT q.terminal)
                + (SELECT count(*) FROM transaction_query q WHERE q.incarnation = b.incarnation AND q.terminal AND q.retain_until > statement_timestamp()) AS queries
            FROM transaction_backend b WHERE b.incarnation = :id
            """;

    static final String SUPERSEDED_OBSERVATION = "SUPERSEDED";
    // Only COMPLETE admissions require an observation. On an UNCERTAIN one it records whether a response was seen.
    static final String UNRECORDED_RESPONSE_OBSERVATION = "RESPONSE_UNRECORDED";
    static final String NO_RESPONSE_OBSERVATION = "NO_RESPONSE";
    // Anchored suffix of fingerprint(): "<clear>:<terminal>:<retry seconds>[:capabilities:<hashes>]"; encoded identities precede it.
    static final String TERMINAL_OBSERVATION = "(true|false):true:[0-9]+(:capabilities:[0-9a-f,]+)?$";
    private static final String SUPERSEDED_PROOF =
            """
            a.query_id IS NOT NULL
            AND a.observation = 'NO_RESPONSE'
            AND EXISTS (SELECT 1 FROM transaction_query q WHERE q.query_id = a.query_id AND q.incarnation = a.incarnation AND q.terminal)
            AND EXISTS (SELECT 1 FROM transaction_admission s WHERE s.query_id = a.query_id AND s.incarnation = a.incarnation
              AND s.admission_id <> a.admission_id AND s.state = 'COMPLETE' AND s.observation ~ :terminal)
            """;

    private final GatewayDatabase jdbi;

    public TransactionStore(Jdbi jdbi)
    {
        this.jdbi = new GatewayDatabase(requireNonNull(jdbi, "jdbi is null"), GatewayDatabase.Operation.TRANSACTION_STORE);
    }

    /**
     * @param poolId set when this backend is a pooled member. It records which transport a probe of
     *         this backend must describe, so a pooled member reached over internal HTTP is probed
     *         differently from a legacy backend without an extra read to find that out.
     */
    public record BackendRef(String name, UUID incarnation, String url, String externalUrl, String routingGroup, @Nullable String nodeId, @Nullable String coordinatorId, @Nullable String poolId)
    {
        public BackendRef(String name, UUID incarnation, String url, String externalUrl, String routingGroup, @Nullable String nodeId, @Nullable String coordinatorId)
        {
            this(name, incarnation, url, externalUrl, routingGroup, nodeId, coordinatorId, null);
        }
    }

    public record Admission(UUID id, BackendRef backend, String ownerHash, @Nullable String transactionId, @Nullable String queryId) {}

    public record ResponseObservation(@Nullable String queryId, @Nullable String startedTxId, boolean clear, boolean terminal, int retryWindowSeconds, List<String> capabilityHashes)
    {
        public ResponseObservation
        {
            capabilityHashes = List.copyOf(capabilityHashes).stream().distinct().sorted().toList();
        }

        public ResponseObservation(@Nullable String queryId, @Nullable String startedTxId, boolean clear, boolean terminal, int retryWindowSeconds)
        {
            this(queryId, startedTxId, clear, terminal, retryWindowSeconds, List.of());
        }
    }

    public record QueryBinding(String queryId, String ownerHash, BackendRef backend, @Nullable String transactionId, boolean terminal) {}

    public record TransactionBinding(String transactionId, String ownerHash, BackendRef backend, String startQueryId, String state) {}

    public record DrainStatus(String name, UUID incarnation, String state, long generation, long pendingRequests, long openTransactions, long activeQueries, boolean readyToSeal, boolean drained, @Nullable String nodeId, @Nullable String coordinatorId) {}

    public enum ErrorCode
    {
        NOT_FOUND, OWNER_MISMATCH, CONFLICT, SEALED, NOT_DRAINED, NOT_ACTIVE, STALE_GENERATION, TENANT_NOT_ADMITTED
    }

    public static final class StoreException
            extends RuntimeException
            implements GatewayDatabase.ExpectedFailure
    {
        private final ErrorCode code;

        public StoreException(ErrorCode code, String message)
        {
            super(message);
            this.code = requireNonNull(code, "code is null");
        }

        public ErrorCode code()
        {
            return code;
        }
    }

    public Optional<BackendRef> getBackend(String name)
    {
        return jdbi.withHandle(handle -> findBackend(handle, name));
    }

    public Optional<QueryBinding> getQuery(String queryId)
    {
        return jdbi.withHandle(handle -> findQuery(handle, queryId));
    }

    public Optional<TransactionBinding> getTransaction(String transactionId)
    {
        return jdbi.withHandle(handle -> findTransaction(handle, transactionId));
    }

    /**
     * Admits new independent work to one pooled member. The candidate was chosen from an advisory
     * snapshot, so the member's phase is revalidated here under the pool's share lock: this is the
     * boundary where eligibility and lifecycle transitions serialize against each other.
     */
    /**
     * The one principal a request will be authenticated as, resolved to its tenant through the
     * controller-published mapping in the same transaction as the admission itself. No principal, or a
     * principal the mapping does not name, is refused: the restriction fails closed.
     */
    public record TenantGate(@Nullable String principal) {}

    public Admission admitPooledMember(String poolId, String backendName, String ownerHash)
    {
        return admitPooledMember(poolId, backendName, ownerHash, null);
    }

    public Admission admitPooledMember(String poolId, String backendName, String ownerHash, @Nullable TenantGate gate)
    {
        return jdbi.inReadCommittedTransaction(handle -> {
            lockRoute(handle, poolId, false);
            check(isPooledMode(handle, poolId), ErrorCode.CONFLICT, "Routing group is not in pooled mode");
            checkTenantAdmitted(handle, poolId, gate);
            BackendRef backend = shareBackend(handle, backendName);
            check(poolId.equals(memberPool(handle, backend)), ErrorCode.CONFLICT, "Backend is not a member of this pool");
            check(backend.routingGroup().equals(poolId), ErrorCode.CONFLICT, "Backend belongs to another routing group");
            check(backendState(handle, backend).equals("ACTIVE"), ErrorCode.NOT_ACTIVE, "Member does not accept new statements");
            return insertAdmission(handle, backend, ownerHash, null, null);
        });
    }

    public Admission admitTransaction(String transactionId, String ownerHash)
    {
        return jdbi.inReadCommittedTransaction(handle -> {
            TransactionBinding initial = findTransaction(handle, transactionId).orElseThrow(() -> missing("transaction"));
            BackendRef backend = shareKnownIncarnation(handle, initial.backend());
            lockIdentity(handle, TRANSACTION_LOCK_NAMESPACE, transactionId);
            TransactionBinding binding = findTransaction(handle, transactionId).orElseThrow(() -> missing("transaction"));
            checkOwner(binding.ownerHash(), ownerHash);
            check(binding.state().equals("OPEN"), ErrorCode.CONFLICT, "Transaction is closed");
            checkNotSealed(handle, backend);
            return insertAdmission(handle, backend, ownerHash, transactionId, null);
        });
    }

    public Admission admitQuery(String queryId, Optional<String> ownerHash, Optional<String> transactionId)
    {
        return admitQuery(queryId, ownerHash, transactionId, Optional.empty());
    }

    public Admission admitQuery(String queryId, Optional<String> ownerHash, Optional<String> transactionId, Optional<String> capabilityHash)
    {
        return jdbi.inReadCommittedTransaction(handle -> {
            QueryBinding initial = findQuery(handle, queryId).orElseThrow(() -> missing("query"));
            BackendRef backend = shareKnownIncarnation(handle, initial.backend());
            lockIdentity(handle, QUERY_LOCK_NAMESPACE, queryId);
            QueryBinding binding = findQuery(handle, queryId).orElseThrow(() -> missing("query"));
            if (binding.transactionId() != null) {
                lockIdentity(handle, TRANSACTION_LOCK_NAMESPACE, binding.transactionId());
            }
            ownerHash.ifPresent(owner -> checkOwner(binding.ownerHash(), owner));
            transactionId.ifPresent(transaction -> check(transaction.equals(binding.transactionId()), ErrorCode.CONFLICT, "Query and transaction disagree"));
            capabilityHash.ifPresent(hash -> {
                check(hash.matches("[0-9a-f]{64}"), ErrorCode.NOT_FOUND, "Unknown query continuation capability");
                check(handle.createQuery("SELECT EXISTS (SELECT 1 FROM transaction_query_capability WHERE query_id = :query AND capability_hash = :hash)")
                        .bind("query", queryId).bind("hash", hash).mapTo(Boolean.class).one(), ErrorCode.NOT_FOUND, "Unknown query continuation capability");
            });
            checkNotSealed(handle, backend);
            return insertAdmission(handle, backend, binding.ownerHash(), binding.transactionId(), queryId);
        });
    }

    public void recordResponse(UUID admissionId, ResponseObservation observation)
    {
        check(observation.retryWindowSeconds() >= 0, ErrorCode.CONFLICT, "Retry window cannot be negative");
        check(!(observation.clear() && observation.startedTxId() != null), ErrorCode.CONFLICT, "Response both starts and clears a transaction");
        observation.capabilityHashes().forEach(hash -> check(hash.matches("[0-9a-f]{64}"), ErrorCode.CONFLICT, "Capability must be a lowercase SHA-256 hash"));
        jdbi.withOperation(GatewayDatabase.Operation.RECORD_RESPONSE).useIdempotentTransaction(handle -> {
            Admission admission = lockAdmissionBackend(handle, admissionId);
            String fingerprint = fingerprint(observation);
            if (isComplete(handle, admissionId)) {
                check(fingerprint.equals(recordedObservation(handle, admissionId)), ErrorCode.CONFLICT, "Admission response changed");
                return;
            }
            String queryId = observation.queryId() == null ? admission.queryId() : observation.queryId();
            check(queryId != null && !queryId.isEmpty(), ErrorCode.CONFLICT, "Response has no query identity");
            check(admission.queryId() == null || admission.queryId().equals(queryId), ErrorCode.CONFLICT, "Response query identity changed");
            lockIdentity(handle, QUERY_LOCK_NAMESPACE, queryId);
            Optional<QueryBinding> previous = findQuery(handle, queryId);
            previous.ifPresent(query -> {
                checkOwner(query.ownerHash(), admission.ownerHash());
                check(query.backend().incarnation().equals(admission.backend().incarnation()), ErrorCode.CONFLICT, "Query backend changed");
            });

            String transactionId = admission.transactionId();
            if (previous.isPresent()) {
                String previousTransaction = previous.orElseThrow().transactionId();
                check(transactionId == null || previousTransaction == null || transactionId.equals(previousTransaction), ErrorCode.CONFLICT, "Query transaction changed");
                if (transactionId == null) {
                    transactionId = previousTransaction;
                }
            }
            if (observation.startedTxId() != null) {
                check(transactionId == null || transactionId.equals(observation.startedTxId()), ErrorCode.CONFLICT, "Response starts a different transaction");
                transactionId = observation.startedTxId();
            }
            if (transactionId != null) {
                lockIdentity(handle, TRANSACTION_LOCK_NAMESPACE, transactionId);
            }
            if (observation.startedTxId() != null) {
                bindStartedTransaction(handle, observation.startedTxId(), admission, queryId);
            }
            bindQuery(handle, queryId, admission, transactionId);
            for (String hash : observation.capabilityHashes()) {
                handle.createUpdate("INSERT INTO transaction_query_capability (query_id, capability_hash) VALUES (:query, :hash) ON CONFLICT DO NOTHING")
                        .bind("query", queryId).bind("hash", hash).execute();
            }
            if (observation.clear()) {
                check(transactionId != null, ErrorCode.CONFLICT, "Response clears an unknown transaction");
                TransactionBinding transaction = findTransaction(handle, transactionId).orElseThrow(() -> missing("transaction"));
                checkOwner(transaction.ownerHash(), admission.ownerHash());
                check(transaction.backend().incarnation().equals(admission.backend().incarnation()), ErrorCode.CONFLICT, "Transaction backend changed");
                handle.createUpdate("UPDATE transaction_binding SET state = 'CLOSED' WHERE transaction_id = :id").bind("id", transactionId).execute();
            }
            if (observation.terminal()) {
                handle.createUpdate(
                        """
                        UPDATE transaction_query SET terminal = TRUE,
                          retain_until = GREATEST(retain_until, clock_timestamp() + make_interval(secs => :seconds))
                        WHERE query_id = :id
                        """).bind("seconds", observation.retryWindowSeconds()).bind("id", queryId).execute();
            }
            handle.createUpdate(
                    """
                    UPDATE transaction_admission SET state = 'COMPLETE', observation = :observation, query_id = :query, transaction_id = :transaction
                    WHERE admission_id = :id
                    """).bind("id", admissionId).bind("observation", fingerprint).bind("query", queryId).bind("transaction", transactionId).execute();
        });
    }

    /**
     * Records that a request's outcome is unknown, which keeps its admission blocking drain. A
     * request that failed without any backend response may instead settle as superseded: its query
     * is terminal and a sibling admission of that query recorded the terminal observation. That
     * happens when a client polls one query over concurrent requests and drops the rest once one
     * delivers the final result. Responses are forwarded only after they are recorded, so the client
     * never saw anything the failed request carried, and the terminal query has nothing left to run.
     *
     * @param supersedable true only when no backend response was forwarded or recorded. Otherwise the
     *         admission is marked so that neither this call nor the sweep ever settles it.
     * @return true when this call settled the admission as superseded
     */
    public boolean markUncertain(UUID admissionId, boolean supersedable)
    {
        return jdbi.withOperation(GatewayDatabase.Operation.MARK_UNCERTAIN).inIdempotentTransaction(handle -> {
            lockAdmissionBackend(handle, admissionId);
            handle.createUpdate(
                            """
                            UPDATE transaction_admission SET state = 'UNCERTAIN',
                              observation = CASE WHEN NOT :supersedable THEN :unrecorded WHEN state = 'PENDING' THEN :noResponse ELSE observation END
                            WHERE admission_id = :id AND state <> 'COMPLETE'
                            """)
                    .bind("id", admissionId).bind("supersedable", supersedable).bind("unrecorded", UNRECORDED_RESPONSE_OBSERVATION).bind("noResponse", NO_RESPONSE_OBSERVATION).execute();
            return supersedable && settleIfSuperseded(handle, admissionId);
        });
    }

    /**
     * Settles uncertain admissions that meet the superseded proof, typically because they failed
     * before a sibling recorded their query's terminal result. Only proven
     * discharged obligations settle; every other uncertain admission keeps blocking drain.
     *
     * @return the admissions this call settled, and the first failure that did not stop it
     */
    public SupersededSweep settleSupersededAdmissions(int limit)
    {
        List<UUID> candidates = jdbi.withOperation(GatewayDatabase.Operation.SETTLE_SUPERSEDED).withHandle(handle -> handle.createQuery(
                        "SELECT a.admission_id FROM transaction_admission a WHERE a.state = 'UNCERTAIN' AND " + SUPERSEDED_PROOF + " ORDER BY a.created_at LIMIT :limit")
                .bind("terminal", TERMINAL_OBSERVATION).bind("limit", limit).mapTo(UUID.class).list());
        List<Admission> settled = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (UUID candidate : candidates) {
            // One failing candidate must not starve the rest, which are ordered oldest first.
            try {
                jdbi.withOperation(GatewayDatabase.Operation.SETTLE_SUPERSEDED).inIdempotentTransaction(handle -> {
                    Admission admission = lockAdmissionBackend(handle, candidate);
                    return settleIfSuperseded(handle, candidate) ? Optional.of(admission) : Optional.<Admission>empty();
                }).ifPresent(settled::add);
            }
            catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
                else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        return new SupersededSweep(settled, candidates.size(), Optional.ofNullable(firstFailure));
    }

    /**
     * @param candidates the number of candidates read, which bounds settled plus failed ones
     * @param failure the first per-candidate failure, with later ones suppressed into it
     */
    public record SupersededSweep(List<Admission> settled, int candidates, Optional<RuntimeException> failure) {}

    // The admission row is locked by the caller. Terminal state and completed admissions are monotonic, so the proof cannot be revoked.
    // Reports only a settlement this call made, so a repeated call is not counted twice.
    private static boolean settleIfSuperseded(Handle handle, UUID admissionId)
    {
        return handle.createUpdate(
                        "UPDATE transaction_admission a SET state = 'COMPLETE', observation = '" + SUPERSEDED_OBSERVATION + "' " +
                                "WHERE a.admission_id = :id AND a.state <> 'COMPLETE' AND " + SUPERSEDED_PROOF)
                .bind("id", admissionId).bind("terminal", TERMINAL_OBSERVATION).execute() > 0;
    }

    /**
     * Position of a retention scan in its index order. A run starts from {@link #START} and passes
     * each batch's cursor to the next, so rows a batch left alone are not selected again in that run.
     */
    public record RetentionCursor(String timestamp, String id)
    {
        public static final RetentionCursor START = new RetentionCursor("-infinity", "");

        // Sorts before every query identifier and every admission UUID, so the start of a scan includes the first row.
        String uuid()
        {
            return id.isEmpty() ? "00000000-0000-0000-0000-000000000000" : id;
        }
    }

    /**
     * One retention batch. {@code scanned} counts the index entries the batch examined, eligible or
     * not, so {@code scanned < limit} means the run reached the end of the expired range.
     */
    public record RetentionBatch(int scanned, int queries, int admissions, int capabilities, int bindings, RetentionCursor cursor)
    {
        public long rows()
        {
            return (long) queries + admissions + capabilities + bindings;
        }
    }

    // A query row is settled history only when nothing still counts it, completes it, or settles a sibling through it.
    private static final String SETTLED_QUERY =
            """
            q.terminal AND q.retain_until < statement_timestamp() - make_interval(days => :days)
            AND NOT EXISTS (SELECT 1 FROM transaction_admission a WHERE a.query_id = q.query_id AND a.state <> 'COMPLETE')
            AND (q.transaction_id IS NULL OR EXISTS (SELECT 1 FROM transaction_binding t WHERE t.transaction_id = q.transaction_id AND t.state = 'CLOSED'))
            """;

    /**
     * Deletes settled query history from one bounded window of the expiry index: terminal queries
     * whose retry window ended at least {@code retentionDays} ago, whose admissions are all COMPLETE
     * and whose transaction, if any, is CLOSED, together with their admissions, capabilities and
     * finally unreferenced CLOSED transaction bindings. Nothing this deletes is counted by drain
     * status, read by the superseded proof, or reachable by a continuation inside its retry window.
     * <p>
     * Ineligible rows in the window, such as queries pinned by an uncertain admission or an open
     * transaction, only advance the cursor, so they cannot starve the rows behind them. Identity locks
     * are tried before any row lock and rows are locked with SKIP LOCKED, so concurrent replicas
     * partition the backlog and the batch never queues behind a request that already holds a query.
     */
    public RetentionBatch purgeSettledQueries(int retentionDays, int limit, RetentionCursor after)
    {
        checkRetention(retentionDays, limit);
        return jdbi.withOperation(GatewayDatabase.Operation.LEDGER_RETENTION).inReadCommittedIdempotentTransaction(handle -> {
            List<String[]> window = handle.createQuery(
                            """
                            SELECT q.query_id, q.retain_until::text AS retain_until, q.transaction_id,
                              (SELECT NOT EXISTS (SELECT 1 FROM transaction_admission a WHERE a.query_id = q.query_id AND a.state <> 'COMPLETE')
                                AND (q.transaction_id IS NULL OR EXISTS (SELECT 1 FROM transaction_binding t WHERE t.transaction_id = q.transaction_id AND t.state = 'CLOSED'))) AS settled
                            FROM transaction_query q
                            WHERE q.terminal AND q.retain_until < statement_timestamp() - make_interval(days => :days)
                              AND (q.retain_until, q.query_id) > (CAST(:afterTimestamp AS timestamptz), :afterId)
                            ORDER BY q.retain_until, q.query_id LIMIT :limit
                            """)
                    .bind("days", retentionDays).bind("limit", limit).bind("afterTimestamp", after.timestamp()).bind("afterId", after.id())
                    .map((rs, _) -> new String[] {rs.getString("query_id"), rs.getString("retain_until"), rs.getString("transaction_id"), rs.getBoolean("settled") ? "y" : null}).list();
            if (window.isEmpty()) {
                return new RetentionBatch(0, 0, 0, 0, 0, after);
            }
            RetentionCursor cursor = new RetentionCursor(window.getLast()[1], window.getLast()[0]);
            List<String[]> settled = window.stream().filter(row -> row[3] != null).toList();
            // Admission, continuation and completion hold a query's identity lock while they use its rows; never wait for one.
            Set<String> lockedQueries = tryLockIdentities(handle, QUERY_LOCK_NAMESPACE, settled.stream().map(row -> row[0]).toList());
            List<String[]> candidates = settled.stream().filter(row -> lockedQueries.contains(row[0])).toList();
            Set<String> lockedTransactions = tryLockIdentities(
                    handle,
                    TRANSACTION_LOCK_NAMESPACE,
                    candidates.stream().map(row -> row[2]).filter(Objects::nonNull).distinct().toList());
            List<String> lockedIds = candidates.stream().filter(row -> row[2] == null || lockedTransactions.contains(row[2])).map(row -> row[0]).toList();
            if (lockedIds.isEmpty()) {
                return new RetentionBatch(window.size(), 0, 0, 0, 0, cursor);
            }
            List<String> rowLocked = handle.createQuery("SELECT query_id FROM transaction_query WHERE query_id = ANY(:queries) FOR UPDATE SKIP LOCKED")
                    .bindArray("queries", String.class, lockedIds).mapTo(String.class).list();
            Map<String, Long> lockedAdmissions = new HashMap<>();
            if (!rowLocked.isEmpty()) {
                handle.createQuery("SELECT query_id FROM transaction_admission WHERE query_id = ANY(:queries) FOR UPDATE SKIP LOCKED")
                        .bindArray("queries", String.class, rowLocked).mapTo(String.class)
                        .forEach(query -> lockedAdmissions.merge(query, 1L, Long::sum));
            }
            // Recheck under the locks: no admission can join or leave these queries any more, and every admission must be locked here.
            List<String> doomed = rowLocked.isEmpty() ? List.of() : handle.createQuery(
                    "SELECT q.query_id, (SELECT count(*) FROM transaction_admission a WHERE a.query_id = q.query_id) AS admissions " +
                            "FROM transaction_query q WHERE q.query_id = ANY(:queries) AND " + SETTLED_QUERY)
                                                                    .bindArray("queries", String.class, rowLocked).bind("days", retentionDays)
                                                                    .map((rs, _) -> rs.getLong("admissions") == lockedAdmissions.getOrDefault(rs.getString("query_id"), 0L) ? rs.getString("query_id") : null)
                                                                    .list().stream().filter(Objects::nonNull).toList();
            if (doomed.isEmpty()) {
                return new RetentionBatch(window.size(), 0, 0, 0, 0, cursor);
            }
            int admissions = handle.createUpdate("DELETE FROM transaction_admission WHERE query_id = ANY(:queries)")
                    .bindArray("queries", String.class, doomed).execute();
            int capabilities = handle.createUpdate("DELETE FROM transaction_query_capability WHERE query_id = ANY(:queries)")
                    .bindArray("queries", String.class, doomed).execute();
            int queries = handle.createUpdate("DELETE FROM transaction_query WHERE query_id = ANY(:queries)")
                    .bindArray("queries", String.class, doomed).execute();
            Set<String> doomedSet = Set.copyOf(doomed);
            List<String> transactions = candidates.stream().filter(row -> doomedSet.contains(row[0]) && row[2] != null).map(row -> row[2]).distinct().toList();
            return new RetentionBatch(window.size(), queries, admissions, capabilities, deleteClosedBindings(handle, transactions), cursor);
        });
    }

    /**
     * Deletes rejected admissions created at least {@code retentionDays} ago from one bounded window.
     * A rejection is COMPLETE without a query, so it is neither an obligation nor superseded-proof
     * evidence. A CLOSED transaction binding it was the last reference to goes with it.
     */
    public RetentionBatch purgeRejectedAdmissions(int retentionDays, int limit, RetentionCursor after)
    {
        checkRetention(retentionDays, limit);
        return jdbi.withOperation(GatewayDatabase.Operation.LEDGER_RETENTION).inReadCommittedIdempotentTransaction(handle -> {
            List<String[]> window = handle.createQuery(
                            """
                            SELECT admission_id::text AS admission_id, created_at::text AS created_at, transaction_id FROM transaction_admission
                            WHERE query_id IS NULL AND state = 'COMPLETE' AND created_at < statement_timestamp() - make_interval(days => :days)
                              AND (created_at, admission_id) > (CAST(:afterTimestamp AS timestamptz), CAST(:afterId AS uuid))
                            ORDER BY created_at, admission_id LIMIT :limit
                            """)
                    .bind("days", retentionDays).bind("limit", limit).bind("afterTimestamp", after.timestamp()).bind("afterId", after.uuid())
                    .map((rs, _) -> new String[] {rs.getString("admission_id"), rs.getString("created_at"), rs.getString("transaction_id")}).list();
            if (window.isEmpty()) {
                return new RetentionBatch(0, 0, 0, 0, 0, after);
            }
            RetentionCursor cursor = new RetentionCursor(window.getLast()[1], window.getLast()[0]);
            Set<String> lockedTransactions = tryLockIdentities(
                    handle,
                    TRANSACTION_LOCK_NAMESPACE,
                    window.stream().map(row -> row[2]).filter(Objects::nonNull).distinct().toList());
            List<String> lockedIds = window.stream().filter(row -> row[2] == null || lockedTransactions.contains(row[2])).map(row -> row[0]).toList();
            // A COMPLETE admission never changes again, so the locked rows still satisfy the predicate.
            List<String[]> doomed = lockedIds.isEmpty() ? List.of() : handle.createQuery(
                    "SELECT admission_id::text AS admission_id, transaction_id FROM transaction_admission WHERE admission_id = ANY(CAST(:admissions AS uuid[])) " +
                            "AND query_id IS NULL AND state = 'COMPLETE' FOR UPDATE SKIP LOCKED")
                                                                      .bindArray("admissions", String.class, lockedIds)
                                                                      .map((rs, _) -> new String[] {rs.getString("admission_id"), rs.getString("transaction_id")}).list();
            int admissions = doomed.isEmpty() ? 0 : handle.createUpdate("DELETE FROM transaction_admission WHERE admission_id = ANY(CAST(:admissions AS uuid[]))")
                                                    .bindArray("admissions", String.class, doomed.stream().map(row -> row[0]).toList()).execute();
            List<String> transactions = doomed.stream().map(row -> row[1]).filter(Objects::nonNull).distinct().toList();
            return new RetentionBatch(window.size(), 0, admissions, 0, deleteClosedBindings(handle, transactions), cursor);
        });
    }

    // The caller holds each transaction's identity lock, which every writer of a reference to it holds too.
    private static int deleteClosedBindings(Handle handle, List<String> transactionIds)
    {
        if (transactionIds.isEmpty()) {
            return 0;
        }
        return handle.createUpdate(
                        """
                        DELETE FROM transaction_binding WHERE transaction_id IN (
                          SELECT t.transaction_id FROM transaction_binding t WHERE t.transaction_id = ANY(:transactions) AND t.state = 'CLOSED'
                          AND NOT EXISTS (SELECT 1 FROM transaction_query q WHERE q.transaction_id = t.transaction_id)
                          AND NOT EXISTS (SELECT 1 FROM transaction_admission a WHERE a.transaction_id = t.transaction_id)
                          FOR UPDATE OF t SKIP LOCKED)
                        """)
                .bindArray("transactions", String.class, transactionIds).execute();
    }

    private static Set<String> tryLockIdentities(Handle handle, int namespace, List<String> identities)
    {
        if (identities.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(handle.createQuery(
                        "SELECT x.identity FROM unnest(CAST(:identities AS text[]), CAST(:hashes AS int[])) AS x(identity, hash) WHERE pg_try_advisory_xact_lock(:namespace, x.hash)")
                .bindArray("identities", String.class, identities)
                .bindArray("hashes", Integer.class, identities.stream().map(String::hashCode).toList())
                .bind("namespace", namespace).mapTo(String.class).list());
    }

    private static void checkRetention(int retentionDays, int limit)
    {
        if (retentionDays < 1 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Invalid ledger retention bounds");
        }
    }

    public void rejectAdmission(UUID admissionId)
    {
        jdbi.withOperation(GatewayDatabase.Operation.REJECT_ADMISSION).useIdempotentTransaction(handle -> {
            lockAdmissionBackend(handle, admissionId);
            if (isComplete(handle, admissionId)) {
                check("REJECTED".equals(recordedObservation(handle, admissionId)), ErrorCode.CONFLICT, "Admission already has a different outcome");
                return;
            }
            handle.createUpdate("UPDATE transaction_admission SET state = 'COMPLETE', observation = 'REJECTED' WHERE admission_id = :id")
                    .bind("id", admissionId).execute();
        });
    }

    public DrainStatus drainStatus(String name)
    {
        return jdbi.inReadCommittedReadTransaction(handle -> {
            BackendRef observed = findBackend(handle, name).orElseThrow(() -> missing("backend"));
            return status(handle, observed.poolId() == null ? lockBackend(handle, name) : observed);
        });
    }

    static void lockRoute(Handle handle, String routingGroup)
    {
        lockRoute(handle, routingGroup, true);
    }

    static void lockRoute(Handle handle, String routingGroup, boolean exclusive)
    {
        handle.createUpdate("INSERT INTO transaction_route (routing_group) VALUES (:group) ON CONFLICT DO NOTHING").bind("group", routingGroup).execute();
        handle.createQuery("SELECT routing_group FROM transaction_route WHERE routing_group = :group FOR " + (exclusive ? "UPDATE" : "SHARE"))
                .bind("group", routingGroup).mapTo(String.class).one();
    }

    private static Admission insertAdmission(Handle handle, BackendRef backend, String ownerHash, @Nullable String transactionId, @Nullable String queryId)
    {
        check(ownerHash != null && !ownerHash.isEmpty(), ErrorCode.OWNER_MISMATCH, "Missing owner binding");
        UUID id = UUID.randomUUID();
        handle.createUpdate(
                        """
                        INSERT INTO transaction_admission (admission_id, incarnation, owner_hash, transaction_id, query_id, state)
                        VALUES (:id, :backend, :owner, :transaction, :query, 'PENDING')
                        """).bind("id", id).bind("backend", backend.incarnation()).bind("owner", ownerHash)
                .bind("transaction", transactionId).bind("query", queryId).execute();
        return new Admission(id, backend, ownerHash, transactionId, queryId);
    }

    private static Admission lockAdmissionBackend(Handle handle, UUID admissionId)
    {
        Admission initial = findAdmission(handle, admissionId, false);
        if (initial.backend().poolId() == null) {
            shareIncarnation(handle, initial.backend().incarnation());
        }
        return findAdmission(handle, admissionId, true);
    }

    static void admissionBarrier(Handle handle, UUID incarnation, boolean exclusive)
    {
        handle.createQuery("SELECT pg_advisory_xact_lock" + (exclusive ? "" : "_shared") + "(:namespace, :identity)")
                .bind("namespace", ADMISSION_BARRIER_NAMESPACE).bind("identity", incarnation.hashCode()).mapTo(String.class).one();
    }

    private static Admission findAdmission(Handle handle, UUID admissionId, boolean exclusive)
    {
        return handle.createQuery(
                        """
                        SELECT a.admission_id, a.owner_hash, a.transaction_id, a.query_id, b.*
                        FROM transaction_admission a JOIN transaction_backend b USING (incarnation)
                        WHERE admission_id = :id
                        """ + (exclusive ? " FOR UPDATE OF a" : "")).bind("id", admissionId)
                .map((rs, _) -> new Admission(rs.getObject("admission_id", UUID.class), backend(rs), rs.getString("owner_hash"), rs.getString("transaction_id"), rs.getString("query_id")))
                .findOne().orElseThrow(() -> missing("admission"));
    }

    private static void lockIdentity(Handle handle, int namespace, String identity)
    {
        // Admissions acquire the retirement barrier before query and transaction identities.
        // Completion locks its admission row, then query and transaction identities without the lifecycle fence.
        handle.createQuery("SELECT pg_advisory_xact_lock(:namespace, :identity)")
                .bind("namespace", namespace).bind("identity", identity.hashCode()).mapTo(String.class).one();
    }

    private static void bindStartedTransaction(Handle handle, String transactionId, Admission admission, String queryId)
    {
        handle.createUpdate(
                """
                INSERT INTO transaction_binding (transaction_id, owner_hash, incarnation, start_query_id, state)
                VALUES (:id, :owner, :backend, :query, 'OPEN') ON CONFLICT DO NOTHING
                """).bind("id", transactionId).bind("owner", admission.ownerHash()).bind("backend", admission.backend().incarnation()).bind("query", queryId).execute();
        TransactionBinding transaction = findTransaction(handle, transactionId).orElseThrow(() -> missing("transaction"));
        checkOwner(transaction.ownerHash(), admission.ownerHash());
        check(transaction.backend().incarnation().equals(admission.backend().incarnation()) && transaction.startQueryId().equals(queryId),
                ErrorCode.CONFLICT,
                "Transaction start binding changed");
    }

    private static void bindQuery(Handle handle, String queryId, Admission admission, @Nullable String transactionId)
    {
        handle.createUpdate(
                """
                INSERT INTO transaction_query (query_id, owner_hash, incarnation, transaction_id)
                VALUES (:id, :owner, :backend, :transaction) ON CONFLICT DO NOTHING
                """).bind("id", queryId).bind("owner", admission.ownerHash()).bind("backend", admission.backend().incarnation()).bind("transaction", transactionId).execute();
        QueryBinding query = findQuery(handle, queryId).orElseThrow(() -> missing("query"));
        checkOwner(query.ownerHash(), admission.ownerHash());
        check(query.backend().incarnation().equals(admission.backend().incarnation()), ErrorCode.CONFLICT, "Query backend changed");
        check(query.transactionId() == null || query.transactionId().equals(transactionId), ErrorCode.CONFLICT, "Query transaction changed");
        if (query.transactionId() == null && transactionId != null) {
            handle.createUpdate("UPDATE transaction_query SET transaction_id = :transaction WHERE query_id = :id")
                    .bind("transaction", transactionId).bind("id", queryId).execute();
        }
    }

    private static DrainStatus status(Handle handle, BackendRef backend)
    {
        return handle.createQuery(DRAIN_STATUS_SQL).bind("id", backend.incarnation())
                .map((rs, _) -> {
                    String state = rs.getString("state");
                    long pending = rs.getLong("pending");
                    long transactions = rs.getLong("transactions");
                    long queries = rs.getLong("queries");
                    return new DrainStatus(
                            backend.name(),
                            backend.incarnation(),
                            state,
                            rs.getLong("generation"),
                            pending,
                            transactions,
                            queries,
                            state.equals("DRAINING") && pending == 0 && transactions == 0 && queries == 0,
                            state.equals("SEALED"),
                            backend.nodeId(),
                            backend.coordinatorId());
                }).one();
    }

    private static BackendRef lockBackend(Handle handle, String name)
    {
        return handle.createQuery("SELECT * FROM transaction_backend WHERE current_name = :name FOR UPDATE").bind("name", name)
                .map((rs, _) -> backend(rs)).findOne().orElseThrow(() -> missing("backend"));
    }

    private static BackendRef shareBackend(Handle handle, String name)
    {
        BackendRef observed = findBackend(handle, name).orElseThrow(() -> missing("backend"));
        if (observed.poolId() != null) {
            return shareKnownIncarnation(handle, observed);
        }
        return handle.createQuery("SELECT * FROM transaction_backend WHERE current_name = :name FOR SHARE").bind("name", name)
                .map((rs, _) -> backend(rs)).findOne().orElseThrow(() -> missing("backend"));
    }

    private static BackendRef shareIncarnation(Handle handle, UUID incarnation)
    {
        BackendRef observed = handle.createQuery("SELECT * FROM transaction_backend WHERE incarnation = :id").bind("id", incarnation)
                .map((rs, _) -> backend(rs)).findOne().orElseThrow(() -> missing("backend incarnation"));
        return shareKnownIncarnation(handle, observed);
    }

    private static BackendRef shareKnownIncarnation(Handle handle, BackendRef observed)
    {
        UUID incarnation = observed.incarnation();
        BackendRef locked = handle.createQuery("SELECT * FROM transaction_backend WHERE incarnation = :id FOR " + (observed.poolId() == null ? "SHARE" : "KEY SHARE"))
                .bind("id", incarnation).map((rs, _) -> backend(rs)).findOne().orElseThrow(() -> missing("backend incarnation"));
        if (locked.poolId() != null) {
            // KEY SHARE also fences older Gateways whose seal still takes FOR UPDATE.
            admissionBarrier(handle, incarnation, false);
        }
        return locked;
    }

    private static Optional<BackendRef> findBackend(Handle handle, String name)
    {
        return handle.createQuery("SELECT * FROM transaction_backend WHERE current_name = :name").bind("name", name)
                .map((rs, _) -> backend(rs)).findOne();
    }

    private static Optional<QueryBinding> findQuery(Handle handle, String queryId)
    {
        return handle.createQuery("SELECT q.*, b.* FROM transaction_query q JOIN transaction_backend b USING (incarnation) WHERE query_id = :id")
                .bind("id", queryId).map((rs, _) -> new QueryBinding(rs.getString("query_id"), rs.getString("owner_hash"), backend(rs), rs.getString("transaction_id"), rs.getBoolean("terminal"))).findOne();
    }

    private static Optional<TransactionBinding> findTransaction(Handle handle, String transactionId)
    {
        return handle.createQuery("SELECT t.transaction_id, t.owner_hash, t.start_query_id, t.state AS transaction_state, b.* FROM transaction_binding t JOIN transaction_backend b USING (incarnation) WHERE transaction_id = :id")
                .bind("id", transactionId).map((rs, _) -> new TransactionBinding(rs.getString("transaction_id"), rs.getString("owner_hash"), backend(rs), rs.getString("start_query_id"), rs.getString("transaction_state"))).findOne();
    }

    private static BackendRef backend(ResultSet rs)
            throws SQLException
    {
        return new BackendRef(
                rs.getString("name"),
                rs.getObject("incarnation", UUID.class),
                rs.getString("backend_url"),
                rs.getString("external_url"),
                rs.getString("routing_group"),
                rs.getString("node_id"),
                rs.getString("coordinator_id"),
                rs.getString("pool_id"));
    }

    private static String backendState(Handle handle, BackendRef backend)
    {
        return handle.createQuery("SELECT state FROM transaction_backend WHERE incarnation = :id").bind("id", backend.incarnation()).mapTo(String.class).one();
    }

    /**
     * Continuations and statements inside an already bound transaction survive a drain, but not a
     * seal, an irreversible retirement claim, or a proven process loss.
     */
    private static void checkNotSealed(Handle handle, BackendRef backend)
    {
        check(!List.of("SEALED", "RETIRING", "RETIRED", "LOST").contains(backendState(handle, backend)), ErrorCode.SEALED, "Backend is sealed");
    }

    /**
     * Deny-only admission restriction. It never grants access: the coordinator still verifies the
     * credential and its authorization policy still refuses impersonation. It only refuses to
     * dispatch new independent work whose claimed tenant is not admitted, and fails closed when the
     * claim names no admitted tenant at all.
     * <p>
     * Query continuations and statements inside an already bound transaction are not restricted here,
     * so work that was already dispatched stays pinned and observable.
     */
    private static void checkTenantAdmitted(Handle handle, String poolId, @Nullable TenantGate gate)
    {
        boolean enabled = handle.createQuery("SELECT tenant_admission_enabled FROM pool WHERE pool_id = :pool")
                .bind("pool", poolId).mapTo(Boolean.class).findOne().orElse(false);
        if (!enabled) {
            return;
        }
        check(gate != null && gate.principal() != null, ErrorCode.TENANT_NOT_ADMITTED, "The request presents no credential to restrict");
        // A service grant uses the already-published root binding; the database name never identifies a tenant by itself.
        List<String> states = handle.createQuery(
                        """
                        WITH tenants AS (
                          SELECT tenant FROM pool_tenant_principal WHERE pool_id = :pool AND principal = :principal
                          UNION
                          SELECT tenant FROM pool_tenant_principal WHERE pool_id = :pool AND principal = :serviceRoot
                        )
                        SELECT coalesce(a.state, 'PENDING') AS state
                        FROM tenants t
                        LEFT JOIN pool_tenant_admission a ON a.pool_id = :pool AND a.tenant = t.tenant
                        """)
                .bind("pool", poolId).bind("principal", gate.principal())
                .bind("serviceRoot", serviceCredentialRootPrincipal(gate.principal()))
                .mapTo(String.class).list();
        check(states.size() == 1 && states.getFirst().equals("ADMITTED"), ErrorCode.TENANT_NOT_ADMITTED, "The request's tenant is not admitted");
    }

    @Nullable
    private static String serviceCredentialRootPrincipal(String principal)
    {
        if (principal.length() <= 92 && SERVICE_PRINCIPAL.matcher(principal).matches()) {
            return principal.substring(0, principal.indexOf('.'));
        }
        return null;
    }

    static boolean isPooledMode(Handle handle, String routingGroup)
    {
        return handle.createQuery("SELECT api_mode = 'POOLED' FROM pool WHERE pool_id = :group")
                .bind("group", routingGroup).mapTo(Boolean.class).findOne().orElse(false);
    }

    @Nullable
    private static String memberPool(Handle handle, BackendRef backend)
    {
        return handle.createQuery("SELECT pool_id FROM transaction_backend WHERE incarnation = :id")
                .bind("id", backend.incarnation()).mapTo(String.class).findOne().orElse(null);
    }

    private static boolean isComplete(Handle handle, UUID admissionId)
    {
        return handle.createQuery("SELECT state = 'COMPLETE' FROM transaction_admission WHERE admission_id = :id").bind("id", admissionId).mapTo(Boolean.class).one();
    }

    private static String recordedObservation(Handle handle, UUID admissionId)
    {
        return handle.createQuery("SELECT observation FROM transaction_admission WHERE admission_id = :id").bind("id", admissionId).mapTo(String.class).one();
    }

    private static String fingerprint(ResponseObservation observation)
    {
        String fingerprint = encode(observation.queryId()) + encode(observation.startedTxId()) + observation.clear() + ":" + observation.terminal() + ":" + observation.retryWindowSeconds();
        return observation.capabilityHashes().isEmpty() ? fingerprint : fingerprint + ":capabilities:" + String.join(",", observation.capabilityHashes());
    }

    private static String encode(@Nullable String value)
    {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static void checkOwner(String expected, String actual)
    {
        check(expected.equals(actual), ErrorCode.OWNER_MISMATCH, "Owner binding does not match");
    }

    private static StoreException missing(String kind)
    {
        return new StoreException(ErrorCode.NOT_FOUND, "Unknown " + kind + " binding");
    }

    private static void check(boolean condition, ErrorCode code, String message)
    {
        if (!condition) {
            throw new StoreException(code, message);
        }
    }
}
