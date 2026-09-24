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
import io.trino.gateway.ha.transaction.TransactionStore.BackendRef;
import org.junit.jupiter.api.Test;
import org.weakref.jmx.MBeanExporter;

import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class TestTransactionLifecycleStats
{
    @Test
    void rateLimitsLogsIndependentlyWithoutSamplingCounters()
    {
        AtomicLong time = new AtomicLong();
        TransactionLifecycleStats stats = new TransactionLifecycleStats(time::get);
        BackendRef backend = new BackendRef("fixture", UUID.randomUUID(), "http://private.example.test", "http://private.example.test", "group", "node", "abcde");
        Admission admission = new Admission(UUID.randomUUID(), backend, "owner-secret", "transaction-secret", "20260924_120000_00001_abcde");
        try (CapturedLogs logs = new CapturedLogs()) {
            for (int index = 0; index < 1000; index++) {
                stats.notFoundContinuation(admission, true);
                stats.uncertain(admission, 503, true);
                stats.uncertain(admission, 0, false);
                stats.drain("unsafe\ninstance", backend.incarnation(), "DRAINING", 2, 3, 4);
                stats.terminalResult(false);
                stats.terminalResult(true);
            }
            assertThat(logs.messages).hasSize(4);
            assertThat(logs.messages).anySatisfy(message -> assertThat(message).contains("reason=POOL_DRAIN_BLOCKED", "instance=redacted", "pendingRequests=2", "openTransactions=3", "activeQueries=4"));
            assertThat(logs.messages).allSatisfy(message -> assertThat(message).doesNotContain("private.example", "owner-secret", "transaction-secret", "\n"));
            assertThat(stats.getExecutingNotFoundResponses().getTotalCount()).isEqualTo(1000);
            assertThat(stats.getMarkUncertainSuccesses().getTotalCount()).isEqualTo(1000);
            assertThat(stats.getMarkUncertainFailures().getTotalCount()).isEqualTo(1000);
            assertThat(stats.getBlockedDrainObservations().getTotalCount()).isEqualTo(1000);
            assertThat(stats.getTerminalResults().getTotalCount()).isEqualTo(1000);
            assertThat(stats.getCancellationAcknowledgements().getTotalCount()).isEqualTo(1000);
            time.addAndGet(SECONDS.toNanos(30));
            stats.uncertain(admission, 503, true);
            assertThat(logs.messages).hasSize(5);
        }
    }

    @Test
    void ignoresNonDrainingAndUnblockedMembers()
    {
        TransactionLifecycleStats stats = new TransactionLifecycleStats();
        stats.drain("member", UUID.randomUUID(), "ACTIVE", 1, 2, 3);
        stats.drain("member", UUID.randomUUID(), "DRAINING", 0, 0, 0);
        assertThat(stats.getBlockedDrainObservations().getTotalCount()).isZero();
    }

    @Test
    void exportsCounterAttributesWithoutDynamicLabels()
            throws Exception
    {
        var server = MBeanServerFactory.newMBeanServer();
        var exporter = new MBeanExporter(server);
        var stats = new TransactionLifecycleStats();
        ObjectName name = new ObjectName("test:type=TransactionLifecycleStats");
        exporter.export(name, stats);
        try {
            stats.terminalResult(false);
            assertThat(server.getAttribute(name, "TerminalResults.TotalCount")).isEqualTo(1L);
            assertThat(server.getMBeanInfo(name).getAttributes()).allSatisfy(attribute ->
                    assertThat(attribute.getName()).doesNotContain("query", "instance", "incarnation"));
        }
        finally {
            exporter.unexport(name);
        }
    }

    static final class CapturedLogs
            implements AutoCloseable
    {
        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(TransactionLifecycleStats.class.getName());
        private final Handler handler = new Handler()
        {
            @Override
            public void publish(LogRecord record)
            {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        CapturedLogs()
        {
            logger.addHandler(handler);
        }

        @Override
        public void close()
        {
            logger.removeHandler(handler);
        }
    }
}
