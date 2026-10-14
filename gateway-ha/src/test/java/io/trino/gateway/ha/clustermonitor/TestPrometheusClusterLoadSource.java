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
import io.trino.gateway.ha.router.BackendLoad;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static io.trino.gateway.ha.clustermonitor.FakePrometheus.json;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestPrometheusClusterLoadSource
{
    private FakePrometheus prometheus;
    private HttpClient httpClient;

    @BeforeEach
    void setUp()
    {
        prometheus = new FakePrometheus();
        httpClient = new JettyHttpClient(new HttpClientConfig());
    }

    @AfterEach
    void tearDown()
            throws Exception
    {
        httpClient.close();
        prometheus.close();
    }

    @Test
    void enrichesEachBackendFromItsSeriesAndKeepsHealth()
    {
        prometheus.vector("queued", Map.of("b1", "3", "b2", "0"));
        prometheus.vector("running", Map.of("b1", "10", "b2", "4"));
        prometheus.vector("reserved", Map.of("b1", "2.5E9", "b2", "9000"));
        prometheus.vector("max", Map.of("b1", "1.0E10", "b2", "10000"));

        List<ClusterStats> stats = source(configuration(true)).enrich(List.of(
                health("b1", TrinoStatus.HEALTHY),
                health("b2", TrinoStatus.UNHEALTHY)));

        ClusterStats b1 = stats.get(0);
        assertThat(b1.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
        assertThat(b1.externalLoad()).contains(new ExternalLoad(OptionalInt.of(3), OptionalInt.of(10), OptionalLong.of(2_500_000_000L), OptionalLong.of(10_000_000_000L)));
        // The monitor fields stay unchanged, so other consumers of the statistics see no change.
        assertThat(b1.queuedQueryCount()).isZero();
        assertThat(b1.runningQueryCount()).isZero();
        assertThat(b1.reservedQueryMemoryBytes()).isEmpty();
        assertThat(b1.proxyTo()).isEqualTo("http://b1.example.test");
        assertThat(BackendLoad.load(b1)).isEqualTo(3 + 10 * 0.25);
        ClusterStats b2 = stats.get(1);
        assertThat(b2.trinoStatus()).isEqualTo(TrinoStatus.UNHEALTHY);
        assertThat(BackendLoad.load(b2)).isEqualTo(0 + 4 * 0.9);
    }

    @Test
    void requestCountPerRefreshIsIndependentOfBackendCount()
    {
        PrometheusClusterLoadSource source = source(configuration(true));
        source.enrich(List.of(health("b1", TrinoStatus.HEALTHY)));
        assertThat(prometheus.requestCount()).isEqualTo(4);
        source.enrich(IntStream.range(0, 50).mapToObj(i -> health("b" + i, TrinoStatus.HEALTHY)).toList());
        assertThat(prometheus.requestCount()).isEqualTo(8);
        source(configuration(false)).enrich(IntStream.range(0, 50).mapToObj(i -> health("b" + i, TrinoStatus.HEALTHY)).toList());
        assertThat(prometheus.requestCount()).isEqualTo(10);
    }

    @Test
    void disabledSourceSendsNoRequestsAndReturnsTheInput()
    {
        MonitorConfiguration configuration = configuration(true);
        configuration.setLoadPrometheusUrl("");
        PrometheusClusterLoadSource source = source(configuration);
        List<ClusterStats> input = List.of(health("b1", TrinoStatus.HEALTHY));
        assertThat(source.isEnabled()).isFalse();
        assertThat(source.enrich(input)).isSameAs(input);
        assertThat(source(new MonitorConfiguration()).enrich(input)).isSameAs(input);
        assertThat(prometheus.requestCount()).isZero();
    }

    @Test
    void missingBackendGivesUnknownLoad()
    {
        prometheus.vector("queued", Map.of("b1", "1"));
        prometheus.vector("running", Map.of("b1", "1", "b2", "1"));
        List<ClusterStats> stats = source(configuration(false)).enrich(List.of(health("b1", TrinoStatus.HEALTHY), health("b2", TrinoStatus.HEALTHY)));
        assertThat(BackendLoad.load(stats.get(0))).isEqualTo(2);
        assertThat(stats.get(1).externalLoad()).contains(ExternalLoad.unknown());
        assertThat(BackendLoad.load(stats.get(1))).isInfinite();
        assertThat(stats.get(1).trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
    }

    @Test
    void failedRequestGivesUnknownLoadAndKeepsHealth()
    {
        prometheus.respond("queued", () -> new MockResponse().setResponseCode(503).setBody("unavailable"));
        prometheus.vector("running", Map.of("b1", "1"));
        assertUnknownLoad(source(configuration(false)).enrich(List.of(healthOnly("b1", TrinoStatus.HEALTHY).queuedQueryCount(7).build())));
    }

    @Test
    void malformedResponsesGiveUnknownLoad()
    {
        prometheus.vector("running", Map.of("b1", "1"));
        for (String body : List.of(
                "not json",
                "",
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"backend\":\"b1\"},\"values\":[[1,\"1\"]]}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"backend\":\"b1\"},\"value\":[1,\"NaN\"]}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"backend\":\"b1\"},\"value\":[1,\"+Inf\"]}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"backend\":\"b1\"},\"value\":[1,\"-1\"]}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"backend\":\"b1\"},\"value\":[1,3]}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"backend\":\"b1\"}}]}}",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"backend\":\"b1\",\"pod\":\"a\"},\"value\":[1,\"1\"]},"
                        + "{\"metric\":{\"backend\":\"b1\",\"pod\":\"b\"},\"value\":[1,\"2\"]}]}}")) {
            prometheus.respond("queued", () -> json(body));
            assertUnknownLoad(source(configuration(false)).enrich(List.of(healthOnly("b1", TrinoStatus.HEALTHY).queuedQueryCount(7).build())));
        }
    }

    @Test
    void duplicateSeriesDoNotAffectOtherBackends()
    {
        prometheus.respond("queued", () -> json("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"backend\":\"b1\"},\"value\":[1,\"1\"]},{\"metric\":{\"backend\":\"b1\"},\"value\":[1,\"1\"]},"
                + "{\"metric\":{\"backend\":\"b1\"},\"value\":[1,\"1\"]},{\"metric\":{\"backend\":\"b2\"},\"value\":[1,\"5\"]},{\"metric\":{},\"value\":[1,\"1\"]}]}}"));
        prometheus.vector("running", Map.of("b1", "1", "b2", "1"));
        List<ClusterStats> stats = source(configuration(false)).enrich(List.of(health("b1", TrinoStatus.HEALTHY), health("b2", TrinoStatus.HEALTHY)));
        assertThat(BackendLoad.load(stats.get(0))).isInfinite();
        assertThat(BackendLoad.load(stats.get(1))).isEqualTo(6);
    }

    @Test
    void timeoutGivesUnknownLoadWithinOneTimeout()
    {
        prometheus.respond("queued", () -> FakePrometheus.vectorResponse(Map.of("b1", "1")).setHeadersDelay(5, SECONDS));
        prometheus.respond("running", () -> FakePrometheus.vectorResponse(Map.of("b1", "1")).setHeadersDelay(5, SECONDS));
        MonitorConfiguration configuration = configuration(false);
        configuration.setLoadQueryTimeout(new Duration(300, MILLISECONDS));
        long start = System.nanoTime();
        assertUnknownLoad(source(configuration).enrich(List.of(healthOnly("b1", TrinoStatus.HEALTHY).queuedQueryCount(7).build())));
        assertThat(System.nanoTime() - start).isLessThan(SECONDS.toNanos(3));
    }

    @Test
    void partialMemoryIsClearedAndRunningCountsAtFullWeight()
    {
        prometheus.vector("queued", Map.of("b1", "1"));
        prometheus.vector("running", Map.of("b1", "4"));
        prometheus.vector("reserved", Map.of("b1", "50"));
        prometheus.respond("max", () -> new MockResponse().setResponseCode(500));
        // Memory from the monitor is never mixed with the load source.
        ClusterStats input = health("b1", TrinoStatus.HEALTHY).toBuilder().reservedQueryMemoryBytes(1).maxQueryMemoryBytes(100).build();
        ClusterStats stats = source(configuration(true)).enrich(List.of(input)).getFirst();
        assertThat(stats.externalLoad().orElseThrow().reservedQueryMemoryBytes()).isEmpty();
        assertThat(stats.externalLoad().orElseThrow().maxQueryMemoryBytes()).isEmpty();
        assertThat(BackendLoad.load(stats)).isEqualTo(5);
        ClusterStats withoutMemoryQueries = source(configuration(false)).enrich(List.of(input)).getFirst();
        assertThat(BackendLoad.load(withoutMemoryQueries)).isEqualTo(5);
    }

    @Test
    void unreachableSourceGivesUnknownLoad()
            throws Exception
    {
        MonitorConfiguration configuration = configuration(true);
        prometheus.close();
        assertUnknownLoad(source(configuration).enrich(List.of(healthOnly("b1", TrinoStatus.HEALTHY).queuedQueryCount(7).build())));
    }

    @Test
    void incompleteConfigurationIsRejected()
    {
        MonitorConfiguration noCounts = configuration(false);
        noCounts.setLoadRunningQueriesQuery("");
        assertThatThrownBy(() -> source(noCounts)).isInstanceOf(IllegalArgumentException.class);
        MonitorConfiguration oneMemory = configuration(false);
        oneMemory.setLoadReservedMemoryQuery("reserved");
        assertThatThrownBy(() -> source(oneMemory)).isInstanceOf(IllegalArgumentException.class);
        MonitorConfiguration longTimeout = configuration(false);
        longTimeout.setTaskDelay(new Duration(5, SECONDS));
        assertThatThrownBy(() -> source(longTimeout)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceIsDisabledWhenLoadAwareRoutingIsOff()
    {
        PrometheusClusterLoadSource source = new PrometheusClusterLoadSource(httpClient, configuration(true), false);
        List<ClusterStats> input = List.of(health("b1", TrinoStatus.HEALTHY));
        assertThat(source.isEnabled()).isFalse();
        assertThat(source.enrich(input)).isSameAs(input);
        assertThat(prometheus.requestCount()).isZero();
    }

    private static void assertUnknownLoad(List<ClusterStats> stats)
    {
        ClusterStats only = stats.getFirst();
        assertThat(only.externalLoad().orElseThrow().queuedQueryCount().isEmpty() || only.externalLoad().orElseThrow().runningQueryCount().isEmpty()).isTrue();
        assertThat(only.queuedQueryCount()).isEqualTo(7);
        assertThat(BackendLoad.load(only)).isInfinite();
        assertThat(only.trinoStatus()).isEqualTo(TrinoStatus.HEALTHY);
    }

    private PrometheusClusterLoadSource source(MonitorConfiguration configuration)
    {
        return new PrometheusClusterLoadSource(httpClient, configuration);
    }

    private MonitorConfiguration configuration(boolean withMemory)
    {
        MonitorConfiguration configuration = new MonitorConfiguration();
        configuration.setLoadPrometheusUrl(prometheus.baseUrl());
        configuration.setLoadQueuedQueriesQuery("queued");
        configuration.setLoadRunningQueriesQuery("running");
        if (withMemory) {
            configuration.setLoadReservedMemoryQuery("reserved");
            configuration.setLoadMaxMemoryQuery("max");
        }
        return configuration;
    }

    static ClusterStats health(String name, TrinoStatus status)
    {
        return healthOnly(name, status).build();
    }

    static ClusterStats.Builder healthOnly(String name, TrinoStatus status)
    {
        return ClusterStats.builder(name)
                .trinoStatus(status)
                .proxyTo("http://" + name + ".example.test")
                .routingGroup("pool");
    }
}
