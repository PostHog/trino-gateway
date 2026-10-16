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
package io.trino.gateway.ha.router;

import io.trino.gateway.ha.clustermonitor.ClusterStats;
import io.trino.gateway.ha.clustermonitor.ExternalLoad;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

final class TestBackendLoad
{
    @Test
    void loadWeightsRunningQueriesByMemoryPressure()
    {
        ClusterStats stats = stats("a", 4, 10, 25, 100);
        assertThat(BackendLoad.memoryPressure(stats).orElseThrow()).isCloseTo(0.25, within(1e-9));
        assertThat(BackendLoad.load(stats)).isCloseTo(4 + 10 * 1.25, within(1e-9));
    }

    @Test
    void busyMembersWithLowMemoryPressureSortAfterLessBusyMembers()
    {
        // CPU-bound queries reserve little memory. Running queries must still count as load.
        ClusterStats busy = stats("busy", 0, 6, 9, 10_000);
        ClusterStats single = stats("single", 0, 1, 0, 10_000);
        ClusterStats idle = stats("idle", 0, 0, 0, 10_000);
        assertThat(BackendLoad.load(idle)).isZero();
        assertThat(BackendLoad.load(single)).isGreaterThanOrEqualTo(BackendLoad.load(idle) + 1);
        assertThat(BackendLoad.load(busy)).isGreaterThanOrEqualTo(BackendLoad.load(single) + 5);

        Map<String, ClusterStats> stats = Map.of("busy", busy, "single", single, "idle", idle);
        List<String> names = new ArrayList<>(List.of("busy", "single", "idle"));
        names.sort(Comparator.comparingDouble(name -> BackendLoad.load(Optional.of(stats.get(name)))));
        assertThat(names).containsExactly("idle", "single", "busy");
    }

    @Test
    void runningQueriesWithoutReservedMemoryAddLoad()
    {
        assertThat(BackendLoad.load(stats("a", 0, 5, 0, 100))).isEqualTo(5);
        assertThat(BackendLoad.load(stats("a", 0, 5, 0, 100))).isGreaterThan(BackendLoad.load(stats("b", 0, 0, 0, 100)));
    }

    @Test
    void memoryPressureAddsLoadForEqualRunningCounts()
    {
        assertThat(BackendLoad.load(stats("a", 0, 4, 50, 100))).isGreaterThan(BackendLoad.load(stats("b", 0, 4, 10, 100)));
        assertThat(BackendLoad.load(stats("b", 0, 4, 10, 100))).isGreaterThan(BackendLoad.load(stats("c", 0, 4, 0, 100)));
    }

    @Test
    void knownMemoryNeverGivesLessLoadThanUnknownMemory()
    {
        ClusterStats unknown = ClusterStats.builder("a").queuedQueryCount(2).runningQueryCount(7).build();
        for (long reserved : new long[] {0, 1, 50, 100, 300}) {
            assertThat(BackendLoad.load(stats("b", 2, 7, reserved, 100))).isGreaterThanOrEqualTo(BackendLoad.load(unknown));
        }
    }

    @Test
    void memoryPressureIsClipped()
    {
        assertThat(BackendLoad.memoryPressure(stats("a", 0, 1, 300, 100))).hasValue(1);
        assertThat(BackendLoad.load(stats("a", 2, 5, 300, 100))).isEqualTo(12);
    }

    @Test
    void unknownMemoryDegradesToQueuedPlusRunning()
    {
        ClusterStats noMemory = ClusterStats.builder("a").queuedQueryCount(3).runningQueryCount(4).build();
        assertThat(BackendLoad.memoryPressure(noMemory)).isEmpty();
        assertThat(BackendLoad.load(noMemory)).isEqualTo(7);

        ClusterStats onlyReserved = ClusterStats.builder("a").queuedQueryCount(3).runningQueryCount(4).reservedQueryMemoryBytes(10).build();
        assertThat(BackendLoad.load(onlyReserved)).isEqualTo(7);

        ClusterStats zeroMax = stats("a", 3, 4, 10, 0);
        assertThat(BackendLoad.load(zeroMax)).isEqualTo(7);
    }

    @Test
    void unknownQueryCountsGiveTheHighestLoad()
    {
        ClusterStats unknown = stats("a", 0, 0, 0, 100).toBuilder().externalLoad(Optional.of(ExternalLoad.unknown())).build();
        assertThat(BackendLoad.load(unknown)).isInfinite();
        assertThat(BackendLoad.load(Optional.of(unknown))).isEqualTo(BackendLoad.load(Optional.empty()));
    }

    @Test
    void toBuilderKeepsEveryField()
    {
        ClusterStats stats = ClusterStats.builder("a")
                .runningQueryCount(1)
                .queuedQueryCount(2)
                .numWorkerNodes(3)
                .trinoStatus(io.trino.gateway.ha.clustermonitor.TrinoStatus.PENDING)
                .proxyTo("http://a.example.test")
                .externalUrl("https://a.example.test")
                .routingGroup("group")
                .userQueuedCount(Map.of("user", 4))
                .reservedQueryMemoryBytes(5)
                .maxQueryMemoryBytes(6)
                .externalLoad(Optional.of(ExternalLoad.unknown()))
                .build();
        assertThat(stats.toBuilder().build()).isEqualTo(stats);
    }

    @Test
    void externalLoadReplacesMonitorLoad()
    {
        ClusterStats stats = stats("a", 50, 50, 100, 100).toBuilder()
                .externalLoad(Optional.of(new ExternalLoad(OptionalInt.of(1), OptionalInt.of(8), OptionalLong.empty(), OptionalLong.empty())))
                .build();
        assertThat(BackendLoad.memoryPressure(stats)).isEmpty();
        assertThat(BackendLoad.load(stats)).isEqualTo(9);
        ClusterStats withMemory = stats.toBuilder()
                .externalLoad(Optional.of(new ExternalLoad(OptionalInt.of(1), OptionalInt.of(8), OptionalLong.of(25), OptionalLong.of(100))))
                .build();
        assertThat(BackendLoad.load(withMemory)).isEqualTo(11);
    }

    @Test
    void lowestLoadSortsFirstAndMissingStatsSortLast()
    {
        Map<String, ClusterStats> stats = Map.of(
                "busy", stats("busy", 5, 0, 0, 100),
                "light", stats("light", 0, 2, 5, 100),
                "pressured", stats("pressured", 0, 20, 90, 100));
        List<String> names = new ArrayList<>(List.of("unknown", "pressured", "busy", "light"));
        names.sort(Comparator.comparingDouble(name -> BackendLoad.load(Optional.ofNullable(stats.get(name)))));
        assertThat(names).containsExactly("light", "busy", "pressured", "unknown");
    }

    @Test
    void equalLoadsKeepTheirPriorOrder()
    {
        Map<String, ClusterStats> stats = Map.of(
                "a", stats("a", 1, 0, 0, 100),
                "b", stats("b", 1, 0, 0, 100),
                "c", stats("c", 1, 0, 0, 100));
        List<String> names = new ArrayList<>(List.of("c", "a", "b"));
        names.sort(Comparator.comparingDouble(name -> BackendLoad.load(Optional.ofNullable(stats.get(name)))));
        assertThat(names).containsExactly("c", "a", "b");
    }

    private static ClusterStats stats(String name, int queued, int running, long reserved, long max)
    {
        return ClusterStats.builder(name)
                .queuedQueryCount(queued)
                .runningQueryCount(running)
                .reservedQueryMemoryBytes(reserved)
                .maxQueryMemoryBytes(max)
                .build();
    }
}
