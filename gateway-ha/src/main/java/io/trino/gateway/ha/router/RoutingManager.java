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
import io.trino.gateway.ha.clustermonitor.TrinoStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local observations of backend health and load. Pool membership and durable lifecycle state, not
 * this cache, determine routing eligibility. This class never selects or discovers a query owner.
 */
public class RoutingManager
{
    private final Map<String, TrinoStatus> backendToStatus = new HashMap<>();
    private final Map<String, ClusterStats> clusterStats = new HashMap<>();

    public synchronized Optional<TrinoStatus> getBackEndHealth(String backendId)
    {
        return Optional.ofNullable(backendToStatus.get(backendId));
    }

    public synchronized void updateBackEndHealth(String backendId, TrinoStatus value)
    {
        backendToStatus.put(backendId, value);
        // A lifecycle update invalidates the previous load observation until monitoring refreshes it.
        clusterStats.remove(backendId);
    }

    public synchronized void removeBackEndHealth(String backendId)
    {
        backendToStatus.remove(backendId);
        clusterStats.remove(backendId);
    }

    public synchronized void updateClusterStats(List<ClusterStats> stats)
    {
        for (ClusterStats stat : stats) {
            backendToStatus.put(stat.clusterId(), stat.trinoStatus());
            clusterStats.put(stat.clusterId(), stat);
        }
    }

    /**
     * Returns the last monitoring observation for advisory ordering within an eligible pool.
     */
    public synchronized Optional<ClusterStats> getClusterStats(String backendId)
    {
        return Optional.ofNullable(clusterStats.get(backendId));
    }
}
