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

import io.airlift.log.Logger;
import io.airlift.stats.CounterStat;
import io.trino.gateway.ha.transaction.TransactionStore.Admission;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class TransactionLifecycleStats
{
    private static final Logger log = Logger.get(TransactionLifecycleStats.class);
    private static final long LOG_INTERVAL_NANOS = SECONDS.toNanos(30);

    private enum Event
    {
        EXECUTING_RESULT_NOT_FOUND, MARK_UNCERTAIN_SUCCEEDED, MARK_UNCERTAIN_FAILED, POOL_DRAIN_BLOCKED
    }

    private final CounterStat terminalResults = new CounterStat();
    private final CounterStat cancellationAcknowledgements = new CounterStat();
    private final CounterStat notFoundContinuations = new CounterStat();
    private final CounterStat executingNotFoundResponses = new CounterStat();
    private final CounterStat markUncertainSuccesses = new CounterStat();
    private final CounterStat markUncertainFailures = new CounterStat();
    private final CounterStat blockedDrainObservations = new CounterStat();
    private final EnumMap<Event, AtomicLong> lastLogNanos = new EnumMap<>(Event.class);
    private final LongSupplier nanoTime;

    public TransactionLifecycleStats()
    {
        this(System::nanoTime);
    }

    TransactionLifecycleStats(LongSupplier nanoTime)
    {
        this.nanoTime = nanoTime;
        for (Event event : Event.values()) {
            lastLogNanos.put(event, new AtomicLong(Long.MIN_VALUE));
        }
    }

    void terminalResult(boolean cancellation)
    {
        (cancellation ? cancellationAcknowledgements : terminalResults).update(1);
    }

    void notFoundContinuation(Admission admission, boolean executingNotFound)
    {
        notFoundContinuations.update(1);
        if (executingNotFound) {
            executingNotFoundResponses.update(1);
            requestLog(Event.EXECUTING_RESULT_NOT_FOUND, admission, 404);
        }
    }

    void uncertain(Admission admission, int status, boolean persisted)
    {
        (persisted ? markUncertainSuccesses : markUncertainFailures).update(1);
        requestLog(persisted ? Event.MARK_UNCERTAIN_SUCCEEDED : Event.MARK_UNCERTAIN_FAILED, admission, status);
    }

    void drain(String instance, UUID incarnation, String phase, long pending, long transactions, long queries)
    {
        if (!phase.equals("DRAINING") || (pending == 0 && transactions == 0 && queries == 0)) {
            return;
        }
        blockedDrainObservations.update(1);
        if (mayLog(Event.POOL_DRAIN_BLOCKED)) {
            log.info("reason=POOL_DRAIN_BLOCKED instance=%s incarnation=%s pendingRequests=%s openTransactions=%s activeQueries=%s",
                    safeIdentifier(instance),
                    incarnation,
                    pending,
                    transactions,
                    queries);
        }
    }

    private void requestLog(Event event, Admission admission, int status)
    {
        if (mayLog(event)) {
            log.warn("reason=%s admission=%s query=%s incarnation=%s status=%s",
                    event,
                    admission.id(),
                    safeIdentifier(admission.queryId()),
                    admission.backend().incarnation(),
                    status);
        }
    }

    private boolean mayLog(Event event)
    {
        AtomicLong previousLog = lastLogNanos.get(event);
        long previous = previousLog.get();
        long now = nanoTime.getAsLong();
        return (previous == Long.MIN_VALUE || now - previous >= LOG_INTERVAL_NANOS) && previousLog.compareAndSet(previous, now);
    }

    private static String safeIdentifier(String value)
    {
        return value == null ? "none" : value.matches("[a-zA-Z0-9_.-]{1,128}") ? value : "redacted";
    }

    @Managed
    @Nested
    public CounterStat getTerminalResults()
    {
        return terminalResults;
    }

    @Managed
    @Nested
    public CounterStat getCancellationAcknowledgements()
    {
        return cancellationAcknowledgements;
    }

    @Managed
    @Nested
    public CounterStat getNotFoundContinuations()
    {
        return notFoundContinuations;
    }

    @Managed
    @Nested
    public CounterStat getExecutingNotFoundResponses()
    {
        return executingNotFoundResponses;
    }

    @Managed
    @Nested
    public CounterStat getMarkUncertainSuccesses()
    {
        return markUncertainSuccesses;
    }

    @Managed
    @Nested
    public CounterStat getMarkUncertainFailures()
    {
        return markUncertainFailures;
    }

    @Managed
    @Nested
    public CounterStat getBlockedDrainObservations()
    {
        return blockedDrainObservations;
    }
}
