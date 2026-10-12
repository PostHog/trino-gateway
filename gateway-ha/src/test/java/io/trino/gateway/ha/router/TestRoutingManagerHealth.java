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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class TestRoutingManagerHealth
{
    @Test
    void testUpstreamRoutingAndAdvisoryStatsRemain()
    {
        assertThat(RoutingManager.class.isInterface()).isTrue();
        assertThat(RoutingManager.class.getDeclaredMethods())
                .filteredOn(method -> !method.isSynthetic())
                .extracting(method -> method.getName())
                .contains("getBackEndHealth", "updateBackEndHealth", "removeBackEndHealth", "updateClusterStats", "getClusterStats", "provideBackendConfiguration");
    }

    @Test
    void testUpstreamSelectorsRemainAvailable()
    {
        for (String name : List.of(
                "io.trino.gateway.ha.router.BaseRoutingManager",
                "io.trino.gateway.ha.router.StochasticRoutingManager",
                "io.trino.gateway.ha.router.QueryCountBasedRouter",
                "io.trino.gateway.ha.module.QueryCountBasedRouterProvider")) {
            org.assertj.core.api.Assertions.assertThatCode(() -> Class.forName(name)).doesNotThrowAnyException();
        }
    }

    @Test
    void testHealthIsUnknownUntilObservedAndCanBeRemoved()
            throws ReflectiveOperationException
    {
        RoutingManager manager = manager();
        assertThat(manager.getBackEndHealth("backend-a")).isEmpty();
        manager.updateBackEndHealth("backend-a", TrinoStatus.HEALTHY);
        assertThat(manager.getBackEndHealth("backend-a")).contains(TrinoStatus.HEALTHY);
        manager.updateBackEndHealth("backend-a", TrinoStatus.UNHEALTHY);
        assertThat(manager.getBackEndHealth("backend-a")).contains(TrinoStatus.UNHEALTHY);
        manager.removeBackEndHealth("backend-a");
        assertThat(manager.getBackEndHealth("backend-a")).isEmpty();
    }

    @Test
    void testStatsUpdateHealth()
            throws ReflectiveOperationException
    {
        RoutingManager manager = manager();
        ClusterStats stats = ClusterStats.builder("backend-a").trinoStatus(TrinoStatus.HEALTHY).runningQueryCount(7).queuedQueryCount(2).build();
        manager.updateClusterStats(List.of(stats));
        assertThat(manager.getBackEndHealth("backend-a")).contains(TrinoStatus.HEALTHY);
        assertThat(manager.getClusterStats("backend-a")).contains(stats);
        assertThat(manager.getClusterStats("unknown")).isEmpty();
        manager.removeBackEndHealth("backend-a");
        assertThat(manager.getClusterStats("backend-a")).isEmpty();
        assertThat(manager.getBackEndHealth("backend-a")).isEmpty();
    }

    @Test
    void testLifecycleHealthUpdateInvalidatesOldLoadObservation()
            throws ReflectiveOperationException
    {
        RoutingManager manager = manager();
        manager.updateClusterStats(List.of(ClusterStats.builder("backend-a").trinoStatus(TrinoStatus.HEALTHY).runningQueryCount(7).build()));
        manager.updateBackEndHealth("backend-a", TrinoStatus.PENDING);
        assertThat(manager.getClusterStats("backend-a")).isEmpty();
        assertThat(manager.getBackEndHealth("backend-a")).contains(TrinoStatus.PENDING);
    }

    private static RoutingManager manager()
    {
        return new StochasticRoutingManager(org.mockito.Mockito.mock(GatewayBackendManager.class), org.mockito.Mockito.mock(QueryHistoryManager.class), new io.trino.gateway.ha.config.RoutingConfiguration());
    }
}
