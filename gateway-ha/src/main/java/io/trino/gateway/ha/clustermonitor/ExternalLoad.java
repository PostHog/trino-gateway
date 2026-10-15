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
package io.trino.gateway.ha.clustermonitor;

import java.util.OptionalInt;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

/**
 * Load of one backend from an external load source, for load-aware pooled routing only. When it is
 * present, load-aware routing uses only these values, and ignores the load fields of the monitor. An
 * empty value is not known. Other consumers of {@link ClusterStats} do not read it.
 */
public record ExternalLoad(
        OptionalInt queuedQueryCount,
        OptionalInt runningQueryCount,
        OptionalLong reservedQueryMemoryBytes,
        OptionalLong maxQueryMemoryBytes)
{
    public ExternalLoad
    {
        requireNonNull(queuedQueryCount, "queuedQueryCount is null");
        requireNonNull(runningQueryCount, "runningQueryCount is null");
        requireNonNull(reservedQueryMemoryBytes, "reservedQueryMemoryBytes is null");
        requireNonNull(maxQueryMemoryBytes, "maxQueryMemoryBytes is null");
    }

    public static ExternalLoad unknown()
    {
        return new ExternalLoad(OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty(), OptionalLong.empty());
    }
}
