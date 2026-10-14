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

import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpClientConfig;
import io.airlift.http.client.jetty.JettyHttpClient;
import io.airlift.units.Duration;
import io.trino.gateway.ha.config.MonitorConfiguration;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;
import io.trino.gateway.ha.router.GatewayBackendManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class TestActiveClusterMonitorLoadSource
{
    @Test
    void observersReceiveHealthWithExternalLoad()
            throws Exception
    {
        try (FakePrometheus prometheus = new FakePrometheus();
                HttpClient client = new JettyHttpClient(new HttpClientConfig())) {
            prometheus.vector("queued", Map.of("b1", "2"));
            prometheus.vector("running", Map.of("b1", "3"));
            MonitorConfiguration configuration = monitorConfiguration();
            configuration.setLoadPrometheusUrl(prometheus.baseUrl());
            configuration.setLoadQueuedQueriesQuery("queued");
            configuration.setLoadRunningQueriesQuery("running");
            ClusterStats observed = firstObservation(configuration, new PrometheusClusterLoadSource(client, configuration));
            assertThat(observed.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
            assertThat(observed.externalLoad().orElseThrow().queuedQueryCount()).isEqualTo(OptionalInt.of(2));
            assertThat(observed.externalLoad().orElseThrow().runningQueryCount()).isEqualTo(OptionalInt.of(3));
        }
    }

    @Test
    void disabledSourceLeavesObservedStatsUnchanged()
            throws Exception
    {
        try (HttpClient client = new JettyHttpClient(new HttpClientConfig())) {
            MonitorConfiguration configuration = monitorConfiguration();
            ClusterStats observed = firstObservation(configuration, new PrometheusClusterLoadSource(client, configuration));
            assertThat(observed).isEqualTo(monitorStats());
            assertThat(observed.externalLoad()).isEqualTo(Optional.empty());
        }
    }

    private static ClusterStats firstObservation(MonitorConfiguration configuration, PrometheusClusterLoadSource source)
            throws Exception
    {
        ProxyBackendConfiguration backend = new ProxyBackendConfiguration();
        backend.setName("b1");
        backend.setProxyTo("http://b1.example.test");
        GatewayBackendManager backends = mock(GatewayBackendManager.class);
        when(backends.getAllBackends()).thenReturn(List.of(backend));
        PoolMonitoringScope scope = mock(PoolMonitoringScope.class);
        when(scope.monitored(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BlockingQueue<List<ClusterStats>> observations = new LinkedBlockingQueue<>();
        ActiveClusterMonitor monitor = new ActiveClusterMonitor(Set.of(observations::add), backends, configuration, _ -> monitorStats(), scope, source);
        monitor.start();
        try {
            List<ClusterStats> stats = observations.poll(10, SECONDS);
            assertThat(stats).hasSize(1);
            return stats.getFirst();
        }
        finally {
            monitor.stop();
        }
    }

    private static ClusterStats monitorStats()
    {
        return ClusterStats.builder("b1").trinoStatus(TrinoStatus.HEALTHY).proxyTo("http://b1.example.test").build();
    }

    private static MonitorConfiguration monitorConfiguration()
    {
        MonitorConfiguration configuration = new MonitorConfiguration();
        configuration.setTaskDelay(new Duration(60_000, MILLISECONDS));
        return configuration;
    }
}
