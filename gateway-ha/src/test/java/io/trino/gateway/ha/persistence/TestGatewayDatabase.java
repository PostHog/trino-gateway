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
package io.trino.gateway.ha.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static io.trino.gateway.ha.persistence.GatewayDatabase.Operation.TRANSACTION_STORE;
import static io.trino.gateway.ha.persistence.GatewayDatabase.Safety.IDEMPOTENT;
import static io.trino.gateway.ha.persistence.GatewayDatabase.Safety.READ;
import static io.trino.gateway.ha.persistence.GatewayDatabase.Safety.WRITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGatewayDatabase
{
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlineBetweenAttemptsRetainsTheOriginalFailure(boolean ambientPhaseExpires)
    {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        AtomicBoolean slept = new AtomicBoolean();
        AtomicLong now = new AtomicLong();
        RuntimeException original = new RuntimeException(new SQLException("PRIVATE_DATABASE_FAILURE", "08006", ambientPhaseExpires ? 9181 : 9182));
        GatewayDatabase.RetryTiming timing = new GatewayDatabase.RetryTiming()
        {
            @Override
            long nanoTime()
            {
                return now.get();
            }

            @Override
            long remainingNanos()
                    throws SQLException
            {
                if (ambientPhaseExpires && slept.get()) {
                    throw new SQLException("synthetic phase expiry", "57014");
                }
                return Long.MAX_VALUE;
            }

            @Override
            void sleep(long nanos)
            {
                if (sleeps.incrementAndGet() == 1) {
                    return;
                }
                slept.set(true);
                if (!ambientPhaseExpires) {
                    now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(11));
                }
            }
        };
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        var logger = java.util.logging.Logger.getLogger(GatewayDatabase.class.getName());
        Handler handler = capturingHandler(records);
        logger.addHandler(handler);
        try {
            assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(true), new AtomicBoolean(), () -> {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException(new SQLTransientConnectionException("PRIVATE_FIRST_FAILURE", "08001", 9180));
                }
                throw original;
            }, timing)).hasCause(original)
                    .hasMessage(ambientPhaseExpires ? "Database phase deadline expired before retry" : "Database retry scheduling deadline expired");
            assertThat(calls).hasValue(2);
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).isNull();
                assertThat(record.getMessage()).contains("outcome=DEADLINE", "attempts=2", "exceptionClass=SQLException", "sqlState=08006", "vendorCode=" + (ambientPhaseExpires ? 9181 : 9182))
                        .doesNotContain("PRIVATE_DATABASE_FAILURE", "PRIVATE_FIRST_FAILURE", "57014");
            });
        }
        finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void initiallyExpiredPhaseDoesNotInventDatabaseFailure()
    {
        AtomicInteger calls = new AtomicInteger();
        GatewayDatabase.RetryTiming timing = new GatewayDatabase.RetryTiming()
        {
            @Override
            long remainingNanos()
                    throws SQLException
            {
                throw new SQLException("synthetic phase expiry", "57014");
            }
        };
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        var logger = java.util.logging.Logger.getLogger(GatewayDatabase.class.getName());
        Handler handler = capturingHandler(records);
        logger.addHandler(handler);
        try {
            assertThatThrownBy(() -> GatewayDatabase.retry(GatewayDatabase.Operation.HISTORY_DAO, READ, new AtomicBoolean(), new AtomicBoolean(), calls::incrementAndGet, timing))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("phase deadline").hasNoCause();
            assertThat(calls).hasValue(0);
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).isNull();
                assertThat(record.getMessage()).contains("outcome=PHASE_DEADLINE", "attempts=0", "exceptionClass=NONE", "sqlState=NONE", "vendorCode=0")
                        .doesNotContain("57014");
            });
        }
        finally {
            logger.removeHandler(handler);
        }
    }

    private static Handler capturingHandler(List<LogRecord> records)
    {
        return new Handler()
        {
            @Override
            public void publish(LogRecord record)
            {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "08001", "40001", "40P01", "55P03", "57P01", "57P03"})
    void retriesTransientReads(String state)
    {
        AtomicInteger calls = new AtomicInteger();
        String result = GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(true), () -> {
            if (calls.incrementAndGet() == 1) {
                throw sql(state);
            }
            return "result";
        });
        assertThat(result).isEqualTo("result");
        assertThat(calls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001", "40P01", "55P03"})
    void retriesWritesOnlyAfterKnownAbort(String state)
    {
        AtomicInteger calls = new AtomicInteger();
        assertThat(GatewayDatabase.retry(TRANSACTION_STORE, WRITE, new AtomicBoolean(true), succeedsAfterFailure(calls, sql(state))))
                .isEqualTo("result");
        assertThat(calls).hasValue(2);
    }

    @Test
    void idempotentCompletionCanResolveAnAmbiguousCommit()
    {
        AtomicInteger calls = new AtomicInteger();
        assertThat(GatewayDatabase.retry(TRANSACTION_STORE, IDEMPOTENT, new AtomicBoolean(true), succeedsAfterFailure(calls, sql("08006"))))
                .isEqualTo("result");
        assertThat(calls).hasValue(2);
    }

    @Test
    void connectionAcquisitionCanRetryBeforeWriteStarts()
    {
        AtomicInteger calls = new AtomicInteger();
        assertThat(GatewayDatabase.retry(
                TRANSACTION_STORE,
                WRITE,
                new AtomicBoolean(false),
                succeedsAfterFailure(calls, new RuntimeException(new SQLTransientConnectionException("synthetic connection failure", "08001")))))
                .isEqualTo("result");
        assertThat(calls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "23505", "28P01", "42601", "57014", "57P01", "57P03", "invalid\nsecret"})
    void unsafeOrPermanentWritesAreNotReplayed(String state)
    {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = sql(state);
        assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, WRITE, new AtomicBoolean(true), succeedsAfterFailure(calls, failure)))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void failureWithoutSqlStateIsNotAssumedTransient()
    {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new RuntimeException(new SQLException("private message"));
        assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, IDEMPOTENT, new AtomicBoolean(true), succeedsAfterFailure(calls, failure)))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void suppressedCleanupFailureDoesNotMakeDomainFailureRetryable()
    {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("synthetic domain conflict");
        failure.addSuppressed(new SQLException("synthetic close failure", "40001"));
        assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, IDEMPOTENT, new AtomicBoolean(true), succeedsAfterFailure(calls, failure)))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void persistentFailureHasThreeAttempts()
    {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(true), () -> {
            calls.incrementAndGet();
            throw sql("08006");
        })).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void localCapacityFailureDoesNotAmplifyLoad()
    {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new RuntimeException(new SQLTransientConnectionException("synthetic capacity limit"));
        assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(false), succeedsAfterFailure(calls, failure)))
                .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryBudgetDoesNotChangeStatementDeadline()
    {
        GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(true), () -> {
            try {
                assertThat(DatabaseDeadline.remainingNanos()).isEqualTo(Long.MAX_VALUE);
            }
            catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Test
    void interruptedCompletionStillGetsOneInitialPersistenceAttempt()
    {
        AtomicInteger calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            GatewayDatabase.retry(TRANSACTION_STORE, IDEMPOTENT, new AtomicBoolean(true), () -> calls.incrementAndGet());
            assertThat(calls).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void existingDeadlinePreventsAnotherAttempt()
    {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> DatabaseDeadline.withDeadline(System.nanoTime() + MILLISECONDS.toNanos(500), false,
                () -> GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(true), () -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(600);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    throw sql("08006");
                }))).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void interruptionPreservesFlagAndStopsRetry()
    {
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, READ, new AtomicBoolean(true), () -> {
                calls.incrementAndGet();
                Thread.currentThread().interrupt();
                throw sql("08006");
            })).isInstanceOf(RuntimeException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(1);
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void diagnosticsDoNotExposeSqlMessagesAndKeepDistinctFailures()
    {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        var logger = java.util.logging.Logger.getLogger(GatewayDatabase.class.getName());
        Handler handler = new Handler()
        {
            @Override
            public void publish(LogRecord record)
            {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            for (String state : new String[] {null, "XX001", "XX002"}) {
                RuntimeException failure = new RuntimeException(new SQLException("SECRET_SQL_AND_PASSWORD", state, 9173));
                assertThatThrownBy(() -> GatewayDatabase.retry(GatewayDatabase.Operation.REJECT_ADMISSION, WRITE, new AtomicBoolean(true), () -> { throw failure; }))
                        .isSameAs(failure);
            }
            assertThat(records).hasSize(3).allSatisfy(record -> {
                assertThat(record.getThrown()).isNull();
                assertThat(record.getMessage()).contains("operation=REJECT_ADMISSION", "phase=EXECUTE_OR_COMMIT", "outcome=NOT_RETRYABLE", "vendorCode=9173")
                        .doesNotContain("SECRET_SQL_AND_PASSWORD");
            });
            assertThat(records.getFirst().getMessage()).contains("sqlState=UNKNOWN");
            records.clear();
            RuntimeException unexpected = new IllegalArgumentException("SECRET_MAPPING_VALUE");
            assertThatThrownBy(() -> GatewayDatabase.retry(GatewayDatabase.Operation.HISTORY_DAO, READ, new AtomicBoolean(true), () -> { throw unexpected; }))
                    .isSameAs(unexpected);
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).isNull();
                assertThat(record.getMessage()).contains("exceptionClass=IllegalArgumentException", "sqlState=NONE").doesNotContain("SECRET_MAPPING_VALUE");
            });
            records.clear();
            RuntimeException conflict = new io.trino.gateway.ha.transaction.TransactionStore.StoreException(io.trino.gateway.ha.transaction.TransactionStore.ErrorCode.CONFLICT, "expected");
            assertThatThrownBy(() -> GatewayDatabase.retry(TRANSACTION_STORE, WRITE, new AtomicBoolean(true), () -> { throw conflict; }))
                    .isSameAs(conflict);
            assertThat(records).isEmpty();
        }
        finally {
            logger.removeHandler(handler);
        }
    }

    private static Supplier<String> succeedsAfterFailure(AtomicInteger calls, RuntimeException failure)
    {
        return () -> {
            if (calls.incrementAndGet() == 1) {
                throw failure;
            }
            return "result";
        };
    }

    private static RuntimeException sql(String state)
    {
        return new RuntimeException(new SQLException("synthetic private SQL parameter", state));
    }
}
