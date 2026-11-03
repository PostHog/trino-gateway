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

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Configuration for the required pooled member lifecycle.
 */
public class PoolLifecycleConfiguration
{
    /**
     * The restriction is absent. Nothing infers a tenant, and a pool cannot enable its admission gate.
     */
    public static final String TENANT_IDENTITY_NONE = "NONE";
    /**
     * The name the coordinator's password authenticator verifies: the Basic credential's user, host
     * qualified exactly as the coordinator qualifies it. The Gateway never verifies the credential
     * itself, and never derives a tenant from the name — the tenant comes from the
     * controller-published principal mapping.
     */
    public static final String TENANT_IDENTITY_TRINO_BASIC_PRINCIPAL = "TRINO_BASIC_PRINCIPAL";

    public static final List<String> TENANT_IDENTITY_SOURCES = List.of(TENANT_IDENTITY_NONE, TENANT_IDENTITY_TRINO_BASIC_PRINCIPAL);

    private static final Pattern CANARY_DOMAIN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");

    private boolean enabled;
    private String tenantIdentitySource = TENANT_IDENTITY_NONE;
    private List<String> hostQualificationDomains = List.of();
    private List<String> excludedHostLabels = List.of();
    private List<String> canaryHostDomains = List.of();
    private int maxPreDispatchCandidates = 3;
    private int certificateFreshnessSeconds = 300;
    private int operationRetentionDays = 30;
    private boolean forwardedProtoHttps;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getTenantIdentitySource()
    {
        return tenantIdentitySource;
    }

    public void setTenantIdentitySource(String tenantIdentitySource)
    {
        this.tenantIdentitySource = tenantIdentitySource;
    }

    /**
     * True only when a verified tenant identity is actually available to the admission path.
     */
    public boolean hasVerifiedTenantIdentity()
    {
        return !TENANT_IDENTITY_NONE.equals(tenantIdentitySource);
    }

    /**
     * Domains under which a coordinator qualifies a Basic user with the routed host's leading label.
     * The restriction must recompute the same string the coordinator authenticates, so these must
     * equal the coordinator's own configured domains whenever the principal source is enabled.
     */
    public List<String> getHostQualificationDomains()
    {
        return hostQualificationDomains;
    }

    public void setHostQualificationDomains(List<String> hostQualificationDomains)
    {
        this.hostQualificationDomains = hostQualificationDomains == null ? List.of() : List.copyOf(hostQualificationDomains);
    }

    /**
     * Host labels under those domains that name something operational rather than a tenant. These must
     * equal the coordinator's own exclusions, so a request to an operational host qualifies the same
     * way on both sides.
     */
    public List<String> getExcludedHostLabels()
    {
        return excludedHostLabels;
    }

    public void setExcludedHostLabels(List<String> excludedHostLabels)
    {
        this.excludedHostLabels = excludedHostLabels == null ? List.of() : List.copyOf(excludedHostLabels);
    }

    /**
     * Domains of the canary lane. A request whose Host is exactly one DNS label under one of them is a
     * canary request, and runs on the pool's canary build when the pool has one. The label only marks
     * the request: a pool is still chosen by an exact {@code hostnameToPool} entry, so those entries are
     * the allowlist, and {@code hostnameSuffixToPool} must not cover a canary domain.
     */
    public List<String> getCanaryHostDomains()
    {
        return canaryHostDomains;
    }

    public void setCanaryHostDomains(List<String> canaryHostDomains)
    {
        this.canaryHostDomains = canaryHostDomains == null ? List.of() : List.copyOf(canaryHostDomains);
    }

    public int getMaxPreDispatchCandidates()
    {
        return maxPreDispatchCandidates;
    }

    public void setMaxPreDispatchCandidates(int maxPreDispatchCandidates)
    {
        this.maxPreDispatchCandidates = maxPreDispatchCandidates;
    }

    public int getCertificateFreshnessSeconds()
    {
        return certificateFreshnessSeconds;
    }

    public void setCertificateFreshnessSeconds(int certificateFreshnessSeconds)
    {
        this.certificateFreshnessSeconds = certificateFreshnessSeconds;
    }

    public int getOperationRetentionDays()
    {
        return operationRetentionDays;
    }

    public void setOperationRetentionDays(int operationRetentionDays)
    {
        this.operationRetentionDays = operationRetentionDays;
    }

    /**
     * Whether a <em>pooled</em> member's identity probe asserts {@code X-Forwarded-Proto: https}.
     * <p>
     * A pooled member is reached over internal plain HTTP while TLS terminates at the Gateway, so a
     * coordinator that processes forwarded headers needs to be told the original protocol. Disabled by
     * default, and it applies only to probes of pooled members: probing of legacy backends is
     * byte-for-byte unchanged whether or not this is set. It relaxes nothing on the coordinator and
     * never substitutes for authentication.
     */
    public boolean isForwardedProtoHttps()
    {
        return forwardedProtoHttps;
    }

    public void setForwardedProtoHttps(boolean forwardedProtoHttps)
    {
        this.forwardedProtoHttps = forwardedProtoHttps;
    }

    /**
     * A suffix mapping for a canary domain would send every host under it to the pool, so any client
     * could reach the canary build by naming such a host. Only exact host entries are an allowlist.
     */
    public void validateHostnameSuffixes(Collection<String> hostnameSuffixes)
    {
        if (!enabled) {
            return;
        }
        for (String suffix : hostnameSuffixes) {
            if (canaryHostDomains.contains(normalizedDomain(suffix))) {
                throw new IllegalArgumentException("hostnameSuffixToPool must not map a canary host domain; list each canary host in hostnameToPool instead");
            }
        }
    }

    private static String normalizedDomain(String domain)
    {
        String normalized = domain.trim().toLowerCase(Locale.ENGLISH);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    public void validate(boolean transactionAwarenessEnabled, boolean forwardedHeadersEnabled)
    {
        if (!enabled) {
            return;
        }
        if (!transactionAwarenessEnabled) {
            throw new IllegalArgumentException("Pooled member lifecycle requires transaction awareness to be enabled");
        }
        if (tenantIdentitySource == null || !TENANT_IDENTITY_SOURCES.contains(tenantIdentitySource)) {
            throw new IllegalArgumentException("tenantIdentitySource must be one of " + TENANT_IDENTITY_SOURCES);
        }
        if (TENANT_IDENTITY_TRINO_BASIC_PRINCIPAL.equals(tenantIdentitySource)) {
            // The restriction decides on the principal the coordinator will authenticate, which depends
            // on the host the coordinator sees. With routing.forwardedHeadersEnabled = false the
            // Gateway sends no X-Forwarded-Host, so the coordinator qualifies with its own internal
            // Service host instead of the client's, and the name checked here is not the name
            // authenticated there. No verified alternative way to carry that host exists, so this is
            // refused at startup rather than decided against the wrong identity.
            if (!forwardedHeadersEnabled) {
                throw new IllegalArgumentException("A tenant principal restriction requires routing.forwardedHeadersEnabled, "
                        + "because the coordinator must qualify the credential with the client's host and not the internal Service host");
            }
            // A coordinator may authenticate an unqualified name, so an empty domain list is valid: the
            // credential itself is then the only candidate. A malformed domain is not.
            for (String domain : hostQualificationDomains) {
                if (domain == null || domain.isBlank() || domain.startsWith(".") || domain.endsWith("..")) {
                    throw new IllegalArgumentException("hostQualificationDomains must contain absolute domains, "
                            + "so the restriction keys on the same principal the coordinator authenticates");
                }
            }
        }
        for (String domain : canaryHostDomains) {
            if (domain == null || !CANARY_DOMAIN.matcher(domain).matches() || domain.length() > 253) {
                throw new IllegalArgumentException("canaryHostDomains must contain lowercase DNS names without a port, a wildcard or a trailing dot");
            }
            // The coordinator and the Gateway qualify a user by the host label under these domains. A canary
            // host outside them would qualify the same user differently than the principal that is checked.
            if (hasVerifiedTenantIdentity() && hostQualificationDomains.stream().map(PoolLifecycleConfiguration::normalizedDomain).noneMatch(domain::equals)) {
                throw new IllegalArgumentException("Every canaryHostDomains entry must also be in hostQualificationDomains when a tenant identity source is set, "
                        + "so a canary host qualifies a user as the coordinator does");
            }
        }
        if (maxPreDispatchCandidates < 1 || maxPreDispatchCandidates > 10) {
            throw new IllegalArgumentException("maxPreDispatchCandidates must be between 1 and 10");
        }
        if (certificateFreshnessSeconds < 1 || certificateFreshnessSeconds > 3600) {
            throw new IllegalArgumentException("certificateFreshnessSeconds must be between 1 and 3600");
        }
        if (operationRetentionDays < 1 || operationRetentionDays > 3650) {
            throw new IllegalArgumentException("operationRetentionDays must be between 1 and 3650");
        }
    }
}
