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

import io.airlift.units.Duration;

import java.util.Map;

import static java.util.concurrent.TimeUnit.MINUTES;

public class RoutingConfiguration
{
    private Duration asyncTimeout = new Duration(2, MINUTES);

    private boolean forwardedHeadersEnabled = true;

    private String defaultRoutingGroup = "adhoc";

    private Map<String, String> hostnameToPool = Map.of();
    private Map<String, String> hostnameSuffixToPool = Map.of();

    /**
     * Protocol the proxy asserts to Trino in {@code X-Forwarded-Proto}, instead of the scheme of
     * the connection it received. Set to {@code https} when a load balancer in front of the
     * Gateway terminates TLS and forwards plain HTTP, so that a coordinator running
     * {@code http-server.process-forwarded=true} still authenticates and builds next URIs with the
     * external scheme. {@code null} forwards the connection scheme.
     */
    private String forwardedProto;

    /**
     * Port the proxy asserts to Trino in {@code X-Forwarded-Port}, instead of the port of the
     * connection it received. Pair with {@link #forwardedProto} behind a load balancer that
     * terminates TLS: the external port is what next URIs must carry. {@code null} forwards the
     * connection port.
     */
    private Integer forwardedPort;

    // Off (default, opt-in dark launch): when on, the gateway pins every request of a Trino
    // OAuth2 token-exchange handshake (the driver's /oauth2/token/{authId} poll loop and the
    // browser's /oauth2/token/initiate/{authIdHash} redirect) to the coordinator that minted the
    // authId. Only that coordinator holds the in-memory token-exchange state, so without pinning a
    // multi-coordinator deployment routes the poll loop stochastically and the handshake stalls.
    // See OAuth2RoutingUtils.
    private boolean oauth2RoutingEnabled;

    // Default false. If true, pooled routing orders eligible members by load (queued queries plus
    // running queries weighted by memory pressure), after health. See BackendLoad.
    private boolean loadAwarePooledRoutingEnabled;

    public Duration getAsyncTimeout()
    {
        return asyncTimeout;
    }

    public void setAsyncTimeout(Duration asyncTimeout)
    {
        this.asyncTimeout = asyncTimeout;
    }

    public boolean isForwardedHeadersEnabled()
    {
        return forwardedHeadersEnabled;
    }

    public void setForwardedHeadersEnabled(boolean forwardedHeadersEnabled)
    {
        this.forwardedHeadersEnabled = forwardedHeadersEnabled;
    }

    public String getDefaultRoutingGroup()
    {
        return defaultRoutingGroup;
    }

    public void setDefaultRoutingGroup(String defaultRoutingGroup)
    {
        this.defaultRoutingGroup = defaultRoutingGroup;
    }

    public Map<String, String> getHostnameToPool()
    {
        return hostnameToPool;
    }

    public void setHostnameToPool(Map<String, String> hostnameToPool)
    {
        this.hostnameToPool = Map.copyOf(hostnameToPool);
    }

    /**
     * A suffix maps exactly one leading DNS label to its pool, excluding the apex and nested hosts.
     * Use only for domains whose tenant placements share one pool.
     */
    public Map<String, String> getHostnameSuffixToPool()
    {
        return hostnameSuffixToPool;
    }

    public void setHostnameSuffixToPool(Map<String, String> hostnameSuffixToPool)
    {
        this.hostnameSuffixToPool = Map.copyOf(hostnameSuffixToPool);
    }

    public String getForwardedProto()
    {
        return forwardedProto;
    }

    public void setForwardedProto(String forwardedProto)
    {
        this.forwardedProto = forwardedProto;
    }

    public Integer getForwardedPort()
    {
        return forwardedPort;
    }

    public void setForwardedPort(Integer forwardedPort)
    {
        this.forwardedPort = forwardedPort;
    }

    public boolean isOauth2RoutingEnabled()
    {
        return oauth2RoutingEnabled;
    }

    public void setOauth2RoutingEnabled(boolean oauth2RoutingEnabled)
    {
        this.oauth2RoutingEnabled = oauth2RoutingEnabled;
    }

    public boolean isLoadAwarePooledRoutingEnabled()
    {
        return loadAwarePooledRoutingEnabled;
    }

    public void setLoadAwarePooledRoutingEnabled(boolean loadAwarePooledRoutingEnabled)
    {
        this.loadAwarePooledRoutingEnabled = loadAwarePooledRoutingEnabled;
    }
}
