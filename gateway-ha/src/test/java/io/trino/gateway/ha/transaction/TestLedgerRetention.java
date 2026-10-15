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

import io.trino.gateway.ha.transaction.TransactionStore.Admission;
import io.trino.gateway.ha.transaction.TransactionStore.DrainStatus;
import io.trino.gateway.ha.transaction.TransactionStore.ResponseObservation;
import io.trino.gateway.ha.transaction.TransactionStore.RetentionBatch;
import io.trino.gateway.ha.transaction.TransactionStore.RetentionCursor;
import io.trino.gateway.ha.transaction.TransactionStore.StoreException;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.NOT_FOUND;
import static io.trino.gateway.ha.transaction.TransactionStore.RetentionCursor.START;
import static io.trino.gateway.ha.util.TestcontainersUtils.createPostgreSqlContainer;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

/**
 * Ledger retention against the real migrations, including the concurrently built indexes. Two
 * store instances on separate connection pools stand in for two Gateway replicas.
 */
@TestInstance(PER_CLASS)
@Execution(SAME_THREAD)
@Isolated
class TestLedgerRetention
{
    private static final int DAYS = 7;
    private static final String CAPABILITY = "c".repeat(64);

    private final String schema = "ledger_retention_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcDatabaseContainer<?> container;
    private Jdbi admin;
    private Jdbi database;
    private PooledTransactionStoreFixture fixture;
    private TransactionStore replicaOne;
    private TransactionStore replicaTwo;
    private boolean schemaCreated;

    @BeforeAll
    void setupDatabase()
            throws IOException
    {
        container = createPostgreSqlContainer();
        container.start();
        String url = container.getJdbcUrl();
        admin = Jdbi.create(url, container.getUsername(), container.getPassword());
        admin.useHandle(handle -> handle.execute("CREATE SCHEMA " + schema));
        schemaCreated = true;
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        database = Jdbi.create(schemaUrl, container.getUsername(), container.getPassword());
        for (String version : new String[] {"V7__transaction_awareness.sql", "V8__backend_incarnation_history.sql", "V9__query_capabilities.sql", "V10__drain_obligation_indexes.sql", "V11__cell_rollout_operations.sql", "V12__pool_member_lifecycle.sql", "V13__pool_tenant_principals.sql"}) {
            String sql = resource(version);
            database.useHandle(handle -> handle.createScript(sql).execute());
        }
        // Concurrent index builds cannot run inside a transaction block, so run them one statement at a time.
        for (String version : new String[] {"V14__drain_reconciliation_index.sql", "V15__ledger_retention_indexes.sql"}) {
            for (String statement : resource(version).replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    database.useHandle(handle -> handle.execute(statement));
                }
            }
        }
        fixture = new PooledTransactionStoreFixture(database);
        replicaOne = new TransactionStore(Jdbi.create(schemaUrl, container.getUsername(), container.getPassword()));
        replicaTwo = new TransactionStore(Jdbi.create(schemaUrl, container.getUsername(), container.getPassword()));
    }

    @AfterAll
    void cleanupDatabase()
    {
        try {
            if (schemaCreated) {
                admin.useHandle(handle -> handle.execute("DROP SCHEMA " + schema + " CASCADE"));
            }
        }
        finally {
            container.close();
        }
    }

    @BeforeEach
    void resetLedger()
    {
        database.useHandle(handle -> handle.execute("TRUNCATE pool_tenant_principal, pool_publication_receipt, pool_publication, pool_tenant_admission, pool_failure_receipt, pool_member_certificate, pool_operation, pool, transaction_rollout, transaction_route, transaction_admission, transaction_query_capability, transaction_query, transaction_binding, transaction_backend"));
        fixture.ensureBackend("blue", "http://blue.example.test", "http://blue.example.test", "group", "blue-node", "blue-process");
    }

    @Test
    void settledHistoryIsDeleted()
    {
        settledQuery("finished");
        Admission start = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(start.id(), new ResponseObservation("opens", "tx", false, true, 0));
        Admission commit = fixture.admitTransaction("tx", "owner");
        fixture.recordResponse(commit.id(), new ResponseObservation("commits", null, true, true, 0));
        age();
        DrainStatus before = fixture.drainStatus("blue");

        RetentionBatch batch = replicaOne.purgeSettledQueries(DAYS, 100, START);

        assertThat(batch.queries()).isEqualTo(3);
        assertThat(batch.admissions()).isEqualTo(3);
        assertThat(batch.capabilities()).isEqualTo(1);
        assertThat(batch.bindings()).isEqualTo(1);
        assertThat(count("transaction_query") + count("transaction_admission") + count("transaction_query_capability") + count("transaction_binding")).isZero();
        assertThat(fixture.drainStatus("blue")).isEqualTo(before);
        // A deleted query is unknown, so a continuation is refused rather than routed anywhere.
        expectNotFound(() -> fixture.admitQuery("finished", Optional.of("owner"), Optional.empty()));
        expectNotFound(() -> fixture.admitTransaction("tx", "owner"));
    }

    @Test
    void blockersRetainedWindowAndOpenTransactionsAreNeverDeleted()
    {
        // A pending continuation of a terminal query.
        settledQuery("pending");
        fixture.admitQuery("pending", Optional.of("owner"), Optional.empty());
        // An uncertain continuation with a received but unrecorded response.
        settledQuery("uncertain");
        Admission uncertain = fixture.admitQuery("uncertain", Optional.of("owner"), Optional.empty());
        fixture.markUncertain(uncertain.id(), false);
        // A running query.
        Admission running = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(running.id(), new ResponseObservation("running", null, false, false, 0));
        // A terminal statement inside a transaction that is still open.
        Admission open = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(open.id(), new ResponseObservation("in-open-tx", "open-tx", false, true, 0));
        // A new statement with no response yet.
        fixture.admitNew("blue", "owner", "group");
        age();
        // Expired inside the safety margin, and still inside the retry window.
        settledQuery("margin");
        settledQuery("window");
        database.useHandle(handle -> {
            handle.execute("UPDATE transaction_query SET retain_until = now() - make_interval(days => ?) + interval '1 hour' WHERE query_id = 'margin'", DAYS);
            handle.execute("UPDATE transaction_query SET retain_until = now() + interval '1 hour' WHERE query_id = 'window'");
        });
        long rows = ledgerRows();
        DrainStatus before = fixture.drainStatus("blue");
        assertThat(before.pendingRequests()).isEqualTo(3);
        assertThat(before.openTransactions()).isEqualTo(1);

        RetentionBatch queries = replicaOne.purgeSettledQueries(DAYS, 100, START);
        RetentionBatch rejected = replicaOne.purgeRejectedAdmissions(DAYS, 100, START);

        assertThat(queries.rows() + rejected.rows()).isZero();
        assertThat(ledgerRows()).isEqualTo(rows);
        assertThat(fixture.drainStatus("blue")).isEqualTo(before);
        // A late continuation inside the retained window still reaches its query.
        Admission late = fixture.admitQuery("window", Optional.of("owner"), Optional.empty(), Optional.of(CAPABILITY));
        assertThat(late.queryId()).isEqualTo("window");
    }

    @Test
    void unsettledSupersededCandidateKeepsItsProof()
    {
        Admission submit = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(submit.id(), new ResponseObservation("polled", null, false, false, 0));
        // A poll dropped while the query was still running stays uncertain.
        Admission dropped = fixture.admitQuery("polled", Optional.of("owner"), Optional.empty());
        assertThat(fixture.markUncertain(dropped.id(), true)).isFalse();
        Admission delivering = fixture.admitQuery("polled", Optional.of("owner"), Optional.empty());
        fixture.recordResponse(delivering.id(), new ResponseObservation("polled", null, false, true, 0));
        age();

        assertThat(replicaOne.purgeSettledQueries(DAYS, 100, START).rows()).isZero();
        assertThat(fixture.settleSupersededAdmissions(10)).extracting(Admission::id).containsExactly(dropped.id());
        assertThat(replicaOne.purgeSettledQueries(DAYS, 100, START).queries()).isEqualTo(1);
    }

    @Test
    void queryWithALockedAdmissionIsLeftAlone()
    {
        settledQuery("busy");
        Admission poll = fixture.admitQuery("busy", Optional.of("owner"), Optional.empty());
        fixture.recordResponse(poll.id(), new ResponseObservation("busy", null, false, true, 0));
        age();
        try (var session = database.open()) {
            session.begin();
            session.createQuery("SELECT admission_id FROM transaction_admission WHERE admission_id = ? FOR UPDATE").bind(0, poll.id()).mapTo(UUID.class).one();
            long started = System.nanoTime();
            assertThat(replicaOne.purgeSettledQueries(DAYS, 100, START).rows()).isZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3000);
            session.rollback();
        }
        assertThat(replicaOne.purgeSettledQueries(DAYS, 100, START).admissions()).isEqualTo(2);
    }

    @Test
    void rejectedAdmissionsAreDeletedOnlyAfterRetention()
    {
        Admission old = fixture.admitNew("blue", "owner", "group");
        fixture.rejectAdmission(old.id());
        age();
        Admission recent = fixture.admitNew("blue", "owner", "group");
        fixture.rejectAdmission(recent.id());
        Admission pending = fixture.admitNew("blue", "owner", "group");
        database.useHandle(handle -> handle.execute("UPDATE transaction_admission SET created_at = now() - interval '30 days' WHERE admission_id = ?", pending.id()));

        RetentionBatch batch = replicaOne.purgeRejectedAdmissions(DAYS, 100, START);

        assertThat(batch.admissions()).isEqualTo(1);
        List<UUID> remaining = database.withHandle(handle -> handle.createQuery("SELECT admission_id FROM transaction_admission").mapTo(UUID.class).list());
        assertThat(remaining)
                .containsExactlyInAnyOrder(recent.id(), pending.id());
        assertThat(fixture.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void batchesAreBounded()
    {
        for (int i = 0; i < 25; i++) {
            settledQuery("query_" + i);
        }
        age();

        RetentionBatch first = replicaOne.purgeSettledQueries(DAYS, 10, START);
        assertThat(first.scanned()).isEqualTo(10);
        assertThat(first.queries()).isEqualTo(10);
        assertThat(count("transaction_query")).isEqualTo(15);
        RetentionBatch second = replicaOne.purgeSettledQueries(DAYS, 10, first.cursor());
        assertThat(second.queries()).isEqualTo(10);
        RetentionBatch last = replicaOne.purgeSettledQueries(DAYS, 10, second.cursor());
        assertThat(last.scanned()).isEqualTo(5);
        assertThat(replicaOne.purgeSettledQueries(DAYS, 10, last.cursor()).scanned()).isZero();
    }

    @Test
    void concurrentReplicasShareTheBacklogSafely()
            throws Exception
    {
        int total = 300;
        for (int i = 0; i < total; i++) {
            settledQuery("shared_" + i);
        }
        age();
        DrainStatus before = fixture.drainStatus("blue");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> runs = new ArrayList<>();
            for (TransactionStore replica : List.of(replicaOne, replicaTwo)) {
                runs.add(executor.submit(() -> {
                    start.await();
                    long deleted = 0;
                    // Repeated runs, each scanning from the start as production does, until nothing is left.
                    for (int run = 0; run < 50 && count("transaction_query") > 0; run++) {
                        deleted += runOnce(replica, 20);
                    }
                    return deleted;
                }));
            }
            start.countDown();
            long deleted = 0;
            for (Future<Long> run : runs) {
                deleted += run.get(60, TimeUnit.SECONDS);
            }
            assertThat(deleted).isEqualTo(total);
        }
        finally {
            executor.shutdownNow();
        }
        assertThat(count("transaction_query") + count("transaction_admission") + count("transaction_query_capability")).isZero();
        assertThat(fixture.drainStatus("blue")).isEqualTo(before);
    }

    @Test
    void continuationHoldingTheQueryLockWinsOverRetention()
    {
        settledQuery("held");
        settledQuery("free");
        age();
        // A continuation that has taken the query identity lock but not yet inserted its admission.
        try (var session = database.open()) {
            session.begin();
            session.createQuery("SELECT pg_advisory_xact_lock(?, ?)::text").bind(0, TransactionStore.QUERY_LOCK_NAMESPACE).bind(1, "held".hashCode()).mapTo(String.class).one();
            long started = System.nanoTime();
            RetentionBatch batch = replicaOne.purgeSettledQueries(DAYS, 100, START);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3000);
            assertThat(batch.queries()).isEqualTo(1);
            assertThat(fixture.getQuery("held")).isPresent();
            assertThat(fixture.getQuery("free")).isEmpty();
            session.execute("INSERT INTO transaction_admission (admission_id, incarnation, owner_hash, query_id, state) " +
                    "SELECT gen_random_uuid(), incarnation, owner_hash, query_id, 'PENDING' FROM transaction_query WHERE query_id = 'held'");
            session.commit();
        }
        // The continuation committed its obligation, so the query is no longer settled.
        assertThat(replicaOne.purgeSettledQueries(DAYS, 100, START).rows()).isZero();
        assertThat(fixture.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void lateContinuationRacingRetentionNeverLosesItsObligation()
            throws Exception
    {
        int total = 100;
        for (int i = 0; i < total; i++) {
            settledQuery("raced_" + i);
        }
        age();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Admission> admitted = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> purge = executor.submit(() -> {
                start.await();
                for (int run = 0; run < 100 && runOnce(replicaOne, 5) > 0; run++) {
                    // Keep running until a run deletes nothing.
                }
                return null;
            });
            Future<List<Admission>> continuations = executor.submit(() -> {
                start.await();
                List<Admission> accepted = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    try {
                        accepted.add(replicaTwo.admitQuery("raced_" + i, Optional.of("owner"), Optional.empty()));
                    }
                    catch (StoreException e) {
                        assertThat(e.code()).isEqualTo(NOT_FOUND);
                    }
                }
                return accepted;
            });
            start.countDown();
            purge.get(60, TimeUnit.SECONDS);
            admitted.addAll(continuations.get(60, TimeUnit.SECONDS));
        }
        finally {
            executor.shutdownNow();
        }
        // Every accepted continuation keeps its pending admission and its query, so it still blocks drain.
        assertThat(fixture.drainStatus("blue").pendingRequests()).isEqualTo(admitted.size());
        for (Admission admission : admitted) {
            assertThat(fixture.getQuery(admission.queryId())).isPresent();
        }
        runOnce(replicaOne, 500);
        assertThat(count("transaction_query")).isEqualTo(admitted.size());
    }

    @Test
    void permanentlyPinnedHistoryDoesNotStarveLaterRows()
    {
        for (int i = 0; i < 30; i++) {
            settledQuery("pinned_" + i);
            Admission uncertain = fixture.admitQuery("pinned_" + i, Optional.of("owner"), Optional.empty());
            fixture.markUncertain(uncertain.id(), false);
        }
        age();
        age();
        for (int i = 0; i < 5; i++) {
            settledQuery("later_" + i);
        }
        age();

        assertThat(runOnce(replicaOne, 10)).isEqualTo(5);
        assertThat(count("transaction_query")).isEqualTo(30);
        assertThat(fixture.drainStatus("blue").pendingRequests()).isEqualTo(30);
    }

    @Test
    void rejectionsReleaseOnlyUnreferencedClosedBindings()
    {
        // A closed transaction whose statements are still retained, plus an old rejected statement in it.
        Admission start = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(start.id(), new ResponseObservation("opens", "closed-tx", false, true, 0));
        Admission rejectedInClosed = fixture.admitTransaction("closed-tx", "owner");
        fixture.rejectAdmission(rejectedInClosed.id());
        Admission commit = fixture.admitTransaction("closed-tx", "owner");
        fixture.recordResponse(commit.id(), new ResponseObservation("commits", null, true, true, 3600));
        // An open transaction with an old rejected statement.
        Admission open = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(open.id(), new ResponseObservation("open-start", "open-tx", false, true, 3600));
        Admission rejectedInOpen = fixture.admitTransaction("open-tx", "owner");
        fixture.rejectAdmission(rejectedInOpen.id());
        database.useHandle(handle -> handle.execute("UPDATE transaction_admission SET created_at = created_at - interval '30 days'"));

        assertThat(replicaOne.purgeRejectedAdmissions(DAYS, 100, START).admissions()).isEqualTo(2);
        // Both bindings are still referenced by retained queries; the open one is a drain blocker anyway.
        assertThat(count("transaction_binding")).isEqualTo(2);
        assertThat(fixture.drainStatus("blue").openTransactions()).isEqualTo(1);

        // Once the closed transaction's queries expire, its binding goes with the last of them.
        database.useHandle(handle -> handle.execute("UPDATE transaction_query SET retain_until = now() - interval '30 days' WHERE transaction_id = 'closed-tx'"));
        assertThat(replicaOne.purgeSettledQueries(DAYS, 100, START).bindings()).isEqualTo(1);
        assertThat(fixture.getTransaction("closed-tx")).isEmpty();
        assertThat(fixture.getTransaction("open-tx")).isPresent();
    }

    private static long runOnce(TransactionStore store, int limit)
    {
        long deleted = 0;
        RetentionCursor cursor = START;
        RetentionBatch batch;
        do {
            batch = store.purgeSettledQueries(DAYS, limit, cursor);
            deleted += batch.queries();
            cursor = batch.cursor();
        }
        while (batch.scanned() == limit);
        return deleted;
    }

    private void settledQuery(String queryId)
    {
        Admission submit = fixture.admitNew("blue", "owner", "group");
        fixture.recordResponse(submit.id(), new ResponseObservation(queryId, null, false, true, 0, List.of(CAPABILITY)));
    }

    // Moves every existing row past the retention horizon without touching the database clock.
    private void age()
    {
        database.useHandle(handle -> {
            handle.execute("UPDATE transaction_query SET retain_until = retain_until - make_interval(days => ?) - interval '1 hour' WHERE terminal", DAYS);
            handle.execute("UPDATE transaction_admission SET created_at = created_at - make_interval(days => ?) - interval '1 hour'", DAYS);
        });
    }

    private long ledgerRows()
    {
        return count("transaction_query") + count("transaction_admission") + count("transaction_query_capability") + count("transaction_binding");
    }

    private long count(String table)
    {
        return database.withHandle(handle -> handle.createQuery("SELECT count(*) FROM " + table).mapTo(Long.class).one());
    }

    private String resource(String version)
            throws IOException
    {
        try (var migration = requireNonNull(getClass().getResourceAsStream("/postgresql/" + version))) {
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void expectNotFound(Runnable operation)
    {
        assertThatThrownBy(operation::run).isInstanceOf(StoreException.class)
                .satisfies(error -> assertThat(((StoreException) error).code()).isEqualTo(NOT_FOUND));
    }
}
