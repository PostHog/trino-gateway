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
import com.google.common.annotations.VisibleForTesting;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpClient.HttpResponseFuture;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler.StringResponse;
import io.airlift.json.ObjectMapperProvider;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.trino.gateway.ha.config.MonitorConfiguration;
import io.trino.gateway.ha.router.BackendLoad;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.airlift.http.client.HeaderNames.CONTENT_TYPE;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Optional load source for load-aware pooled routing. It reads queued queries, running queries, and
 * query memory for all backends from a Prometheus-compatible HTTP API, and writes them into
 * {@link ClusterStats#externalLoad()} of each backend. It never changes the health status or the load
 * fields of the monitor, so other consumers of the statistics see no change.
 * <p>
 * Each refresh sends one instant query for each configured metric, independent of the number of
 * backends. Each series must have a {@code backend} label equal to the backend name.
 * <p>
 * When the source is enabled, it is the only source of load for the refresh:
 * <ul>
 * <li>The queued and running counts are one pair. If either value is not available for a backend,
 * the counts of that backend are unknown, and load-aware routing gives it the highest load.</li>
 * <li>The reserved and maximum memory are one pair. If either value is not available, both are unknown,
 * and running queries count at full weight. Memory from the monitor is never mixed in.</li>
 * <li>Values from an earlier refresh are never kept. A failed query makes its metric unknown for
 * all backends for that refresh.</li>
 * </ul>
 */
public class PrometheusClusterLoadSource
{
    private static final Logger log = Logger.get(PrometheusClusterLoadSource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProvider().get();
    private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final HttpClient httpClient;
    private final Optional<URI> queryUri;
    private final String queuedQueriesQuery;
    private final String runningQueriesQuery;
    private final Optional<String> reservedMemoryQuery;
    private final Optional<String> maxMemoryQuery;
    private final Duration timeout;
    private final AtomicLong lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    @VisibleForTesting
    PrometheusClusterLoadSource(HttpClient httpClient, MonitorConfiguration configuration)
    {
        this(httpClient, configuration, true);
    }

    /**
     * @param loadAwareRoutingEnabled when false, the source is disabled, because nothing reads its values
     */
    public PrometheusClusterLoadSource(HttpClient httpClient, MonitorConfiguration configuration, boolean loadAwareRoutingEnabled)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        String url = nullToEmpty(configuration.getLoadPrometheusUrl());
        queuedQueriesQuery = nullToEmpty(configuration.getLoadQueuedQueriesQuery());
        runningQueriesQuery = nullToEmpty(configuration.getLoadRunningQueriesQuery());
        reservedMemoryQuery = Optional.of(nullToEmpty(configuration.getLoadReservedMemoryQuery())).filter(query -> !query.isEmpty());
        maxMemoryQuery = Optional.of(nullToEmpty(configuration.getLoadMaxMemoryQuery())).filter(query -> !query.isEmpty());
        if (url.isEmpty()) {
            timeout = null;
            queryUri = Optional.empty();
            return;
        }
        timeout = requireNonNull(configuration.getLoadQueryTimeout(), "loadQueryTimeout is null");
        if (queuedQueriesQuery.isEmpty() || runningQueriesQuery.isEmpty()) {
            throw new IllegalArgumentException("loadQueuedQueriesQuery and loadRunningQueriesQuery are required when loadPrometheusUrl is set");
        }
        if (reservedMemoryQuery.isPresent() != maxMemoryQuery.isPresent()) {
            throw new IllegalArgumentException("Set both loadReservedMemoryQuery and loadMaxMemoryQuery, or neither");
        }
        // The refresh waits for the load source, so a long wait would delay health updates.
        if (timeout.compareTo(configuration.getTaskDelay()) >= 0) {
            throw new IllegalArgumentException("loadQueryTimeout must be shorter than taskDelay");
        }
        queryUri = loadAwareRoutingEnabled ? Optional.of(uriBuilderFrom(URI.create(url)).appendPath("/api/v1/query").build()) : Optional.empty();
    }

    public boolean isEnabled()
    {
        return queryUri.isPresent();
    }

    /**
     * Returns the statistics with load from this source. Returns the input unchanged when the source is
     * disabled. Never throws.
     */
    public List<ClusterStats> enrich(List<ClusterStats> stats)
    {
        if (queryUri.isEmpty()) {
            return stats;
        }
        try {
            // Send all queries before waiting, so that the refresh takes at most one timeout.
            List<HttpResponseFuture<StringResponse>> sent = new ArrayList<>();
            Map<String, Double> queuedValues;
            Map<String, Double> runningValues;
            Optional<Map<String, Double>> reservedValues;
            Optional<Map<String, Double>> maxValues;
            try {
                HttpResponseFuture<StringResponse> queued = send(queuedQueriesQuery, sent);
                HttpResponseFuture<StringResponse> running = send(runningQueriesQuery, sent);
                Optional<HttpResponseFuture<StringResponse>> reserved = reservedMemoryQuery.map(query -> send(query, sent));
                Optional<HttpResponseFuture<StringResponse>> max = maxMemoryQuery.map(query -> send(query, sent));
                long deadline = System.nanoTime() + timeout.roundTo(TimeUnit.NANOSECONDS);
                queuedValues = await(queued, deadline, "queued queries");
                runningValues = await(running, deadline, "running queries");
                reservedValues = reserved.map(future -> await(future, deadline, "reserved memory"));
                maxValues = max.map(future -> await(future, deadline, "maximum memory"));
            }
            finally {
                sent.forEach(future -> future.cancel(true));
            }
            List<ClusterStats> enriched = new ArrayList<>(stats.size());
            for (ClusterStats clusterStats : stats) {
                enriched.add(merge(clusterStats, queuedValues, runningValues, reservedValues, maxValues));
            }
            // A query that returned no usable series has already logged its own reason.
            if (!queuedValues.isEmpty() && !runningValues.isEmpty() && !enriched.isEmpty()
                    && enriched.stream().allMatch(clusterStats -> BackendLoad.load(clusterStats) == Double.POSITIVE_INFINITY)) {
                List<String> monitored = stats.stream().map(ClusterStats::clusterId).sorted().toList();
                warn("Load source gave no backend both queued and running values. The backend label must equal the backend name. "
                        + "Monitored: %s; queued: %s; running: %s".formatted(sample(monitored), sample(queuedValues.keySet()), sample(runningValues.keySet())));
            }
            return enriched;
        }
        catch (RuntimeException e) {
            warn("Load source failed: %s".formatted(e));
            try {
                List<ClusterStats> unknown = new ArrayList<>(stats.size());
                for (ClusterStats clusterStats : stats) {
                    unknown.add(clusterStats.toBuilder().externalLoad(Optional.of(ExternalLoad.unknown())).build());
                }
                return unknown;
            }
            catch (RuntimeException secondFailure) {
                return stats;
            }
        }
    }

    private static ClusterStats merge(
            ClusterStats stats,
            Map<String, Double> queued,
            Map<String, Double> running,
            Optional<Map<String, Double>> reserved,
            Optional<Map<String, Double>> max)
    {
        String backend = stats.clusterId();
        Double queuedValue = queued.get(backend);
        Double runningValue = running.get(backend);
        boolean countsKnown = queuedValue != null && runningValue != null;
        Double reservedValue = reserved.map(values -> values.get(backend)).orElse(null);
        Double maxValue = max.map(values -> values.get(backend)).orElse(null);
        boolean memoryKnown = reservedValue != null && maxValue != null;
        ExternalLoad load = new ExternalLoad(
                countsKnown ? OptionalInt.of(toCount(queuedValue)) : OptionalInt.empty(),
                countsKnown ? OptionalInt.of(toCount(runningValue)) : OptionalInt.empty(),
                memoryKnown ? OptionalLong.of(toBytes(reservedValue)) : OptionalLong.empty(),
                memoryKnown ? OptionalLong.of(toBytes(maxValue)) : OptionalLong.empty());
        return stats.toBuilder().externalLoad(Optional.of(load)).build();
    }

    private static int toCount(double value)
    {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    private static long toBytes(double value)
    {
        return (long) Math.min(Long.MAX_VALUE, value);
    }

    private HttpResponseFuture<StringResponse> send(String query, List<HttpResponseFuture<StringResponse>> sent)
    {
        // Send the query as a form body. In a URL query string, a raw '+' (as in the regex "(.+)") is
        // decoded as a space by Prometheus-compatible servers, which silently changes the query.
        Request request = preparePost()
                .setUri(queryUri.orElseThrow())
                .setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded")
                .setBodyGenerator(createStaticBodyGenerator("query=" + URLEncoder.encode(query, UTF_8), UTF_8))
                .build();
        HttpResponseFuture<StringResponse> future = httpClient.executeAsync(request, createStringResponseHandler());
        sent.add(future);
        return future;
    }

    private Map<String, Double> await(HttpResponseFuture<StringResponse> future, long deadlineNanos, String metric)
    {
        try {
            StringResponse response = future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.getStatusCode() != 200) {
                warn("Load source query for %s returned HTTP %s".formatted(metric, response.getStatusCode()));
                return Map.of();
            }
            Optional<Vector> parsed = parse(response.getBody());
            if (parsed.isEmpty()) {
                warn("Load source query for %s returned an unexpected response".formatted(metric));
                return Map.of();
            }
            Vector vector = parsed.orElseThrow();
            if (vector.seriesCount() == 0) {
                warn("Load source query for %s returned no series".formatted(metric));
            }
            else if (vector.values().isEmpty()) {
                warn("Load source query for %s returned %s series and no usable value: %s without a backend label, the rest with an invalid or duplicate value"
                        .formatted(metric, vector.seriesCount(), vector.seriesWithoutBackend()));
            }
            return vector.values();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Map.of();
        }
        catch (Exception e) {
            future.cancel(true);
            warn("Load source query for %s failed: %s".formatted(metric, e));
            return Map.of();
        }
    }

    @VisibleForTesting
    record Vector(Map<String, Double> values, int seriesCount, int seriesWithoutBackend) {}

    /**
     * Parses an instant vector response into a value for each {@code backend} label, with the number of
     * series and of series without that label. Returns empty when
     * the response is not a successful vector. A backend with more than one series, or with a value that
     * is not a finite number at least zero, is left out.
     */
    @VisibleForTesting
    static Optional<Vector> parse(String body)
    {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        }
        catch (Exception e) {
            return Optional.empty();
        }
        if (root == null
                || !"success".equals(root.path("status").asText())
                || !"vector".equals(root.path("data").path("resultType").asText())
                || !root.path("data").path("result").isArray()) {
            return Optional.empty();
        }
        Map<String, Double> values = new HashMap<>();
        Set<String> rejected = new HashSet<>();
        int seriesCount = 0;
        int seriesWithoutBackend = 0;
        for (JsonNode series : root.path("data").path("result")) {
            seriesCount++;
            JsonNode backendNode = series.path("metric").path("backend");
            if (!backendNode.isTextual() || isNullOrEmpty(backendNode.asText())) {
                seriesWithoutBackend++;
                continue;
            }
            String backend = backendNode.asText();
            OptionalDouble value = sampleValue(series.path("value"));
            if (value.isEmpty() || values.containsKey(backend) || rejected.contains(backend)) {
                rejected.add(backend);
                values.remove(backend);
                continue;
            }
            values.put(backend, value.orElseThrow());
        }
        return Optional.of(new Vector(Map.copyOf(values), seriesCount, seriesWithoutBackend));
    }

    private static OptionalDouble sampleValue(JsonNode value)
    {
        if (!value.isArray() || value.size() != 2 || !value.get(1).isTextual()) {
            return OptionalDouble.empty();
        }
        try {
            double number = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(number) && number >= 0 ? OptionalDouble.of(number) : OptionalDouble.empty();
        }
        catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static String sample(Collection<String> names)
    {
        List<String> sorted = names.stream().sorted().toList();
        return sorted.size() <= 5 ? sorted.toString() : "%s (of %s)".formatted(sorted.subList(0, 5), sorted.size());
    }

    private void warn(String message)
    {
        long now = System.nanoTime();
        long last = lastWarnNanos.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
            log.warn("%s", message);
        }
        else {
            log.debug("%s", message);
        }
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value.trim();
    }
}
