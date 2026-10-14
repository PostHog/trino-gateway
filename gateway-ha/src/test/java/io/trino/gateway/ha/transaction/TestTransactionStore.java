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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.gateway.ha.persistence.GatewayDatabase;
import io.trino.gateway.ha.transaction.TransactionStore.Admission;
import io.trino.gateway.ha.transaction.TransactionStore.ErrorCode;
import io.trino.gateway.ha.transaction.TransactionStore.ResponseObservation;
import io.trino.gateway.ha.transaction.TransactionStore.StoreException;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.CONFLICT;
import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.NOT_ACTIVE;
import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.NOT_DRAINED;
import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.NOT_FOUND;
import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.OWNER_MISMATCH;
import static io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.SEALED;
import static io.trino.gateway.ha.util.TestcontainersUtils.createPostgreSqlContainer;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

@TestInstance(PER_CLASS)
@Execution(SAME_THREAD)
@Isolated
class TestTransactionStore
{
    private final String schema = "transaction_store_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcDatabaseContainer<?> container;
    private Jdbi admin;
    private Jdbi database;
    private PooledTransactionStoreFixture first;
    private PooledTransactionStoreFixture second;
    private boolean schemaCreated;
    private String fixtureUrl;
    private String fixtureUsername;
    private String fixturePassword;

    @BeforeAll
    void setupDatabase()
            throws IOException
    {
        String url = System.getenv("TX_STORE_TEST_JDBC_URL");
        String username;
        String password;
        if (url == null) {
            container = createPostgreSqlContainer();
            container.start();
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
        else {
            username = requireNonNull(System.getenv("TX_STORE_TEST_USERNAME"), "TX_STORE_TEST_USERNAME is required");
            password = System.getenv().getOrDefault("TX_STORE_TEST_PASSWORD", "");
        }
        admin = Jdbi.create(url, username, password);
        admin.useHandle(handle -> handle.execute("CREATE SCHEMA " + schema));
        schemaCreated = true;
        fixtureUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        fixtureUsername = username;
        fixturePassword = password;
        database = Jdbi.create(fixtureUrl, username, password);
        for (String version : new String[] {"V7__transaction_awareness.sql", "V8__backend_incarnation_history.sql", "V9__query_capabilities.sql", "V10__drain_obligation_indexes.sql", "V11__cell_rollout_operations.sql", "V12__pool_member_lifecycle.sql", "V13__pool_tenant_principals.sql"}) {
            try (var migration = requireNonNull(getClass().getResourceAsStream("/postgresql/" + version))) {
                String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
                database.useHandle(handle -> handle.createScript(sql).execute());
            }
        }
        first = new PooledTransactionStoreFixture(database);
        second = new PooledTransactionStoreFixture(database);
        database.useHandle(handle -> handle.execute("CREATE TABLE retry_fixture (id integer PRIMARY KEY)"));
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
            if (container != null) {
                container.close();
            }
        }
    }

    @BeforeEach
    void resetLedger()
    {
        database.useHandle(handle -> handle.execute("TRUNCATE pool_tenant_principal, pool_publication_receipt, pool_publication, pool_tenant_admission, pool_failure_receipt, pool_member_certificate, pool_operation, pool, transaction_rollout, transaction_route, transaction_admission, transaction_query_capability, transaction_query, transaction_binding, transaction_backend"));
        first.ensureBackend("blue", "http://blue.example.test", "http://blue.example.test", "group", "blue-node", "blue-process");
        first.ensureBackend("green", "http://green.example.test", "http://green.example.test", "group", "green-node", "green-process");
        database.useHandle(handle -> handle.execute("TRUNCATE retry_fixture"));
    }

    @Test
    void committedResponseWithLostAcknowledgementRetriesWithoutChangingItsObservation()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        AtomicInteger commits = new AtomicInteger();
        PooledTransactionStoreFixture flaky = new PooledTransactionStoreFixture(commitFailureDatabase(commits, true));
        ResponseObservation observation = new ResponseObservation("query", null, false, true, 120);
        flaky.recordResponse(admission.id(), observation);
        assertThat(commits).hasValue(2);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        int queries = database.withHandle(handle -> handle.createQuery("SELECT count(*) FROM transaction_query").mapTo(Integer.class).one());
        assertThat(queries).isEqualTo(1);
        first.recordResponse(admission.id(), observation);
        assertThatThrownBy(() -> first.recordResponse(admission.id(), new ResponseObservation("query", null, false, false, 120)))
                .isInstanceOf(StoreException.class);
    }

    @Test
    void uncertaintyWriteWithLostAcknowledgementIsIdempotentAndStillBlocksDrain()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        AtomicInteger commits = new AtomicInteger();
        new PooledTransactionStoreFixture(commitFailureDatabase(commits, true)).markUncertain(admission.id());
        assertThat(commits).hasValue(2);
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
        String state = database.withHandle(handle -> handle.createQuery("SELECT state FROM transaction_admission WHERE admission_id = :id").bind("id", admission.id()).mapTo(String.class).one());
        assertThat(state).isEqualTo("UNCERTAIN");
    }

    @Test
    void rejectedAdmissionWithLostAcknowledgementIsSettledOnce()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        AtomicInteger commits = new AtomicInteger();
        new PooledTransactionStoreFixture(commitFailureDatabase(commits, true)).rejectAdmission(admission.id());
        assertThat(commits).hasValue(2);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        first.rejectAdmission(admission.id());
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void responseConnectionFailureBeforeCommitRetriesTheSameAdmission()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        AtomicInteger commits = new AtomicInteger();
        new PooledTransactionStoreFixture(commitFailureDatabase(commits, false, "08006"))
                .recordResponse(admission.id(), new ResponseObservation("query", null, false, true, 120));
        assertThat(commits).hasValue(2);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    public interface RetryDao
    {
        @SqlUpdate("INSERT INTO retry_fixture VALUES (:id)")
        void insert(int id);

        @SqlQuery("SELECT count(*) FROM retry_fixture")
        int count();

        default int countViaDefault()
        {
            return count();
        }
    }

    @Test
    void daoAutocommitWriteWithLostAcknowledgementIsNotReplayed()
    {
        AtomicInteger executed = new AtomicInteger();
        RetryDao dao = GatewayDatabase.dao(statementFailureDatabase(executed), RetryDao.class, GatewayDatabase.Operation.HISTORY_DAO);
        assertThatThrownBy(() -> dao.insert(1)).isInstanceOf(RuntimeException.class);
        assertThat(executed).hasValue(1);
        int count = database.withHandle(handle -> handle.createQuery("SELECT count(*) FROM retry_fixture").mapTo(Integer.class).one());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void daoDefaultMethodUsesReadRetryAndFreshConnection()
    {
        AtomicInteger executed = new AtomicInteger();
        RetryDao dao = GatewayDatabase.dao(statementFailureDatabase(executed), RetryDao.class, GatewayDatabase.Operation.HISTORY_DAO);
        assertThat(dao.countViaDefault()).isZero();
        assertThat(executed).hasValue(2);
        assertThat(dao.equals(dao)).isTrue();
    }

    @Test
    void daoWriteRetriesConnectionAcquisitionBeforeDispatch()
    {
        AtomicInteger opened = new AtomicInteger();
        Jdbi flaky = Jdbi.create(() -> {
            if (opened.incrementAndGet() == 1) {
                throw new SQLTransientConnectionException("synthetic acquisition failure", "08001");
            }
            return DriverManager.getConnection(fixtureUrl, fixtureUsername, fixturePassword);
        }).installPlugin(new SqlObjectPlugin());
        RetryDao dao = GatewayDatabase.dao(flaky, RetryDao.class, GatewayDatabase.Operation.HISTORY_DAO);
        dao.insert(1);
        assertThat(opened).hasValue(2);
        assertThat(dao.count()).isEqualTo(1);
    }

    @Test
    void closeFailureAfterSuccessfulCommitDoesNotRepeatTheWrite()
    {
        AtomicInteger opened = new AtomicInteger();
        Jdbi flaky = Jdbi.create(() -> {
            opened.incrementAndGet();
            Connection delegate = DriverManager.getConnection(fixtureUrl, fixtureUsername, fixturePassword);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (_, method, args) -> {
                try {
                    Object result = method.invoke(delegate, args);
                    if (method.getName().equals("close")) {
                        throw new SQLException("synthetic pool reset failure after commit", "40001");
                    }
                    return result;
                }
                catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        });
        assertThatThrownBy(() -> new PooledTransactionStoreFixture(flaky).admitNew("blue", "owner", "group")).isInstanceOf(RuntimeException.class);
        assertThat(opened).hasValue(1);
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    private Jdbi statementFailureDatabase(AtomicInteger executed)
    {
        return Jdbi.create(() -> {
            Connection delegate = DriverManager.getConnection(fixtureUrl, fixtureUsername, fixturePassword);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (_, method, args) -> {
                try {
                    Object result = method.invoke(delegate, args);
                    if (method.getName().equals("prepareStatement")) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (_, statementMethod, statementArgs) -> {
                            try {
                                Object statementResult = statementMethod.invoke(result, statementArgs);
                                if (statementMethod.getName().equals("execute") && executed.incrementAndGet() == 1) {
                                    throw new SQLException("synthetic statement acknowledgement loss", "08006");
                                }
                                return statementResult;
                            }
                            catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
                    }
                    return result;
                }
                catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }).installPlugin(new SqlObjectPlugin());
    }

    @Test
    void ambiguousAdmissionCommitIsNotRetriedOrDuplicated()
    {
        AtomicInteger commits = new AtomicInteger();
        PooledTransactionStoreFixture flaky = new PooledTransactionStoreFixture(commitFailureDatabase(commits, true));
        assertThatThrownBy(() -> flaky.admitNew("blue", "owner", "group")).isInstanceOf(RuntimeException.class);
        assertThat(commits).hasValue(1);
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void knownTransactionAbortRetriesFromTheBeginning()
    {
        AtomicInteger commits = new AtomicInteger();
        PooledTransactionStoreFixture flaky = new PooledTransactionStoreFixture(commitFailureDatabase(commits, false));
        flaky.admitNew("blue", "owner", "group");
        assertThat(commits).hasValue(2);
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    private Jdbi commitFailureDatabase(AtomicInteger commits, boolean committed)
    {
        return commitFailureDatabase(commits, committed, committed ? "08006" : "40001");
    }

    private Jdbi commitFailureDatabase(AtomicInteger commits, boolean committed, String state)
    {
        return Jdbi.create(() -> {
            Connection delegate = DriverManager.getConnection(fixtureUrl, fixtureUsername, fixturePassword);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (_, method, args) -> {
                if (method.getName().equals("commit") && commits.incrementAndGet() == 1) {
                    if (committed) {
                        delegate.commit();
                        throw new SQLException("synthetic acknowledgement loss", state);
                    }
                    delegate.rollback();
                    throw new SQLException("synthetic serialization abort", state);
                }
                try {
                    return method.invoke(delegate, args);
                }
                catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        });
    }

    @Test
    void unknownTransactionFailsWithoutAdmission()
    {
        expect(NOT_FOUND, () -> second.admitTransaction("unknown", "owner"));
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void transactionAndQueryPinAcrossStoreInstancesDuringDrain()
    {
        Admission start = startTransaction("transaction", "start");
        first.beginDrain("blue");
        Admission statement = second.admitTransaction("transaction", "owner");
        assertThat(statement.backend()).isEqualTo(start.backend());
        assertThat(first.getQuery("start").orElseThrow().transactionId()).isEqualTo("transaction");
        assertThat(second.drainStatus("blue").openTransactions()).isEqualTo(1);
        expect(NOT_ACTIVE, () -> first.admitNew("blue", "owner", "group"));
        expect(OWNER_MISMATCH, () -> second.admitTransaction("transaction", "other-owner"));
    }

    @Test
    void queryCapabilityRetainsOwnerAndRejectsContradictions()
    {
        startTransaction("transaction", "start");
        Admission continuation = second.admitQuery("start", Optional.empty(), Optional.empty());
        assertThat(continuation.ownerHash()).isEqualTo("owner");
        expect(OWNER_MISMATCH, () -> first.admitQuery("start", Optional.of("other"), Optional.empty()));
        expect(CONFLICT, () -> first.admitQuery("start", Optional.empty(), Optional.of("another-transaction")));
    }

    @Test
    void closedTransactionStillAllowsItsOutstandingResults()
    {
        startTransaction("transaction", "start");
        Admission commit = second.admitTransaction("transaction", "owner");
        second.recordResponse(commit.id(), new ResponseObservation("commit", null, true, false, 0));
        long generation = first.beginDrain("blue").generation();
        assertThat(first.getTransaction("transaction").orElseThrow().state()).isEqualTo("CLOSED");
        assertThat(first.drainStatus("blue").activeQueries()).isEqualTo(1);
        expect(CONFLICT, () -> first.admitTransaction("transaction", "owner"));
        expect(NOT_DRAINED, () -> first.seal("blue", generation));
        Admission result = first.admitQuery("commit", Optional.empty(), Optional.of("transaction"));
        first.recordResponse(result.id(), new ResponseObservation("commit", null, true, true, 0));
        assertThat(first.seal("blue", generation).drained()).isTrue();
    }

    @Test
    void startReplayCannotReopenTombstone()
    {
        Admission start = startTransaction("transaction", "start");
        Admission commit = second.admitTransaction("transaction", "owner");
        second.recordResponse(commit.id(), new ResponseObservation("commit", null, true, true, 0));
        first.recordResponse(start.id(), new ResponseObservation("start", "transaction", false, true, 0));
        Admission replay = first.admitQuery("start", Optional.empty(), Optional.empty());
        first.recordResponse(replay.id(), new ResponseObservation("start", "transaction", false, true, 0));
        assertThat(first.getTransaction("transaction").orElseThrow().state()).isEqualTo("CLOSED");
        Admission different = first.admitNew("blue", "owner", "group");
        expect(CONFLICT, () -> first.recordResponse(different.id(), new ResponseObservation("different-start", "transaction", false, true, 0)));
        assertThat(first.getQuery("different-start")).isEmpty();
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void duplicateCallbacksAreIdempotentAndCannotChangeOutcome()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        ResponseObservation observation = new ResponseObservation("query", null, false, true, 120);
        first.recordResponse(admission.id(), observation);
        String firstRetention = retention("query");
        second.recordResponse(admission.id(), observation);
        second.markUncertain(admission.id());
        assertThat(retention("query")).isEqualTo(firstRetention);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        expect(CONFLICT, () -> first.recordResponse(admission.id(), new ResponseObservation("different", null, false, true, 120)));
        expect(CONFLICT, () -> first.rejectAdmission(admission.id()));
    }

    @Test
    void uncertainAdmissionRemainsBlockingUntilKnownOutcome()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        first.markUncertain(admission.id());
        long generation = second.beginDrain("blue").generation();
        expect(NOT_DRAINED, () -> first.seal("blue", generation));
        second.recordResponse(admission.id(), new ResponseObservation("late", null, false, true, 0));
        assertThat(first.seal("blue", generation).drained()).isTrue();
    }

    @Test
    void definiteRejectionIsIdempotent()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        first.rejectAdmission(admission.id());
        second.rejectAdmission(admission.id());
        second.markUncertain(admission.id());
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        expect(CONFLICT, () -> first.recordResponse(admission.id(), new ResponseObservation("late", null, false, true, 0)));
    }

    @Test
    void droppedConcurrentPollSettlesOnceSiblingDeliveredTerminalResult()
    {
        String capability = "c".repeat(64);
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, false, 0, List.of(capability)));
        Admission delivering = second.admitQuery("query", Optional.of("owner"), Optional.empty());
        Admission dropped = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        second.recordResponse(delivering.id(), new ResponseObservation("query", null, false, true, 0));
        long generation = second.beginDrain("blue").generation();
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);

        assertThat(first.markUncertain(dropped.id(), true)).isTrue();
        assertThat(admissionState(dropped)).isEqualTo("COMPLETE:" + TransactionStore.SUPERSEDED_OBSERVATION);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        assertThat(second.markUncertain(dropped.id(), true)).isFalse();
        assertThat(admissionState(dropped)).isEqualTo("COMPLETE:" + TransactionStore.SUPERSEDED_OBSERVATION);
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(first.seal("blue", generation).drained()).isTrue();
    }

    @Test
    void receivedButUnrecordedResponseIsNeverSuperseded()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, true, 0));
        Admission unrecorded = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        assertThat(first.markUncertain(unrecorded.id(), false)).isFalse();
        assertThat(admissionState(unrecorded)).isEqualTo("UNCERTAIN:" + TransactionStore.UNRECORDED_RESPONSE_OBSERVATION);
        assertThat(second.markUncertain(unrecorded.id(), true)).isFalse();
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(admissionState(unrecorded)).isEqualTo("UNCERTAIN:" + TransactionStore.UNRECORDED_RESPONSE_OBSERVATION);
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void uncertaintyMarkerDoesNotPreventALaterOutcome()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, false, 0));
        Admission continuation = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        assertThat(first.markUncertain(continuation.id(), true)).isFalse();
        first.recordResponse(continuation.id(), new ResponseObservation("query", null, false, true, 0));
        assertThat(admissionState(continuation)).startsWith("COMPLETE:").doesNotContain(TransactionStore.SUPERSEDED_OBSERVATION);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void terminalObservationWithCapabilitiesProvesDelivery()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, true, 0, List.of("d".repeat(64), "e".repeat(64))));
        Admission dropped = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        assertThat(first.markUncertain(dropped.id(), true)).isTrue();
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void uncertainContinuationOfRunningQueryStillBlocksDrain()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, false, 0));
        Admission dropped = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        assertThat(first.markUncertain(dropped.id(), true)).isFalse();
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(admissionState(dropped)).isEqualTo("UNCERTAIN:" + TransactionStore.NO_RESPONSE_OBSERVATION);
        long generation = second.beginDrain("blue").generation();
        expect(NOT_DRAINED, () -> first.seal("blue", generation));
    }

    @Test
    void terminalQueryWithoutCompletedTerminalSiblingStillBlocksDrain()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, false, 120, List.of("f".repeat(64), "0".repeat(64))));
        Admission dropped = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        // Terminal state without a sibling that recorded it does not prove this request's obligation discharged.
        database.useHandle(handle -> handle.execute("UPDATE transaction_query SET terminal = TRUE, retain_until = clock_timestamp() WHERE query_id = 'query'"));
        Admission rejected = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        first.rejectAdmission(rejected.id());
        assertThat(first.markUncertain(dropped.id(), true)).isFalse();
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(admissionState(dropped)).isEqualTo("UNCERTAIN:" + TransactionStore.NO_RESPONSE_OBSERVATION);
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void uncertainSubmissionIsNeverSuperseded()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, true, 0));
        Admission unknown = first.admitNew("blue", "owner", "group");
        assertThat(first.markUncertain(unknown.id(), true)).isFalse();
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void completedAdmissionKeepsItsOutcome()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        ResponseObservation terminal = new ResponseObservation("query", null, false, true, 0);
        first.recordResponse(submit.id(), terminal);
        Admission rejected = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        first.rejectAdmission(rejected.id());
        assertThat(first.markUncertain(rejected.id(), true)).isFalse();
        assertThat(first.markUncertain(submit.id(), true)).isFalse();
        assertThat(admissionState(rejected)).isEqualTo("COMPLETE:REJECTED");
        first.recordResponse(submit.id(), terminal);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void sweepSettlesProvenOrphansExactlyOnce()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, false, 0));
        Admission early = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        Admission delivering = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        // The dropped poll failed before its sibling recorded the terminal result.
        assertThat(first.markUncertain(early.id(), true)).isFalse();
        first.recordResponse(delivering.id(), new ResponseObservation("query", null, false, true, 0));

        Admission running = first.admitNew("green", "owner", "group");
        first.recordResponse(running.id(), new ResponseObservation("running", null, false, false, 0));
        Admission unproven = first.admitQuery("running", Optional.of("owner"), Optional.empty());
        first.markUncertain(unproven.id(), true);
        Admission pending = first.admitQuery("query", Optional.of("owner"), Optional.empty());

        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(2);
        assertThat(second.settleSupersededAdmissions(10)).extracting(Admission::id).containsExactly(early.id());
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(admissionState(early)).isEqualTo("COMPLETE:" + TransactionStore.SUPERSEDED_OBSERVATION);
        assertThat(admissionState(unproven)).isEqualTo("UNCERTAIN:" + TransactionStore.NO_RESPONSE_OBSERVATION);
        assertThat(admissionState(pending)).isEqualTo("PENDING:null");
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
        assertThat(first.drainStatus("green").pendingRequests()).isEqualTo(1);
    }

    @Test
    void sweepNeverSettlesUnmarkedUncertainty()
    {
        Admission submit = first.admitNew("blue", "owner", "group");
        first.recordResponse(submit.id(), new ResponseObservation("query", null, false, true, 0));
        Admission legacy = first.admitQuery("query", Optional.of("owner"), Optional.empty());
        database.useHandle(handle -> handle.createUpdate("UPDATE transaction_admission SET state = 'UNCERTAIN' WHERE admission_id = :id").bind("id", legacy.id()).execute());
        // Without a marker it is unknown whether a response was forwarded.
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(admissionState(legacy)).isEqualTo("UNCERTAIN:null");
        // A later failure report cannot supply the missing fact either.
        assertThat(first.markUncertain(legacy.id(), true)).isFalse();
        assertThat(first.settleSupersededAdmissions(10)).isEmpty();
        assertThat(admissionState(legacy)).isEqualTo("UNCERTAIN:null");
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    private String admissionState(Admission admission)
    {
        return database.withHandle(handle -> handle.createQuery("SELECT state || ':' || coalesce(observation, 'null') FROM transaction_admission WHERE admission_id = :id")
                .bind("id", admission.id()).mapTo(String.class).one());
    }

    @Test
    void rejectedContinuationDoesNotEraseQueryOrTransactionObligations()
    {
        Admission start = first.admitNew("blue", "owner", "group");
        first.recordResponse(start.id(), new ResponseObservation("start", "transaction", false, false, 0));
        Admission continuation = second.admitQuery("start", Optional.of("owner"), Optional.of("transaction"));
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
        first.rejectAdmission(continuation.id());
        var status = second.beginDrain("blue");
        assertThat(status.pendingRequests()).isZero();
        assertThat(status.openTransactions()).isEqualTo(1);
        assertThat(status.activeQueries()).isEqualTo(1);
        assertThat(status.readyToSeal()).isFalse();
        assertThat(first.getQuery("start").orElseThrow().terminal()).isFalse();
        assertThat(first.getTransaction("transaction").orElseThrow().state()).isEqualTo("OPEN");
        expect(NOT_DRAINED, () -> second.seal("blue", status.generation()));
    }

    @Test
    void settledCancellationPreservesConcurrentPollAndTerminalRetention()
    {
        Admission start = first.admitNew("blue", "owner", "group");
        first.recordResponse(start.id(), new ResponseObservation("start", "transaction", false, true, 120));
        String originalRetention = retention("start");
        Admission poll = first.admitQuery("start", Optional.of("owner"), Optional.of("transaction"));
        Admission cancellation = second.admitQuery("start", Optional.of("owner"), Optional.of("transaction"));
        second.rejectAdmission(cancellation.id());
        var status = first.beginDrain("blue");
        assertThat(status.pendingRequests()).isEqualTo(1);
        assertThat(status.openTransactions()).isEqualTo(1);
        assertThat(status.activeQueries()).isEqualTo(1);
        assertThat(first.getQuery("start").orElseThrow().terminal()).isTrue();
        assertThat(retention("start")).isEqualTo(originalRetention);
        expect(NOT_DRAINED, () -> second.seal("blue", status.generation()));
        first.rejectAdmission(poll.id());
        assertThat(second.drainStatus("blue").pendingRequests()).isZero();
        assertThat(second.drainStatus("blue").activeQueries()).isEqualTo(1);
        assertThat(second.getTransaction("transaction").orElseThrow().state()).isEqualTo("OPEN");
        assertThat(retention("start")).isEqualTo(originalRetention);
    }

    @Test
    void acknowledgedQueryCancellationPreservesTransactionAndConcurrentResponse()
    {
        Admission start = first.admitNew("blue", "owner", "group");
        first.recordResponse(start.id(), new ResponseObservation("query", "transaction", false, false, 120));
        Admission poll = first.admitQuery("query", Optional.of("owner"), Optional.of("transaction"));
        Admission cancel = second.admitQuery("query", Optional.of("owner"), Optional.of("transaction"));
        ResponseObservation cancelled = new ResponseObservation("query", null, false, true, 120);
        second.recordResponse(cancel.id(), cancelled);
        String retained = retention("query");
        var status = first.beginDrain("blue");
        assertThat(status.pendingRequests()).isEqualTo(1);
        assertThat(status.activeQueries()).isEqualTo(1);
        assertThat(status.openTransactions()).isEqualTo(1);
        expect(NOT_DRAINED, () -> second.seal("blue", status.generation()));
        first.recordResponse(poll.id(), new ResponseObservation("query", null, false, false, 120));
        assertThat(second.getQuery("query").orElseThrow().terminal()).isTrue();
        assertThat(retention("query")).isEqualTo(retained);
        first.recordResponse(cancel.id(), cancelled);
        assertThat(retention("query")).isEqualTo(retained);
        assertThat(second.getTransaction("transaction").orElseThrow().state()).isEqualTo("OPEN");
        database.useHandle(handle -> handle.execute("UPDATE transaction_query SET retain_until = clock_timestamp() - INTERVAL '1 second'"));
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        assertThat(first.drainStatus("blue").activeQueries()).isZero();
        expect(NOT_DRAINED, () -> second.seal("blue", status.generation()));
        Admission rollback = second.admitTransaction("transaction", "owner");
        second.recordResponse(rollback.id(), new ResponseObservation("rollback", null, true, true, 0));
        assertThat(first.seal("blue", status.generation()).drained()).isTrue();
    }

    @Test
    void terminalWindowAndLateContinuationFenceSealing()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        first.recordResponse(admission.id(), new ResponseObservation("query", null, false, true, 120));
        long generation = first.beginDrain("blue").generation();
        assertThat(first.drainStatus("blue").activeQueries()).isEqualTo(1);
        expect(NOT_DRAINED, () -> second.seal("blue", generation));
        database.useHandle(handle -> handle.execute("UPDATE transaction_query SET retain_until = clock_timestamp() - INTERVAL '1 second'"));
        assertThat(first.drainStatus("blue").readyToSeal()).isTrue();
        Admission continuation = second.admitQuery("query", Optional.empty(), Optional.empty());
        expect(NOT_DRAINED, () -> first.seal("blue", generation));
        second.recordResponse(continuation.id(), new ResponseObservation("query", null, false, true, 0));
        assertThat(first.seal("blue", generation).drained()).isTrue();
        expect(SEALED, () -> first.admitQuery("query", Optional.empty(), Optional.empty()));
    }

    @Test
    void queryCollisionDoesNotRebindOrReleaseAdmission()
    {
        Admission blue = first.admitNew("blue", "owner", "group");
        Admission green = second.admitNew("green", "owner", "group");
        first.recordResponse(blue.id(), new ResponseObservation("collision", null, false, false, 0));
        expect(CONFLICT, () -> second.recordResponse(green.id(), new ResponseObservation("collision", null, false, false, 0)));
        assertThat(first.getQuery("collision").orElseThrow().backend().name()).isEqualTo("blue");
        assertThat(first.drainStatus("green").pendingRequests()).isEqualTo(1);
    }

    @Test
    void malformedResponseDoesNotSettleAdmission()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        expect(CONFLICT, () -> first.recordResponse(admission.id(), new ResponseObservation(null, null, false, true, 0)));
        expect(CONFLICT, () -> first.recordResponse(admission.id(), new ResponseObservation("query", "transaction", true, true, 0)));
        expect(CONFLICT, () -> first.recordResponse(admission.id(), new ResponseObservation("query", null, true, true, 0)));
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
        assertThat(first.getQuery("query")).isEmpty();
    }

    @Test
    void admissionWaitsForBackendFenceAndObservesCommittedDrain()
            throws Exception
    {
        try (Handle fence = database.open(); var executor = Executors.newSingleThreadExecutor()) {
            fence.begin();
            fence.createQuery("SELECT name FROM transaction_backend WHERE name = 'blue' FOR UPDATE").mapTo(String.class).one();
            CountDownLatch started = new CountDownLatch(1);
            var admission = executor.submit(() -> {
                started.countDown();
                return second.admitNew("blue", "owner", "group");
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            fence.execute("UPDATE transaction_backend SET state = 'DRAINING', generation = generation + 1 WHERE name = 'blue'");
            fence.commit();
            assertThatThrownBy(() -> admission.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StoreException.class)
                    .satisfies(error -> assertThat(((StoreException) error.getCause()).code()).isEqualTo(NOT_ACTIVE));
            assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        }
    }

    @Test
    void independentAdmissionProgressesWhileAnotherQueryCompletionWaits()
            throws Exception
    {
        assertIndependentRequestProgress(false, "blue");
    }

    @Test
    void independentCompletionProgressesWhileAnotherQueryCompletionWaits()
            throws Exception
    {
        assertIndependentRequestProgress(true, "blue");
    }

    @Test
    void otherBackendCompletionProgressesWhileAnotherQueryCompletionWaits()
            throws Exception
    {
        assertIndependentRequestProgress(true, "green");
    }

    @Test
    void concurrentConflictingCallbacksKeepExactlyOneAdmissionOutcome()
            throws Exception
    {
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("one", null, false, false, 0));
        Admission admission = first.admitNew("blue", "owner", "group");
        try (Handle fence = database.open(); var executor = Executors.newFixedThreadPool(2)) {
            fence.begin();
            int fencePid = fence.createQuery("SELECT pg_backend_pid()").mapTo(Integer.class).one();
            fence.createQuery("SELECT query_id FROM transaction_query WHERE query_id = 'one' FOR UPDATE").mapTo(String.class).one();
            var completion = executor.submit(() -> first.recordResponse(admission.id(), new ResponseObservation("one", null, false, true, 0)));
            try {
                awaitDatabaseWaiter(fencePid);
                var conflicting = executor.submit(() -> second.recordResponse(admission.id(), new ResponseObservation("two", null, false, true, 0)));
                assertThatThrownBy(() -> conflicting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                fence.rollback();
                completion.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> conflicting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StoreException.class)
                        .satisfies(error -> assertThat(((StoreException) error.getCause()).code()).isEqualTo(CONFLICT));
            }
            finally {
                if (fence.isInTransaction()) {
                    fence.rollback();
                }
            }
        }
        assertThat(first.getQuery("one").orElseThrow().terminal()).isTrue();
        assertThat(first.getQuery("two")).isEmpty();
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void concurrentFirstQueryBindingsCannotCrossOwnersOrBackends()
            throws Exception
    {
        Admission blue = first.admitNew("blue", "first-owner", "group");
        Admission green = first.admitNew("green", "second-owner", "group");
        assertOneObservationWins(
                blue,
                new ResponseObservation("shared-query", null, false, true, 0),
                green,
                new ResponseObservation("shared-query", null, false, true, 0));
        var binding = first.getQuery("shared-query").orElseThrow();
        assertThat(binding.ownerHash()).isEqualTo(binding.backend().name().equals("blue") ? "first-owner" : "second-owner");
        assertThat(first.drainStatus("blue").pendingRequests() + first.drainStatus("green").pendingRequests()).isEqualTo(1);
    }

    @Test
    void concurrentTransactionIdentityCollisionRollsBackLosingQuery()
            throws Exception
    {
        Admission blue = first.admitNew("blue", "owner", "group");
        Admission green = first.admitNew("green", "owner", "group");
        assertOneObservationWins(
                blue,
                new ResponseObservation("blue-start", "shared-transaction", false, true, 0),
                green,
                new ResponseObservation("green-start", "shared-transaction", false, true, 0));
        var transaction = first.getTransaction("shared-transaction").orElseThrow();
        assertThat(first.getQuery(transaction.startQueryId()).orElseThrow().backend()).isEqualTo(transaction.backend());
        assertThat(first.getQuery("blue-start").isPresent() ^ first.getQuery("green-start").isPresent()).isTrue();
        assertThat(first.drainStatus("blue").pendingRequests() + first.drainStatus("green").pendingRequests()).isEqualTo(1);
    }

    @Test
    void advisoryHashCollisionCannotRebindDistinctQueries()
            throws Exception
    {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        Admission blue = first.admitNew("blue", "blue-owner", "group");
        Admission green = first.admitNew("green", "green-owner", "group");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> attemptObservation(start, first, blue, new ResponseObservation("Aa", null, false, true, 0)));
            var two = executor.submit(() -> attemptObservation(start, second, green, new ResponseObservation("BB", null, false, true, 0)));
            start.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(first.getQuery("Aa").orElseThrow().backend()).isEqualTo(blue.backend());
        assertThat(first.getQuery("BB").orElseThrow().backend()).isEqualTo(green.backend());
        assertThat(first.getQuery("Aa").orElseThrow().ownerHash()).isEqualTo("blue-owner");
        assertThat(first.getQuery("BB").orElseThrow().ownerHash()).isEqualTo("green-owner");
    }

    @Test
    void transactionHashFenceCannotBlockUnrelatedQueryNamespace()
            throws Exception
    {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        Admission admission = first.admitNew("blue", "owner", "group");
        try (Handle fence = database.open(); var executor = Executors.newSingleThreadExecutor()) {
            fence.begin();
            fence.createQuery("SELECT pg_advisory_xact_lock(:namespace, :identity)")
                    .bind("namespace", 0x54585452).bind("identity", "Aa".hashCode()).mapTo(String.class).one();
            try {
                var completion = executor.submit(() -> first.recordResponse(admission.id(), new ResponseObservation("BB", null, false, true, 0)));
                assertThatCode(() -> completion.get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
                assertThat(first.getQuery("BB").orElseThrow().transactionId()).isNull();
            }
            finally {
                fence.rollback();
            }
        }
    }

    @Test
    void lateStartPublicationPrecedesWaitingContinuationAdmission()
            throws Exception
    {
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0));
        Admission observation = first.admitQuery("query", Optional.empty(), Optional.empty());
        String capability = "a".repeat(64);
        try (Handle fence = database.open(); var executor = Executors.newFixedThreadPool(2)) {
            fence.begin();
            int fencePid = fence.createQuery("SELECT pg_backend_pid()").mapTo(Integer.class).one();
            fence.createQuery("SELECT query_id FROM transaction_query WHERE query_id = 'query' FOR UPDATE").mapTo(String.class).one();
            var publication = executor.submit(() -> first.recordResponse(observation.id(), new ResponseObservation("query", "late-transaction", false, false, 0, List.of(capability))));
            try {
                awaitDatabaseWaiter(fencePid);
                var continuation = executor.submit(() -> second.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(capability)));
                assertThatThrownBy(() -> continuation.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                fence.rollback();
                publication.get(5, TimeUnit.SECONDS);
                assertThat(continuation.get(5, TimeUnit.SECONDS).transactionId()).isEqualTo("late-transaction");
            }
            finally {
                if (fence.isInTransaction()) {
                    fence.rollback();
                }
            }
        }
    }

    @Test
    void transactionClosePrecedesWaitingTransactionAdmission()
            throws Exception
    {
        startTransaction("transaction", "start");
        Admission commit = first.admitTransaction("transaction", "owner");
        try (Handle fence = database.open(); var executor = Executors.newFixedThreadPool(2)) {
            fence.begin();
            int fencePid = fence.createQuery("SELECT pg_backend_pid()").mapTo(Integer.class).one();
            fence.createQuery("SELECT transaction_id FROM transaction_binding WHERE transaction_id = 'transaction' FOR UPDATE").mapTo(String.class).one();
            var closing = executor.submit(() -> first.recordResponse(commit.id(), new ResponseObservation("commit", null, true, true, 0)));
            try {
                awaitDatabaseWaiter(fencePid);
                var admission = executor.submit(() -> second.admitTransaction("transaction", "owner"));
                assertThatThrownBy(() -> admission.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                fence.rollback();
                closing.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> admission.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StoreException.class)
                        .satisfies(error -> assertThat(((StoreException) error.getCause()).code()).isEqualTo(CONFLICT));
                assertThat(first.drainStatus("blue").pendingRequests()).isZero();
            }
            finally {
                if (fence.isInTransaction()) {
                    fence.rollback();
                }
            }
        }
    }

    private void assertOneObservationWins(Admission firstAdmission, ResponseObservation firstObservation, Admission secondAdmission, ResponseObservation secondObservation)
            throws Exception
    {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> attemptObservation(start, first, firstAdmission, firstObservation));
            var two = executor.submit(() -> attemptObservation(start, second, secondAdmission, secondObservation));
            start.countDown();
            assertThat(new boolean[] {one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)}).containsExactlyInAnyOrder(true, false);
        }
    }

    private static boolean attemptObservation(CountDownLatch start, PooledTransactionStoreFixture store, Admission admission, ResponseObservation observation)
            throws InterruptedException
    {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            store.recordResponse(admission.id(), observation);
            return true;
        }
        catch (StoreException expected) {
            assertThat(expected.code()).isIn(CONFLICT, OWNER_MISMATCH);
            return false;
        }
    }

    @ParameterizedTest
    @CsvSource({"WRITE,true", "WRITE,false", "READ,true", "READ,false", "IDEMPOTENT,true", "IDEMPOTENT,false"})
    void explicitIsolationIsTransactionLocalWithoutSessionRoundTrips(String safety, boolean commit)
            throws Exception
    {
        try (Connection connection = DriverManager.getConnection(fixtureUrl, fixtureUsername, fixturePassword)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            AtomicInteger isolationReads = new AtomicInteger();
            AtomicInteger isolationWrites = new AtomicInteger();
            Connection observed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (_, method, args) -> {
                if (method.getName().equals("close")) {
                    return null;
                }
                if (method.getName().equals("getTransactionIsolation")) {
                    isolationReads.incrementAndGet();
                }
                if (method.getName().equals("setTransactionIsolation")) {
                    isolationWrites.incrementAndGet();
                }
                try {
                    return method.invoke(connection, args);
                }
                catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            GatewayDatabase gatewayDatabase = new GatewayDatabase(Jdbi.create(() -> observed), GatewayDatabase.Operation.TRANSACTION_STORE);
            StoreException rollback = new StoreException(CONFLICT, "Synthetic rollback");
            Function<Handle, Integer> action = handle -> {
                assertThat(handle.createQuery("SHOW transaction_isolation").mapTo(String.class).one()).isEqualTo("read committed");
                handle.execute("INSERT INTO retry_fixture (id) VALUES (1)");
                if (!commit) {
                    throw rollback;
                }
                return 1;
            };
            Runnable operation = switch (safety) {
                case "WRITE" -> () -> gatewayDatabase.inReadCommittedTransaction(action);
                case "READ" -> () -> gatewayDatabase.inReadCommittedReadTransaction(action);
                case "IDEMPOTENT" -> () -> gatewayDatabase.inReadCommittedIdempotentTransaction(action);
                default -> throw new IllegalArgumentException("Unknown safety");
            };
            if (commit) {
                operation.run();
            }
            else {
                assertThatThrownBy(operation::run).isSameAs(rollback);
            }
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM retry_fixture")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(commit ? 1 : 0);
            }
            assertThat(isolationReads).hasValue(0);
            assertThat(isolationWrites).hasValue(0);
        }
    }

    private void awaitDatabaseWaiter(int fencePid)
            throws InterruptedException
    {
        long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < waitDeadline) {
            boolean waiting = database.withHandle(handle -> handle.createQuery(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE :pid = ANY (pg_blocking_pids(pid)))")
                    .bind("pid", fencePid).mapTo(Boolean.class).one());
            if (waiting) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The database operation did not reach the held row fence");
    }

    private void assertIndependentRequestProgress(boolean completeIndependentRequest, String independentBackend)
            throws Exception
    {
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("blocked-query", null, false, false, 0));
        Admission blocked = first.admitQuery("blocked-query", Optional.empty(), Optional.empty());
        Admission independent = first.admitNew(independentBackend, "other-owner", "group");
        try (Handle fence = database.open(); var executor = Executors.newFixedThreadPool(2)) {
            fence.begin();
            int fencePid = fence.createQuery("SELECT pg_backend_pid()").mapTo(Integer.class).one();
            fence.createQuery("SELECT query_id FROM transaction_query WHERE query_id = 'blocked-query' FOR UPDATE").mapTo(String.class).one();
            var blockedCompletion = executor.submit(() -> first.recordResponse(blocked.id(), new ResponseObservation("blocked-query", null, false, true, 0)));
            try {
                awaitDatabaseWaiter(fencePid);
                var progress = executor.submit(() -> {
                    if (completeIndependentRequest) {
                        second.recordResponse(independent.id(), new ResponseObservation("independent-query", null, false, true, 0));
                    }
                    else {
                        second.admitNew(independentBackend, "independent-owner", "group");
                    }
                });
                assertThatCode(() -> progress.get(2, TimeUnit.SECONDS))
                        .as("An unrelated request must not wait for another query's completion")
                        .doesNotThrowAnyException();
                assertThat(blockedCompletion.isDone()).isFalse();
            }
            finally {
                fence.rollback();
                blockedCompletion.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void queryTerminalStateCannotRegress()
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        first.recordResponse(admission.id(), new ResponseObservation("query", null, false, false, 0));
        Admission late = first.admitQuery("query", Optional.empty(), Optional.empty());
        Admission terminal = second.admitQuery("query", Optional.empty(), Optional.empty());
        second.recordResponse(terminal.id(), new ResponseObservation("query", null, false, true, 0));
        first.recordResponse(late.id(), new ResponseObservation("query", null, false, false, 0));
        assertThat(first.getQuery("query").orElseThrow().terminal()).isTrue();
    }

    @Test
    void versionSixPreservesExistingRoutesAndAffinity()
            throws IOException
    {
        UUID incarnation = UUID.randomUUID();
        String upgradeSchema = "transaction_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (Handle handle = database.open()) {
            handle.begin();
            try {
                handle.execute("CREATE SCHEMA " + upgradeSchema);
                handle.execute("SET LOCAL search_path TO " + upgradeSchema);
                try (var migration = requireNonNull(getClass().getResourceAsStream("/postgresql/V7__transaction_awareness.sql"))) {
                    handle.createScript(new String(migration.readAllBytes(), StandardCharsets.UTF_8)).execute();
                }
                handle.createUpdate("INSERT INTO transaction_backend (incarnation, name, backend_url, external_url, routing_group, node_id, coordinator_id, state) VALUES (:id, 'blue', 'http://blue.example.test', 'http://blue.example.test', 'group', 'node', 'process', 'ACTIVE')")
                        .bind("id", incarnation).execute();
                handle.createUpdate("INSERT INTO transaction_binding (transaction_id, owner_hash, incarnation, start_query_id, state) VALUES ('transaction', 'owner', :id, 'query', 'OPEN')")
                        .bind("id", incarnation).execute();
                handle.createUpdate("INSERT INTO transaction_query (query_id, owner_hash, incarnation, transaction_id) VALUES ('query', 'owner', :id, 'transaction')")
                        .bind("id", incarnation).execute();
                handle.createUpdate("INSERT INTO transaction_admission (admission_id, incarnation, owner_hash, transaction_id, query_id, state) VALUES (:admission, :id, 'owner', 'transaction', 'query', 'UNCERTAIN')")
                        .bind("admission", UUID.randomUUID()).bind("id", incarnation).execute();
                handle.execute("INSERT INTO transaction_route (routing_group, backend_name) VALUES ('group', 'blue')");
                try (var migration = requireNonNull(getClass().getResourceAsStream("/postgresql/V8__backend_incarnation_history.sql"))) {
                    handle.createScript(new String(migration.readAllBytes(), StandardCharsets.UTF_8)).execute();
                }
                assertThat(handle.createQuery("SELECT current_name FROM transaction_backend WHERE incarnation = :id").bind("id", incarnation).mapTo(String.class).one()).isEqualTo("blue");
                assertThat(handle.createQuery("SELECT b.incarnation FROM transaction_route r JOIN transaction_backend b ON b.current_name = r.backend_name").mapTo(UUID.class).one()).isEqualTo(incarnation);
                assertThat(handle.createQuery("SELECT b.incarnation FROM transaction_admission a JOIN transaction_query q ON q.query_id = a.query_id JOIN transaction_binding t ON t.transaction_id = q.transaction_id JOIN transaction_backend b ON b.incarnation = t.incarnation WHERE a.state = 'UNCERTAIN'")
                        .mapTo(UUID.class).one()).isEqualTo(incarnation);
            }
            finally {
                handle.rollback();
            }
        }
    }

    @Test
    void advertisedCapabilityPersistsAcrossStoreInstances()
    {
        String hash = "a".repeat(64);
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0, List.of(hash)));
        assertThat(capabilities("query")).containsExactly(hash);
        Admission continuation = second.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(hash));
        assertThat(continuation.ownerHash()).isEqualTo("owner");
        assertThat(continuation.backend().incarnation()).isEqualTo(initial.backend().incarnation());
    }

    @Test
    void wrongOrCrossQueryCapabilitiesCannotCreateAdmissions()
    {
        String firstHash = "a".repeat(64);
        String secondHash = "b".repeat(64);
        Admission one = first.admitNew("blue", "owner", "group");
        Admission two = first.admitNew("blue", "owner", "group");
        first.recordResponse(one.id(), new ResponseObservation("one", null, false, false, 0, List.of(firstHash)));
        first.recordResponse(two.id(), new ResponseObservation("two", null, false, false, 0, List.of(secondHash)));
        expect(NOT_FOUND, () -> second.admitQuery("one", Optional.empty(), Optional.empty(), Optional.of(secondHash)));
        expect(NOT_FOUND, () -> second.admitQuery("one", Optional.empty(), Optional.empty(), Optional.of("c".repeat(64))));
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        assertThat(first.drainStatus("blue").activeQueries()).isEqualTo(2);
    }

    @Test
    void completedCallbackCannotPublishDifferentCapabilities()
    {
        String firstHash = "a".repeat(64);
        String changedHash = "b".repeat(64);
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0, List.of(firstHash)));
        expect(CONFLICT, () -> second.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0, List.of(changedHash))));
        assertThat(capabilities("query")).containsExactly(firstHash);
    }

    @Test
    void capabilityCollectionIsImmutableAndCanonicalForReplays()
    {
        String firstHash = "a".repeat(64);
        String secondHash = "b".repeat(64);
        List<String> supplied = new ArrayList<>(List.of(secondHash, firstHash, secondHash));
        ResponseObservation observation = new ResponseObservation("query", null, false, false, 0, supplied);
        supplied.clear();
        assertThat(observation.capabilityHashes()).containsExactly(firstHash, secondHash);
        assertThatThrownBy(() -> observation.capabilityHashes().add("c".repeat(64))).isInstanceOf(UnsupportedOperationException.class);
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), observation);
        second.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0, List.of(firstHash, secondHash)));
        assertThat(capabilities("query")).containsExactly(firstHash, secondHash);
    }

    @Test
    void responseConflictRollsBackCapabilitiesAndQueryBinding()
    {
        Admission initial = first.admitNew("blue", "owner", "group");
        expect(CONFLICT, () -> first.recordResponse(initial.id(), new ResponseObservation("query", null, true, false, 0, List.of("a".repeat(64)))));
        assertThat(first.getQuery("query")).isEmpty();
        assertThat(capabilities("query")).isEmpty();
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void laterObservationsRetainPreviousCapabilitiesAndBindLateTransactions()
    {
        String firstHash = "a".repeat(64);
        String secondHash = "b".repeat(64);
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0, List.of(firstHash)));
        Admission continuation = second.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(firstHash));
        second.recordResponse(continuation.id(), new ResponseObservation("query", "transaction", false, false, 0, List.of(secondHash)));
        assertThat(capabilities("query")).containsExactly(firstHash, secondHash);
        assertThat(first.getTransaction("transaction").orElseThrow().state()).isEqualTo("OPEN");
        assertThat(first.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(firstHash)).transactionId()).isEqualTo("transaction");
        assertThat(second.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(secondHash)).transactionId()).isEqualTo("transaction");
    }

    @Test
    void malformedCapabilityHashesCannotPartiallyRecordResponses()
    {
        Admission initial = first.admitNew("blue", "owner", "group");
        for (String hash : List.of("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "A".repeat(64))) {
            expect(CONFLICT, () -> first.recordResponse(initial.id(), new ResponseObservation("query", "transaction", false, false, 0, List.of(hash))));
        }
        assertThat(first.getQuery("query")).isEmpty();
        assertThat(first.getTransaction("transaction")).isEmpty();
        assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
    }

    @Test
    void recordedCapabilityCannotBypassHistoricalSealedIncarnation()
    {
        String hash = "a".repeat(64);
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("query", null, false, true, 0, List.of(hash)));
        first.seal("blue", first.beginDrain("blue").generation());
        assertThat(second.admitNew("green", "owner", "group").backend().incarnation()).isNotEqualTo(initial.backend().incarnation());
        expect(SEALED, () -> second.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(hash)));
        assertThat(capabilities("query")).containsExactly(hash);
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
    }

    @Test
    void capabilityCheckWaitsForAtomicPublicationUnderBackendFence()
            throws Exception
    {
        String hash = "a".repeat(64);
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("query", null, false, false, 0));
        try (var executor = Executors.newSingleThreadExecutor(); Handle fence = database.open()) {
            fence.begin();
            fence.createQuery("SELECT name FROM transaction_backend WHERE incarnation = :id FOR UPDATE")
                    .bind("id", initial.backend().incarnation()).mapTo(String.class).one();
            CountDownLatch started = new CountDownLatch(1);
            var continuation = executor.submit(() -> {
                started.countDown();
                return second.admitQuery("query", Optional.empty(), Optional.empty(), Optional.of(hash));
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> continuation.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            fence.createUpdate("INSERT INTO transaction_query_capability (query_id, capability_hash) VALUES ('query', :hash)")
                    .bind("hash", hash).execute();
            fence.commit();
            assertThat(continuation.get(5, TimeUnit.SECONDS).queryId()).isEqualTo("query");
        }
    }

    @Test
    void legacyQueryWithoutRecordedCapabilitiesFailsClosed()
    {
        Admission initial = first.admitNew("blue", "owner", "group");
        first.recordResponse(initial.id(), new ResponseObservation("legacy", null, false, false, 0));
        expect(NOT_FOUND, () -> second.admitQuery("legacy", Optional.empty(), Optional.empty(), Optional.of("a".repeat(64))));
        assertThat(first.drainStatus("blue").pendingRequests()).isZero();
        first.recordResponse(initial.id(), new ResponseObservation("legacy", null, false, false, 0));
        assertThat(second.admitQuery("legacy", Optional.of("owner"), Optional.empty()).queryId()).isEqualTo("legacy");
    }

    @Test
    void databaseFailureDuringCapabilityPublicationRollsBackEntireObservation()
    {
        String firstHash = "a".repeat(64);
        String rejectedHash = "b".repeat(64);
        Admission initial = first.admitNew("blue", "owner", "group");
        database.useHandle(handle -> handle.execute("ALTER TABLE transaction_query_capability ADD CONSTRAINT test_capability_failure CHECK (capability_hash <> repeat('b', 64))"));
        try {
            assertThatThrownBy(() -> first.recordResponse(initial.id(), new ResponseObservation("query", "transaction", false, false, 0, List.of(firstHash, rejectedHash))))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("test_capability_failure");
            assertThat(first.getQuery("query")).isEmpty();
            assertThat(first.getTransaction("transaction")).isEmpty();
            assertThat(capabilities("query")).isEmpty();
            assertThat(first.drainStatus("blue").pendingRequests()).isEqualTo(1);
        }
        finally {
            database.useHandle(handle -> handle.execute("ALTER TABLE transaction_query_capability DROP CONSTRAINT test_capability_failure"));
        }
    }

    @Test
    void drainStatusReadsOutstandingWorkWithoutScanningCompletedHistory()
            throws IOException
    {
        UUID backend = first.getBackend("blue").orElseThrow().incarnation();
        database.useHandle(handle -> {
            handle.createUpdate(
                    """
                    INSERT INTO transaction_admission (admission_id, incarnation, owner_hash, state, observation)
                    SELECT md5(n::text)::uuid, :backend, 'owner', 'COMPLETE', 'finished'
                    FROM generate_series(1, 40000) n
                    """).bind("backend", backend).execute();
            handle.createUpdate(
                    """
                    INSERT INTO transaction_binding (transaction_id, owner_hash, incarnation, start_query_id, state)
                    SELECT 'closed-' || n, 'owner', :backend, 'history', 'CLOSED'
                    FROM generate_series(1, 40000) n
                    """).bind("backend", backend).execute();
            handle.createUpdate(
                    """
                    INSERT INTO transaction_query (query_id, owner_hash, incarnation, terminal, retain_until)
                    SELECT 'expired-' || n, 'owner', :backend, TRUE, statement_timestamp() - interval '1 day'
                    FROM generate_series(1, 40000) n
                    """).bind("backend", backend).execute();
            handle.createUpdate(
                    """
                    INSERT INTO transaction_binding (transaction_id, owner_hash, incarnation, start_query_id, state)
                    VALUES ('open', 'owner', :backend, 'running', 'OPEN')
                    """).bind("backend", backend).execute();
            handle.createUpdate(
                    """
                    INSERT INTO transaction_query (query_id, owner_hash, incarnation, terminal, retain_until)
                    VALUES ('running', 'owner', :backend, FALSE, NULL),
                           ('retained', 'owner', :backend, TRUE, statement_timestamp() + interval '1 day')
                    """).bind("backend", backend).execute();
            handle.execute("ANALYZE transaction_admission, transaction_binding, transaction_query, transaction_backend");
        });
        first.admitNew("blue", "owner", "group");
        first.markUncertain(first.admitNew("blue", "owner", "group").id());
        var status = first.drainStatus("blue");
        assertThat(status.pendingRequests()).isEqualTo(2);
        assertThat(status.openTransactions()).isEqualTo(1);
        assertThat(status.activeQueries()).isEqualTo(2);
        String explain = database.withHandle(handle -> handle.createQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + TransactionStore.DRAIN_STATUS_SQL)
                .bind("id", backend).mapTo(String.class).one());
        var plan = new ObjectMapper().readTree(explain).get(0).path("Plan");
        long blocks = plan.path("Shared Hit Blocks").asLong() + plan.path("Shared Read Blocks").asLong();
        assertThat(blocks).as("Drain status must not read completed history: %s", explain).isLessThan(64);
    }

    private List<String> capabilities(String queryId)
    {
        return database.withHandle(handle -> handle.createQuery("SELECT capability_hash FROM transaction_query_capability WHERE query_id = :query ORDER BY capability_hash")
                .bind("query", queryId).mapTo(String.class).list());
    }

    private Admission startTransaction(String transactionId, String queryId)
    {
        Admission admission = first.admitNew("blue", "owner", "group");
        first.recordResponse(admission.id(), new ResponseObservation(queryId, transactionId, false, true, 0));
        return admission;
    }

    private String retention(String queryId)
    {
        return database.withHandle(handle -> handle.createQuery("SELECT retain_until::text FROM transaction_query WHERE query_id = :id")
                .bind("id", queryId).mapTo(String.class).one());
    }

    private static void expect(ErrorCode code, Runnable operation)
    {
        assertThatThrownBy(operation::run).isInstanceOf(StoreException.class)
                .satisfies(error -> assertThat(((StoreException) error).code()).isEqualTo(code));
    }
}
