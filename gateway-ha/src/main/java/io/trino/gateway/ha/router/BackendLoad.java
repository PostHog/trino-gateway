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

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Load of one coordinator: {@code queued + running * (1 + memoryPressure)}.
 * <p>
 * {@code memoryPressure} is {@code reserved / max} query memory, clipped to {@code [0, 1]}. Running
 * queries always count at full weight, because CPU-bound queries reserve little memory. Memory pressure
 * adds up to one more unit per running query. When the memory statistics are not known, the pressure
 * is {@code 0}, so the load is {@code queued + running}. When {@link ClusterStats#externalLoad()} is present, only its values are
 * used, and unknown query counts give {@link Double#POSITIVE_INFINITY}.
 */
public final class BackendLoad
{
    private BackendLoad() {}

    /**
     * Returns the memory pressure, or empty when the memory statistics are not known.
     */
    public static OptionalDouble memoryPressure(ClusterStats stats)
    {
        if (stats.externalLoad().isPresent()) {
            ExternalLoad external = stats.externalLoad().orElseThrow();
            return memoryPressure(external.reservedQueryMemoryBytes(), external.maxQueryMemoryBytes());
        }
        return memoryPressure(stats.reservedQueryMemoryBytes(), stats.maxQueryMemoryBytes());
    }

    private static OptionalDouble memoryPressure(OptionalLong reserved, OptionalLong max)
    {
        if (reserved.isEmpty() || max.isEmpty() || max.orElseThrow() <= 0) {
            return OptionalDouble.empty();
        }
        double pressure = (double) reserved.orElseThrow() / max.orElseThrow();
        return OptionalDouble.of(Math.min(1, Math.max(0, pressure)));
    }

    public static double load(ClusterStats stats)
    {
        int queued = stats.queuedQueryCount();
        int running = stats.runningQueryCount();
        if (stats.externalLoad().isPresent()) {
            // An external load source replaces the load of the monitor. Unknown counts give the highest load.
            ExternalLoad external = stats.externalLoad().orElseThrow();
            if (external.queuedQueryCount().isEmpty() || external.runningQueryCount().isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            queued = external.queuedQueryCount().orElseThrow();
            running = external.runningQueryCount().orElseThrow();
        }
        return Math.max(0, queued) + Math.max(0, running) * (1 + memoryPressure(stats).orElse(0));
    }

    /**
     * Load from optional statistics. Missing statistics give {@link Double#POSITIVE_INFINITY}, so the
     * item sorts after every item with statistics.
     */
    public static double load(Optional<ClusterStats> stats)
    {
        return stats.map(BackendLoad::load).orElse(Double.POSITIVE_INFINITY);
    }
}
