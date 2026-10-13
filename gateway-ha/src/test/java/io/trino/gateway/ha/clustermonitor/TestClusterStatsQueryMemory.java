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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Response;
import io.airlift.http.client.testing.TestingHttpClient;
import io.airlift.http.client.testing.TestingResponse;
import io.trino.gateway.ha.config.BackendStateConfiguration;
import io.trino.gateway.ha.config.MonitorConfiguration;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

final class TestClusterStatsQueryMemory
{
    private static final String METRICS =
            """
            # TYPE trino_execution_name_QueryManager_RunningQueries gauge
            trino_execution_name_QueryManager_RunningQueries 7.0
            trino_execution_name_QueryManager_QueuedQueries 2.0
            trino_metadata_name_DiscoveryNodeManager_ActiveNodeCount 3.0
            example_reserved_bytes 6.0E9
            example_total_bytes 1.2E10
            """;

    @Test
    void metricsMonitorReadsConfiguredMemoryMetrics()
    {
        ClusterStats stats = metricsMonitor("example_reserved_bytes", "example_total_bytes").monitor(backend());
        assertThat(stats.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(stats.runningQueryCount()).isEqualTo(7);
        assertThat(stats.queuedQueryCount()).isEqualTo(2);
        assertThat(stats.reservedQueryMemoryBytes()).isEqualTo(OptionalLong.of(6_000_000_000L));
        assertThat(stats.maxQueryMemoryBytes()).isEqualTo(OptionalLong.of(12_000_000_000L));
    }

    @Test
    void metricsMonitorTreatsMissingMemoryMetricsAsUnknown()
    {
        ClusterStats stats = metricsMonitor("absent_reserved_bytes", "absent_total_bytes").monitor(backend());
        assertThat(stats.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(stats.reservedQueryMemoryBytes()).isEmpty();
        assertThat(stats.maxQueryMemoryBytes()).isEmpty();
    }

    @Test
    void metricsMonitorWithoutMemoryMetricNamesLeavesMemoryUnknown()
    {
        ClusterStats stats = metricsMonitor(null, null).monitor(backend());
        assertThat(stats.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(stats.reservedQueryMemoryBytes()).isEmpty();
    }

    @Test
    void jmxMemoryPoolResponseIsParsedAsBytes()
            throws Exception
    {
        ClusterStats.Builder builder = ClusterStats.builder("a");
        ClusterStatsJmxMonitor.updateClusterStatsFromMemoryPoolResponse(json(
                """
                {"attributes": [
                  {"name": "ReservedDistributedBytes", "value": 5000000000},
                  {"name": "TotalDistributedBytes", "value": 20000000000},
                  {"name": "Other", "value": "text"}
                ]}
                """), builder);
        ClusterStats stats = builder.build();
        assertThat(stats.reservedQueryMemoryBytes()).isEqualTo(OptionalLong.of(5_000_000_000L));
        assertThat(stats.maxQueryMemoryBytes()).isEqualTo(OptionalLong.of(20_000_000_000L));
    }

    @Test
    void incompleteJmxMemoryPoolResponseLeavesMemoryUnknown()
            throws Exception
    {
        for (String response : List.of(
                "{\"attributes\": [{\"name\": \"ReservedDistributedBytes\", \"value\": 5}]}",
                "{\"attributes\": [{\"name\": \"ReservedDistributedBytes\", \"value\": 5}, {\"name\": \"TotalDistributedBytes\", \"value\": 1.5}]}",
                "{}")) {
            ClusterStats.Builder builder = ClusterStats.builder("a");
            ClusterStatsJmxMonitor.updateClusterStatsFromMemoryPoolResponse(json(response), builder);
            assertThat(builder.build().reservedQueryMemoryBytes()).isEmpty();
            assertThat(builder.build().maxQueryMemoryBytes()).isEmpty();
        }
    }

    @Test
    void jmxMonitorReadsMemoryOnlyWhenEnabled()
    {
        List<String> requested = new ArrayList<>();
        HttpClient client = new TestingHttpClient(request -> {
            String path = request.getUri().getPath();
            requested.add(path);
            if (path.contains("DiscoveryNodeManager")) {
                return json200("{\"attributes\": [{\"name\": \"ActiveNodeCount\", \"value\": 2}]}");
            }
            if (path.contains("QueryManager")) {
                return json200("{\"attributes\": [{\"name\": \"RunningQueries\", \"value\": 3}, {\"name\": \"QueuedQueries\", \"value\": 1}]}");
            }
            return json200("{\"attributes\": [{\"name\": \"ReservedDistributedBytes\", \"value\": 4}, {\"name\": \"TotalDistributedBytes\", \"value\": 8}]}");
        });
        BackendStateConfiguration backendState = new BackendStateConfiguration();
        backendState.setUsername("test_user");

        ClusterStats disabled = new ClusterStatsJmxMonitor(client, backendState).monitor(backend());
        assertThat(disabled.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(disabled.reservedQueryMemoryBytes()).isEmpty();
        assertThat(requested).hasSize(2).noneMatch(path -> path.contains("ClusterMemoryPool"));

        ClusterStats enabled = new ClusterStatsJmxMonitor(client, backendState, true).monitor(backend());
        assertThat(enabled.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(enabled.reservedQueryMemoryBytes()).isEqualTo(OptionalLong.of(4));
        assertThat(enabled.maxQueryMemoryBytes()).isEqualTo(OptionalLong.of(8));
    }

    @Test
    void jmxMemoryFailureKeepsTheBackendHealthy()
    {
        HttpClient client = new TestingHttpClient(request -> {
            String path = request.getUri().getPath();
            if (path.contains("DiscoveryNodeManager")) {
                return json200("{\"attributes\": [{\"name\": \"ActiveNodeCount\", \"value\": 2}]}");
            }
            if (path.contains("QueryManager")) {
                return json200("{\"attributes\": []}");
            }
            return TestingResponse.mockResponse(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "error");
        });
        BackendStateConfiguration backendState = new BackendStateConfiguration();
        backendState.setUsername("test_user");
        ClusterStats stats = new ClusterStatsJmxMonitor(client, backendState, true).monitor(backend());
        assertThat(stats.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(stats.reservedQueryMemoryBytes()).isEmpty();
    }

    private static Response json200(String body)
    {
        return TestingResponse.mockResponse(HttpStatus.OK, MediaType.JSON_UTF_8, body);
    }

    private static ClusterStatsMetricsMonitor metricsMonitor(String reservedName, String maxName)
    {
        HttpClient client = new TestingHttpClient(_ -> TestingResponse.mockResponse(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, METRICS));
        BackendStateConfiguration backendState = new BackendStateConfiguration();
        backendState.setUsername("test_user");
        MonitorConfiguration monitor = new MonitorConfiguration();
        monitor.setReservedQueryMemoryMetricName(reservedName);
        monitor.setMaxQueryMemoryMetricName(maxName);
        return new ClusterStatsMetricsMonitor(client, backendState, monitor);
    }

    private static ProxyBackendConfiguration backend()
    {
        ProxyBackendConfiguration backend = new ProxyBackendConfiguration();
        backend.setName("test_cluster");
        backend.setProxyTo("http://localhost:8080");
        return backend;
    }

    private static JsonNode json(String value)
            throws Exception
    {
        return new ObjectMapper().readTree(value);
    }
}
