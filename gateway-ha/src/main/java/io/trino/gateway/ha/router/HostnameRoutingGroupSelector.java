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

import io.trino.gateway.ha.config.RoutingConfiguration;
import io.trino.gateway.ha.router.schema.RoutingSelectorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Host is a routing key, never proof of tenant authentication or authorization.
 */
public final class HostnameRoutingGroupSelector
        implements RoutingGroupSelector
{
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private final Map<String, String> exactHosts;
    private final Map<String, String> suffixes;

    public HostnameRoutingGroupSelector(RoutingConfiguration configuration)
    {
        requireNonNull(configuration, "configuration is null");
        exactHosts = normalizeMappings(configuration.getHostnameToPool());
        suffixes = normalizeMappings(configuration.getHostnameSuffixToPool());
    }

    @Override
    public RoutingSelectorResponse findRoutingDestination(HttpServletRequest request)
    {
        Enumeration<String> hosts = request.getHeaders("Host");
        if (hosts == null || !hosts.hasMoreElements()) {
            throw new BadRequestException("Exactly one Host header is required");
        }
        String authority = hosts.nextElement();
        if (hosts.hasMoreElements()) {
            throw new BadRequestException("Exactly one Host header is required");
        }
        String host;
        try {
            host = normalizeAuthority(authority);
        }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Malformed Host header", e);
        }
        String pool = exactHosts.get(host);
        if (pool != null) {
            return new RoutingSelectorResponse(pool);
        }
        String matchingSuffix = null;
        for (String suffix : suffixes.keySet()) {
            if (host.endsWith("." + suffix)) {
                String label = host.substring(0, host.length() - suffix.length() - 1);
                if (LABEL.matcher(label).matches() && (matchingSuffix == null || suffix.length() > matchingSuffix.length())) {
                    matchingSuffix = suffix;
                }
            }
        }
        if (matchingSuffix == null) {
            throw new WebApplicationException("No pool is configured for this hostname", 421);
        }
        return new RoutingSelectorResponse(suffixes.get(matchingSuffix));
    }

    private static Map<String, String> normalizeMappings(Map<String, String> mappings)
    {
        Map<String, String> normalized = new LinkedHashMap<>();
        requireNonNull(mappings, "hostname mappings are null").forEach((hostname, pool) -> {
            String host = normalizeHostname(hostname);
            if (pool == null || pool.isBlank() || !pool.equals(pool.trim()) || pool.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A hostname mapping requires a nonempty pool identifier without surrounding whitespace or controls");
            }
            if (normalized.putIfAbsent(host, pool) != null) {
                throw new IllegalArgumentException("Hostname mappings contain duplicate canonical hostnames");
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalizeAuthority(String authority)
    {
        if (authority == null) {
            throw new IllegalArgumentException("Host is null");
        }
        int colon = authority.indexOf(':');
        if (colon < 0) {
            return normalizeHostname(authority);
        }
        String port = authority.substring(colon + 1);
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
            throw new IllegalArgumentException("Invalid Host port");
        }
        return normalizeHostname(authority.substring(0, colon));
    }

    private static String normalizeHostname(String hostname)
    {
        if (hostname == null || hostname.isEmpty() || hostname.codePoints().anyMatch(character -> character > 127)) {
            throw new IllegalArgumentException("Hostname is empty");
        }
        String normalized = hostname.toLowerCase(Locale.ENGLISH);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.length() > 253 || normalized.matches("[0-9]+(?:\\.[0-9]+)*")) {
            throw new IllegalArgumentException("A DNS hostname is required");
        }
        for (String label : normalized.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Malformed DNS hostname");
            }
        }
        return normalized;
    }
}
