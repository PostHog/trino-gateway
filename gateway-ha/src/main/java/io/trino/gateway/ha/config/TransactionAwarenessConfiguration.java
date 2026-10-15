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
package io.trino.gateway.ha.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY;
import static java.nio.charset.StandardCharsets.UTF_8;

public class TransactionAwarenessConfiguration
{
    private boolean enabled;
    private String identityKey;
    private String adminToken;
    private int terminalRetentionSeconds = 120;
    private int maxInFlightRequests = 16;
    private int completionThreads = 4;
    private int requestTimeoutMillis = 120000;
    private int processInfoTimeoutMillis = 5000;
    private int completionTimeoutMillis = 10000;
    private int ledgerRetentionDays = 7;
    private int ledgerRetentionBatchSize = 100;
    private int ledgerRetentionBatchPauseMillis = 500;
    private PoolLifecycleConfiguration pool = new PoolLifecycleConfiguration();

    public PoolLifecycleConfiguration getPool()
    {
        return pool;
    }

    public void setPool(PoolLifecycleConfiguration pool)
    {
        this.pool = pool == null ? new PoolLifecycleConfiguration() : pool;
    }

    public int getMaxInFlightRequests()
    {
        return maxInFlightRequests;
    }

    public void setMaxInFlightRequests(int maxInFlightRequests)
    {
        this.maxInFlightRequests = maxInFlightRequests;
    }

    public int getCompletionThreads()
    {
        return completionThreads;
    }

    public void setCompletionThreads(int completionThreads)
    {
        this.completionThreads = completionThreads;
    }

    public int getRequestTimeoutMillis()
    {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis)
    {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public int getProcessInfoTimeoutMillis()
    {
        return processInfoTimeoutMillis;
    }

    public void setProcessInfoTimeoutMillis(int processInfoTimeoutMillis)
    {
        this.processInfoTimeoutMillis = processInfoTimeoutMillis;
    }

    public int getCompletionTimeoutMillis()
    {
        return completionTimeoutMillis;
    }

    public void setCompletionTimeoutMillis(int completionTimeoutMillis)
    {
        this.completionTimeoutMillis = completionTimeoutMillis;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    @JsonProperty(access = WRITE_ONLY)
    public String getIdentityKey()
    {
        return identityKey;
    }

    public void setIdentityKey(String identityKey)
    {
        this.identityKey = identityKey;
    }

    @JsonProperty(access = WRITE_ONLY)
    public String getAdminToken()
    {
        return adminToken;
    }

    public void setAdminToken(String adminToken)
    {
        this.adminToken = adminToken;
    }

    /**
     * Days settled ledger history is kept after its terminal retry window or rejection. Zero
     * disables ledger retention; obligations are never deleted, whatever this value is.
     */
    public int getLedgerRetentionDays()
    {
        return ledgerRetentionDays;
    }

    public void setLedgerRetentionDays(int ledgerRetentionDays)
    {
        this.ledgerRetentionDays = ledgerRetentionDays;
    }

    public int getLedgerRetentionBatchSize()
    {
        return ledgerRetentionBatchSize;
    }

    public void setLedgerRetentionBatchSize(int ledgerRetentionBatchSize)
    {
        this.ledgerRetentionBatchSize = ledgerRetentionBatchSize;
    }

    public int getLedgerRetentionBatchPauseMillis()
    {
        return ledgerRetentionBatchPauseMillis;
    }

    public void setLedgerRetentionBatchPauseMillis(int ledgerRetentionBatchPauseMillis)
    {
        this.ledgerRetentionBatchPauseMillis = ledgerRetentionBatchPauseMillis;
    }

    public int getTerminalRetentionSeconds()
    {
        return terminalRetentionSeconds;
    }

    public void setTerminalRetentionSeconds(int terminalRetentionSeconds)
    {
        this.terminalRetentionSeconds = terminalRetentionSeconds;
    }

    /**
     * Validates against the default routing configuration. Callers that own a routing configuration
     * must use {@link #validate(DataStoreConfiguration, RoutingConfiguration)} instead, because the
     * pooled tenant restriction depends on forwarded-header handling.
     */
    public void validate(DataStoreConfiguration dataStore)
    {
        validate(dataStore, new RoutingConfiguration());
    }

    public void validate(DataStoreConfiguration dataStore, RoutingConfiguration routing)
    {
        pool.validate(enabled, routing == null || routing.isForwardedHeadersEnabled());
        if (!enabled) {
            return;
        }
        if (!pool.isEnabled()) {
            throw new IllegalArgumentException("Gateway requires pooled member lifecycle");
        }
        if (dataStore == null || dataStore.getJdbcUrl() == null || !dataStore.getJdbcUrl().startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Transaction awareness requires PostgreSQL");
        }
        if (identityKey == null || identityKey.getBytes(UTF_8).length < 32) {
            throw new IllegalArgumentException("Transaction awareness requires a shared identityKey of at least 32 bytes");
        }
        if (adminToken == null || adminToken.getBytes(UTF_8).length < 32 || adminToken.equals(identityKey)) {
            throw new IllegalArgumentException("Transaction awareness requires a separate adminToken of at least 32 bytes");
        }
        if (terminalRetentionSeconds < 1 || terminalRetentionSeconds > 86400) {
            throw new IllegalArgumentException("terminalRetentionSeconds must be between 1 and 86400");
        }
        if (ledgerRetentionDays < 0 || ledgerRetentionDays > 3650 || ledgerRetentionBatchSize < 1 || ledgerRetentionBatchSize > 500
                || ledgerRetentionBatchPauseMillis < 0 || ledgerRetentionBatchPauseMillis > 60000) {
            throw new IllegalArgumentException("Ledger retention needs 0 to 3650 days, a batch size of 1 to 500 and a pause of at most 60000 ms");
        }
        if (maxInFlightRequests < 1 || maxInFlightRequests > 10000 || completionThreads < 1 || completionThreads > maxInFlightRequests) {
            throw new IllegalArgumentException("Completion threads must be between 1 and maxInFlightRequests, which must not exceed 10000");
        }
        if (requestTimeoutMillis < 1 || requestTimeoutMillis > 3600000 || processInfoTimeoutMillis < 1 || processInfoTimeoutMillis > requestTimeoutMillis || completionTimeoutMillis < 1 || completionTimeoutMillis > 60000) {
            throw new IllegalArgumentException("Request, process probe, and completion timeouts must have bounded positive values");
        }
    }
}
