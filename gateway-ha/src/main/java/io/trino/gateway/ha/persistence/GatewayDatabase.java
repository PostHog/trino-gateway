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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.airlift.log.Logger;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Retries complete database operations, never individual statements in an aborted transaction.
 */
public final class GatewayDatabase
{
    private static final Logger log = Logger.get(GatewayDatabase.class);
    private static final long RETRY_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_ATTEMPTS = 3;
    private static final RetryTiming RETRY_TIMING = new RetryTiming();

    static class RetryTiming
    {
        long nanoTime()
        {
            return System.nanoTime();
        }

        long remainingNanos()
                throws SQLException
        {
            return DatabaseDeadline.remainingNanos();
        }

        void sleep(long nanos)
                throws InterruptedException
        {
            TimeUnit.NANOSECONDS.sleep(nanos);
        }
    }

    public enum Operation
    {
        TRANSACTION_STORE, RECORD_RESPONSE, MARK_UNCERTAIN, REJECT_ADMISSION, POOL_STORE, ROLLOUT_STORE, BACKEND_DAO, HISTORY_DAO
    }

    private record FailureKey(Operation operation, String outcome, String exceptionClass, String state, int vendorCode) {}

    private static final Cache<FailureKey, Boolean> WARNINGS = Caffeine.newBuilder().maximumSize(128).expireAfterWrite(30, TimeUnit.SECONDS).build();
    private static final AtomicLong SUPPRESSED_WARNINGS = new AtomicLong();

    enum Safety
    {
        READ, WRITE, IDEMPOTENT
    }

    public interface ExpectedFailure {}

    private final Jdbi jdbi;
    private final Operation operation;

    public GatewayDatabase(Jdbi jdbi, Operation operation)
    {
        this.jdbi = requireNonNull(jdbi, "jdbi is null");
        this.operation = requireNonNull(operation, "operation is null");
    }

    public Jdbi raw()
    {
        return jdbi;
    }

    public GatewayDatabase withOperation(Operation selected)
    {
        return new GatewayDatabase(jdbi, selected);
    }

    public <T> T withHandle(Function<Handle, T> action)
    {
        return run(Safety.READ, false, action);
    }

    public <T> T inTransaction(Function<Handle, T> action)
    {
        return run(Safety.WRITE, true, action);
    }

    public <T> T inReadTransaction(Function<Handle, T> action)
    {
        return run(Safety.READ, true, action);
    }

    public <T> T inIdempotentTransaction(Function<Handle, T> action)
    {
        return run(Safety.IDEMPOTENT, true, action);
    }

    public <T> T inReadCommittedIdempotentTransaction(Function<Handle, T> action)
    {
        return run(Safety.IDEMPOTENT, true, action, true);
    }

    public <T> T inReadCommittedTransaction(Function<Handle, T> action)
    {
        return run(Safety.WRITE, true, action, true);
    }

    public <T> T inReadCommittedReadTransaction(Function<Handle, T> action)
    {
        return run(Safety.READ, true, action, true);
    }

    public void useTransaction(Consumer<Handle> action)
    {
        inTransaction(handle -> {
            action.accept(handle);
            return null;
        });
    }

    public void useIdempotentTransaction(Consumer<Handle> action)
    {
        inIdempotentTransaction(handle -> {
            action.accept(handle);
            return null;
        });
    }

    private <T> T run(Safety safety, boolean transaction, Function<Handle, T> action)
    {
        return run(safety, transaction, action, false);
    }

    private <T> T run(Safety safety, boolean transaction, Function<Handle, T> action, boolean readCommitted)
    {
        AtomicBoolean entered = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        return retry(operation, safety, entered, completed, () -> {
            entered.set(false);
            completed.set(false);
            return jdbi.withHandle(handle -> {
                entered.set(true);
                T result = transaction ? handle.inTransaction(transactionHandle -> {
                    if (readCommitted) {
                        transactionHandle.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                    }
                    return action.apply(transactionHandle);
                }) : action.apply(handle);
                completed.set(true);
                return result;
            });
        });
    }

    public static <T> T dao(Jdbi jdbi, Class<T> type, Operation operation)
    {
        GatewayDatabase database = new GatewayDatabase(jdbi, operation);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "GatewayDatabaseDao[" + operation + "]";
                    default -> throw new IllegalStateException("Unsupported object method");
                };
            }
            Safety safety = method.isAnnotationPresent(SqlQuery.class) ? Safety.READ : Safety.WRITE;
            return database.run(safety, false, handle -> {
                try {
                    return method.invoke(handle.attach(type), args);
                }
                catch (InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (e.getCause() instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("Database operation failed", e.getCause());
                }
                catch (IllegalAccessException e) {
                    throw new IllegalStateException("Database operation inaccessible", e);
                }
            });
        }));
    }

    static <T> T retry(Operation operation, Safety safety, AtomicBoolean entered, Supplier<T> action)
    {
        return retry(operation, safety, entered, new AtomicBoolean(), action);
    }

    private static <T> T retry(Operation operation, Safety safety, AtomicBoolean entered, AtomicBoolean completed, Supplier<T> action)
    {
        return retry(operation, safety, entered, completed, action, RETRY_TIMING);
    }

    static <T> T retry(Operation operation, Safety safety, AtomicBoolean entered, AtomicBoolean completed, Supplier<T> action, RetryTiming timing)
    {
        long retryDeadline = timing.nanoTime() + RETRY_BUDGET_NANOS;
        RuntimeException lastFailure = null;
        for (int attempt = 1; ; attempt++) {
            boolean retryBudgetExpired = attempt > 1 && timing.nanoTime() >= retryDeadline;
            try {
                if (retryBudgetExpired) {
                    throw new SQLException("Database retry scheduling deadline expired");
                }
                timing.remainingNanos();
                if (attempt > 1 && Thread.currentThread().isInterrupted()) {
                    logFailure(operation, entered.get(), attempt - 1, "INTERRUPTED", "InterruptedException", "NONE", 0);
                    throw new IllegalStateException("Database operation interrupted");
                }
                T result = action.get();
                if (attempt > 1) {
                    log.debug("reason=DATABASE_RETRY_RECOVERED operation=%s attempts=%s", operation, attempt);
                }
                return result;
            }
            catch (SQLException deadline) {
                if (lastFailure == null) {
                    logFailure(operation, entered.get(), 0, "PHASE_DEADLINE", "NONE", "NONE", 0);
                    throw new IllegalStateException("Database phase deadline expired before the initial attempt");
                }
                SQLException sql = sqlFailure(lastFailure);
                logFailure(
                        operation,
                        entered.get(),
                        attempt - 1,
                        "DEADLINE",
                        (sql == null ? lastFailure : sql).getClass().getSimpleName(),
                        sql == null ? "NONE" : safeState(sql.getSQLState()),
                        sql == null ? 0 : sql.getErrorCode());
                throw new IllegalStateException(retryBudgetExpired ? "Database retry scheduling deadline expired" : "Database phase deadline expired before retry", lastFailure);
            }
            catch (RuntimeException failure) {
                lastFailure = failure;
                SQLException sql = sqlFailure(failure);
                String state = sql == null ? "NONE" : safeState(sql.getSQLState());
                boolean aborted = state.equals("40001") || state.equals("40P01") || state.equals("55P03");
                boolean connection = state.startsWith("08") || state.equals("57P01") || state.equals("57P03");
                boolean replaySafe = safety != Safety.WRITE || !entered.get();
                boolean transientFailure = (aborted && (safety != Safety.WRITE || !completed.get())) || (connection && replaySafe);
                long remaining;
                try {
                    remaining = Math.min(timing.remainingNanos(), retryDeadline - timing.nanoTime());
                }
                catch (SQLException ignored) {
                    remaining = 0;
                }
                long delay = TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(10, 26) * attempt);
                String outcome = Thread.currentThread().isInterrupted() ? "INTERRUPTED" :
                        remaining <= delay ? "DEADLINE" :
                        completed.get() && safety == Safety.WRITE ? "COMPLETED_WRITE" :
                        connection && !replaySafe ? "AMBIGUOUS_WRITE" :
                        !transientFailure ? "NOT_RETRYABLE" : attempt >= MAX_ATTEMPTS ? "EXHAUSTED" : "RETRY";
                String exceptionClass = (sql == null ? failure : sql).getClass().getSimpleName();
                int vendorCode = sql == null ? 0 : sql.getErrorCode();
                // Exception messages and SQL can contain credentials, parameters, or tenant data.
                if (!outcome.equals("RETRY")) {
                    if (sql != null || !(failure instanceof ExpectedFailure)) {
                        logFailure(operation, entered.get(), attempt, outcome, exceptionClass, state, vendorCode);
                    }
                    throw failure;
                }
                log.debug(
                        "reason=DATABASE_RETRY operation=%s phase=%s attempt=%s exceptionClass=%s sqlState=%s vendorCode=%s",
                        operation,
                        entered.get() ? "EXECUTE_OR_COMMIT" : "ACQUIRE",
                        attempt,
                        exceptionClass,
                        state,
                        vendorCode);
                try {
                    timing.sleep(delay);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    logFailure(operation, entered.get(), attempt, "INTERRUPTED", exceptionClass, state, vendorCode);
                    throw failure;
                }
            }
        }
    }

    private static SQLException sqlFailure(Throwable failure)
    {
        SQLException fallback = null;
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof SQLException sql) {
                if (fallback == null) {
                    fallback = sql;
                }
                for (int next = 0; sql != null && next < 16; next++, sql = sql.getNextException()) {
                    if (sql.getSQLState() != null || sql instanceof SQLTransientConnectionException) {
                        return sql;
                    }
                }
            }
        }
        return fallback;
    }

    private static void logFailure(Operation operation, boolean entered, int attempts, String outcome, String exceptionClass, String state, int vendorCode)
    {
        FailureKey key = new FailureKey(operation, outcome, exceptionClass, state, vendorCode);
        if (WARNINGS.asMap().putIfAbsent(key, true) != null) {
            SUPPRESSED_WARNINGS.incrementAndGet();
            return;
        }
        log.warn("reason=DATABASE_OPERATION_FAILED operation=%s phase=%s attempts=%s outcome=%s exceptionClass=%s sqlState=%s vendorCode=%s suppressed=%s",
                operation,
                entered ? "EXECUTE_OR_COMMIT" : "ACQUIRE",
                attempts,
                outcome,
                exceptionClass,
                state,
                vendorCode,
                SUPPRESSED_WARNINGS.getAndSet(0));
    }

    private static String safeState(String state)
    {
        return state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN";
    }
}
