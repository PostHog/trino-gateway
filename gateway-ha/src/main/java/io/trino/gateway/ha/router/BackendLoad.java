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

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Load of one coordinator: {@code queued + running * memoryPressure}.
 * <p>
 * {@code memoryPressure} is {@code reserved / max} query memory, clipped to {@code [0, 1]}. When the
 * memory statistics are not known, running queries count at full weight, so the load is
 * {@code queued + running}.
 */
public final class BackendLoad
{
    private BackendLoad() {}

    /**
     * Returns the memory pressure, or empty when the memory statistics are not known.
     */
    public static OptionalDouble memoryPressure(ClusterStats stats)
    {
        if (stats.reservedQueryMemoryBytes().isEmpty() || stats.maxQueryMemoryBytes().isEmpty()) {
            return OptionalDouble.empty();
        }
        long max = stats.maxQueryMemoryBytes().orElseThrow();
        if (max <= 0) {
            return OptionalDouble.empty();
        }
        double pressure = (double) stats.reservedQueryMemoryBytes().orElseThrow() / max;
        return OptionalDouble.of(Math.min(1, Math.max(0, pressure)));
    }

    public static double load(ClusterStats stats)
    {
        return Math.max(0, stats.queuedQueryCount()) + Math.max(0, stats.runningQueryCount()) * memoryPressure(stats).orElse(1);
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
