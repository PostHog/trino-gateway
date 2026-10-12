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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.config.RoutingConfiguration;
import io.trino.gateway.ha.module.HaGatewayProviderModule;
import io.trino.gateway.ha.router.schema.RoutingSelectorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestHostnameRoutingGroupSelector
{
    @Test
    void refusesMissingHostDespiteRoutingHeader()
    {
        assertRejected(List.of(), 400);
    }

    @Test
    void refusesDuplicateHostDespiteRoutingHeader()
    {
        assertRejected(List.of("tenant.dw.example.test", "other.dw.example.test"), 400);
        assertRejected(List.of("tenant.dw.example.test", "tenant.dw.example.test"), 400);
    }

    @Test
    void refusesMalformedHostDespiteRoutingHeader()
    {
        assertRejected(List.of("tenant.dw.example.test,other.dw.example.test"), 400);
    }

    @Test
    void refusesUnknownHostDespiteRoutingHeader()
    {
        assertRejected(List.of("tenant.other.example.test"), 421);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant.dw.example.test", "TENANT.DW.EXAMPLE.TEST", "tenant.dw.example.test.", "tenant.dw.example.test:443", "TENANT.DW.EXAMPLE.TEST.:8443"})
    void normalizesHostButNeverUsesRoutingOrForwardedHeaders(String host)
    {
        HttpServletRequest request = request(List.of(host));
        when(request.getHeader("X-Trino-Routing-Group")).thenReturn("pool-attacker");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("elsewhere.example.test");
        when(request.getHeader("Forwarded")).thenReturn("host=elsewhere.example.test");
        when(request.getServerName()).thenReturn("elsewhere.example.test");
        RoutingSelectorResponse selected = selector().findRoutingDestination(request);
        assertThat(selected.routingGroup()).isEqualTo("pool-one");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " tenant.dw.example.test", "tenant.dw.example.test ", "tenant.dw.example.test..", "tenant..dw.example.test", "-tenant.dw.example.test", "tenant-.dw.example.test", "tenant_name.dw.example.test", "tenant.dw.example.test:0", "tenant.dw.example.test:65536", "tenant.dw.example.test:99999999999999999999", "tenant.dw.example.test:-1", "tenant.dw.example.test:", "tenant.dw.example.test:443:80", "https://tenant.dw.example.test", "user@tenant.dw.example.test", "tenant.dw.example.test/path", "tenant.dw.example.test?x=1", "tenant.dw.example.test#x", "[::1]:443", "127.0.0.1", "tenant.\u212a.test", "tenant.dw.example.test\r\nX: evil"})
    void rejectsMalformedAuthorities(String host)
    {
        assertRejected(List.of(host), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dw.example.test", "a.b.dw.example.test", "tenant.notdw.example.test", "tenant.dw.example.test.attacker.test"})
    void suffixRequiresExactlyOneTenantLabel(String host)
    {
        assertRejected(List.of(host), 421);
    }

    @Test
    void enforcesDnsLengthLimits()
    {
        assertRejected(List.of("a".repeat(64) + ".dw.example.test"), 400);
        assertRejected(List.of(("a".repeat(63) + ".").repeat(4) + "test"), 400);
        assertThat(selector().findRoutingDestination(request(List.of("a".repeat(63) + ".dw.example.test"))).routingGroup()).isEqualTo("pool-one");
    }

    @Test
    void exactHostsOverrideTheSinglePoolDomainForDurablePlacement()
    {
        RoutingConfiguration configuration = configuration();
        configuration.setHostnameToPool(Map.of("TENANT.DW.EXAMPLE.TEST.", "pool-two", "dw.example.test", "pool-apex"));
        RoutingGroupSelector selector = new HostnameRoutingGroupSelector(configuration);
        assertThat(selector.findRoutingDestination(request(List.of("tenant.dw.example.test:443"))).routingGroup()).isEqualTo("pool-two");
        assertThat(selector.findRoutingDestination(request(List.of("other.dw.example.test"))).routingGroup()).isEqualTo("pool-one");
        assertThat(selector.findRoutingDestination(request(List.of("dw.example.test"))).routingGroup()).isEqualTo("pool-apex");
    }

    @Test
    void nestedSuffixMappingDoesNotDependOnMapOrder()
    {
        RoutingConfiguration configuration = configuration();
        configuration.setHostnameSuffixToPool(Map.of("dw.example.test", "pool-one", "nested.dw.example.test", "pool-two"));
        RoutingGroupSelector selector = new HostnameRoutingGroupSelector(configuration);
        assertThat(selector.findRoutingDestination(request(List.of("tenant.nested.dw.example.test"))).routingGroup()).isEqualTo("pool-two");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.dw.example.test", ".dw.example.test", "dw.example.test:443", "dw.example.test..", "bad_name.example.test", "127.0.0.1", "\u212a.example.test"})
    void invalidConfigurationFailsAtProviderCreation(String hostname)
    {
        HaGatewayConfiguration configuration = new HaGatewayConfiguration();
        configuration.getRouting().setHostnameSuffixToPool(Map.of(hostname, "pool-one"));
        assertThatThrownBy(() -> HaGatewayProviderModule.getRoutingGroupSelector(configuration)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesCanonicalDuplicateMappings()
    {
        RoutingConfiguration configuration = configuration();
        configuration.setHostnameSuffixToPool(Map.of("dw.example.test", "pool-one", "DW.EXAMPLE.TEST.", "pool-two"));
        assertThatThrownBy(() -> new HostnameRoutingGroupSelector(configuration)).isInstanceOf(IllegalArgumentException.class);
        configuration.setHostnameSuffixToPool(Map.of());
        configuration.setHostnameToPool(Map.of("tenant.example.test", "pool-one", "TENANT.EXAMPLE.TEST.", "pool-two"));
        assertThatThrownBy(() -> new HostnameRoutingGroupSelector(configuration)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " pool-one", "pool-one ", "pool\none"})
    void rejectsInvalidPoolIdentifiers(String pool)
    {
        RoutingConfiguration configuration = configuration();
        configuration.setHostnameToPool(Map.of("tenant.dw.example.test", pool));
        assertThatThrownBy(() -> new HostnameRoutingGroupSelector(configuration)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotsConfigurationAndDoesNotFallbackWhenEmpty()
    {
        Map<String, String> placements = new HashMap<>(Map.of("tenant.dw.example.test", "pool-two"));
        RoutingConfiguration configuration = configuration();
        configuration.setHostnameToPool(placements);
        RoutingGroupSelector selector = new HostnameRoutingGroupSelector(configuration);
        placements.put("tenant.dw.example.test", "pool-attacker");
        configuration.setHostnameToPool(Map.of());
        assertThat(selector.findRoutingDestination(request(List.of("tenant.dw.example.test"))).routingGroup()).isEqualTo("pool-two");
        assertThatThrownBy(() -> new HostnameRoutingGroupSelector(new RoutingConfiguration()).findRoutingDestination(request(List.of("tenant.dw.example.test"))))
                .isInstanceOfSatisfying(WebApplicationException.class, failure -> assertThat(failure.getResponse().getStatus()).isEqualTo(421));
    }

    @Test
    void deserializesTypedYamlWithoutLegacyRules()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        HaGatewayConfiguration configuration = mapper.readValue(
                """
                routing:
                  hostnameSuffixToPool:
                    dw.example.test: pool-one
                  hostnameToPool:
                    tenant.dw.example.test: pool-two
                """,
                HaGatewayConfiguration.class);
        assertThat(HaGatewayProviderModule.getRoutingGroupSelector(configuration).findRoutingDestination(request(List.of("tenant.dw.example.test"))).routingGroup()).isEqualTo("pool-two");
        assertThatThrownBy(() -> mapper.readValue("routingRules: {rulesEngineEnabled: true}", HaGatewayConfiguration.class))
                .hasMessageContaining("routingRules");
    }

    private static void assertRejected(List<String> hosts, int status)
    {
        HttpServletRequest request = request(hosts);
        when(request.getHeader("X-Trino-Routing-Group")).thenReturn("pool-one");
        when(request.getServerName()).thenReturn("tenant.dw.example.test");
        assertThatThrownBy(() -> selector().findRoutingDestination(request))
                .isInstanceOfSatisfying(WebApplicationException.class, failure -> assertThat(failure.getResponse().getStatus()).isEqualTo(status));
    }

    private static HttpServletRequest request(List<String> hosts)
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaders("Host")).thenAnswer(_ -> Collections.enumeration(hosts));
        return request;
    }

    private static RoutingConfiguration configuration()
    {
        RoutingConfiguration configuration = new RoutingConfiguration();
        configuration.setHostnameSuffixToPool(Map.of("dw.example.test", "pool-one"));
        return configuration;
    }

    private static RoutingGroupSelector selector()
    {
        HaGatewayConfiguration configuration = new HaGatewayConfiguration();
        configuration.setRouting(configuration());
        return HaGatewayProviderModule.getRoutingGroupSelector(configuration);
    }
}
