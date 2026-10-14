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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

final class TestBackendLoad
{
    @Test
    void loadWeightsRunningQueriesByMemoryPressure()
    {
        ClusterStats stats = stats("a", 4, 10, 25, 100);
        assertThat(BackendLoad.memoryPressure(stats).orElseThrow()).isCloseTo(0.25, within(1e-9));
        assertThat(BackendLoad.load(stats)).isCloseTo(4 + 10 * 0.25, within(1e-9));
    }

    @Test
    void memoryPressureIsClipped()
    {
        assertThat(BackendLoad.memoryPressure(stats("a", 0, 1, 300, 100))).hasValue(1);
        assertThat(BackendLoad.load(stats("a", 2, 5, 300, 100))).isEqualTo(7);
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
    void lowestLoadSortsFirstAndMissingStatsSortLast()
    {
        Map<String, ClusterStats> stats = Map.of(
                "busy", stats("busy", 5, 0, 0, 100),
                "idle", stats("idle", 0, 20, 5, 100),
                "pressured", stats("pressured", 0, 20, 90, 100));
        List<String> names = new ArrayList<>(List.of("unknown", "pressured", "busy", "idle"));
        names.sort(Comparator.comparingDouble(name -> BackendLoad.load(Optional.ofNullable(stats.get(name)))));
        assertThat(names).containsExactly("idle", "busy", "pressured", "unknown");
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
