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

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Fake Prometheus HTTP API. Each query name maps to a response. Unknown queries return an empty vector.
 */
public final class FakePrometheus
        implements Closeable
{
    private final MockWebServer server = new MockWebServer();
    private final Map<String, Supplier<MockResponse>> responses = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    public FakePrometheus()
    {
        server.setDispatcher(new Dispatcher()
        {
            @Override
            public MockResponse dispatch(RecordedRequest request)
            {
                requests.incrementAndGet();
                if (!"/prometheus/api/v1/query".equals(request.getRequestUrl().encodedPath())) {
                    return new MockResponse().setResponseCode(404);
                }
                String query = request.getRequestUrl().queryParameter("query");
                Supplier<MockResponse> response = responses.get(query);
                return response == null ? vectorResponse(Map.of()) : response.get();
            }
        });
        try {
            server.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl()
    {
        return server.url("/prometheus").toString();
    }

    public void vector(String query, Map<String, String> valueByBackend)
    {
        responses.put(query, () -> vectorResponse(valueByBackend));
    }

    public void respond(String query, Supplier<MockResponse> response)
    {
        responses.put(query, response);
    }

    public int requestCount()
    {
        return requests.get();
    }

    public static MockResponse vectorResponse(Map<String, String> valueByBackend)
    {
        String result = valueByBackend.entrySet().stream()
                .map(entry -> "{\"metric\":{\"backend\":\"%s\",\"job\":\"trino\"},\"value\":[1700000000.123,\"%s\"]}".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(","));
        return json("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + result + "]}}");
    }

    public static MockResponse json(String body)
    {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Override
    public void close()
            throws IOException
    {
        server.shutdown();
    }
}
