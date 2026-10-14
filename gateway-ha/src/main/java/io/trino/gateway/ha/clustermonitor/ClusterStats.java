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

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

public record ClusterStats(
        String clusterId,
        int runningQueryCount,
        int queuedQueryCount,
        int numWorkerNodes,
        TrinoStatus trinoStatus,
        String proxyTo,
        String externalUrl,
        String routingGroup,
        Map<String, Integer> userQueuedCount,
        OptionalLong reservedQueryMemoryBytes,
        OptionalLong maxQueryMemoryBytes,
        Optional<ExternalLoad> externalLoad)
{
    public ClusterStats
    {
        reservedQueryMemoryBytes = requireNonNullElse(reservedQueryMemoryBytes, OptionalLong.empty());
        maxQueryMemoryBytes = requireNonNullElse(maxQueryMemoryBytes, OptionalLong.empty());
        externalLoad = requireNonNullElse(externalLoad, Optional.empty());
    }

    public static Builder builder(String clusterId)
    {
        return new Builder(clusterId);
    }

    public Builder toBuilder()
    {
        Builder builder = new Builder(clusterId)
                .runningQueryCount(runningQueryCount)
                .queuedQueryCount(queuedQueryCount)
                .numWorkerNodes(numWorkerNodes)
                .trinoStatus(trinoStatus)
                .proxyTo(proxyTo)
                .externalUrl(externalUrl)
                .routingGroup(routingGroup)
                .externalLoad(externalLoad);
        builder.userQueuedCount = userQueuedCount;
        builder.reservedQueryMemoryBytes = reservedQueryMemoryBytes;
        builder.maxQueryMemoryBytes = maxQueryMemoryBytes;
        return builder;
    }

    public static final class Builder
    {
        private final String clusterId;
        private int runningQueryCount;
        private int queuedQueryCount;
        private int numWorkerNodes;
        private TrinoStatus trinoStatus = TrinoStatus.UNKNOWN;
        private String proxyTo;
        private String externalUrl;
        private String routingGroup;
        private Map<String, Integer> userQueuedCount;
        private OptionalLong reservedQueryMemoryBytes = OptionalLong.empty();
        private OptionalLong maxQueryMemoryBytes = OptionalLong.empty();
        private Optional<ExternalLoad> externalLoad = Optional.empty();

        private Builder(String clusterId)
        {
            this.clusterId = requireNonNull(clusterId, "clusterId is null");
        }

        public Builder runningQueryCount(int runningQueryCount)
        {
            this.runningQueryCount = runningQueryCount;
            return this;
        }

        public Builder queuedQueryCount(int queuedQueryCount)
        {
            this.queuedQueryCount = queuedQueryCount;
            return this;
        }

        public Builder numWorkerNodes(int numWorkerNodes)
        {
            this.numWorkerNodes = numWorkerNodes;
            return this;
        }

        public Builder trinoStatus(TrinoStatus trinoStatus)
        {
            this.trinoStatus = trinoStatus;
            return this;
        }

        public Builder proxyTo(String proxyTo)
        {
            this.proxyTo = proxyTo;
            return this;
        }

        public Builder externalUrl(String externalUrl)
        {
            this.externalUrl = externalUrl;
            return this;
        }

        public Builder routingGroup(String routingGroup)
        {
            this.routingGroup = routingGroup;
            return this;
        }

        public Builder userQueuedCount(Map<String, Integer> userQueuedCount)
        {
            this.userQueuedCount = ImmutableMap.copyOf(userQueuedCount);
            return this;
        }

        public Builder reservedQueryMemoryBytes(long reservedQueryMemoryBytes)
        {
            this.reservedQueryMemoryBytes = OptionalLong.of(reservedQueryMemoryBytes);
            return this;
        }

        public Builder maxQueryMemoryBytes(long maxQueryMemoryBytes)
        {
            this.maxQueryMemoryBytes = OptionalLong.of(maxQueryMemoryBytes);
            return this;
        }

        public Builder externalLoad(Optional<ExternalLoad> externalLoad)
        {
            this.externalLoad = requireNonNull(externalLoad, "externalLoad is null");
            return this;
        }

        public ClusterStats build()
        {
            return new ClusterStats(
                    clusterId,
                    runningQueryCount,
                    queuedQueryCount,
                    numWorkerNodes,
                    trinoStatus,
                    proxyTo,
                    externalUrl,
                    routingGroup,
                    userQueuedCount,
                    reservedQueryMemoryBytes,
                    maxQueryMemoryBytes,
                    externalLoad);
        }
    }
}
