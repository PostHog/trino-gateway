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
package io.trino.gateway.ha.module;

import io.airlift.http.client.HttpClient;
import io.trino.gateway.ha.config.GatewayCookieConfiguration;
import io.trino.gateway.ha.config.GatewayCookieConfigurationPropertiesProvider;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.config.RulesExternalConfiguration;
import io.trino.gateway.ha.config.RulesType;
import io.trino.gateway.ha.router.ExternalRoutingGroupSelector;
import io.trino.gateway.ha.router.FileBasedRoutingGroupSelector;
import io.trino.gateway.ha.router.HostnameRoutingGroupSelector;
import io.trino.gateway.ha.router.RoutingGroupSelector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Isolated
class TestRoutingSelectorFeatureGate
{
    @Test
    void enabledUsesHostnameAndIgnoresHeader()
            throws Exception
    {
        HaGatewayConfiguration configuration = configuration(true);
        RoutingGroupSelector selector = provider(configuration);
        assertThat(selector).isInstanceOf(HostnameRoutingGroupSelector.class);
        assertThat(selector.findRoutingDestination(request("tenant.example.test")).routingGroup()).isEqualTo("hostname-pool");
        assertThatThrownBy(() -> selector.findRoutingDestination(request("unknown.example.test")))
                .isInstanceOf(WebApplicationException.class);
    }

    @Test
    void disabledPreservesUpstreamHeaderSelector()
            throws Exception
    {
        RoutingGroupSelector selector = provider(configuration(false));
        assertThat(selector).isNotInstanceOf(HostnameRoutingGroupSelector.class);
        assertThat(selector.findRoutingDestination(request("tenant.example.test")).routingGroup()).isEqualTo("header-group");
        assertThat(selector.findRoutingDestination(request("unknown.example.test")).routingGroup()).isEqualTo("header-group");
    }

    @Test
    void upstreamRulesConfigurationRemainsAvailable()
            throws Exception
    {
        assertThat(HaGatewayConfiguration.class.getMethod("getRoutingRules").invoke(configuration(false))).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(RulesType.class)
    void enabledIgnoresConfiguredRulesEngine(RulesType rulesType)
    {
        HaGatewayConfiguration configuration = configuration(true);
        configuration.getRoutingRules().setRulesEngineEnabled(true);
        configuration.getRoutingRules().setRulesType(rulesType);
        configuration.getRoutingRules().setRulesConfigPath("missing-rules.yml");
        RulesExternalConfiguration external = new RulesExternalConfiguration();
        external.setExcludeHeaders(java.util.List.of());
        external.setUrlPath("http://router.example.test/route");
        configuration.getRoutingRules().setRulesExternalConfiguration(external);
        HttpClient httpClient = mock(HttpClient.class);
        RoutingGroupSelector selector = HaGatewayProviderModule.getRoutingGroupSelector(httpClient, configuration);
        assertThat(selector).isInstanceOf(HostnameRoutingGroupSelector.class);
        assertThat(selector.findRoutingDestination(request("tenant.example.test")).routingGroup()).isEqualTo("hostname-pool");
        assertThatThrownBy(() -> selector.findRoutingDestination(request("unknown.example.test")))
                .isInstanceOf(WebApplicationException.class);
        verifyNoInteractions(httpClient);
    }

    @Test
    void disabledPreservesFileRules(@TempDir Path directory)
            throws Exception
    {
        Path rules = directory.resolve("rules.yml");
        Files.writeString(rules,
                """
                ---
                name: always
                condition: "true"
                actions:
                  - "result.put('routingGroup', 'file-group')"
                """);
        HaGatewayConfiguration configuration = configuration(false);
        configuration.getRoutingRules().setRulesEngineEnabled(true);
        configuration.getRoutingRules().setRulesConfigPath(rules.toString());
        configuration.getRequestAnalyzerConfig().setAnalyzeRequest(false);
        RoutingGroupSelector selector = provider(configuration);
        assertThat(selector).isInstanceOf(FileBasedRoutingGroupSelector.class);
        assertThat(selector.findRoutingDestination(request("unknown.example.test")).routingGroup()).isEqualTo("file-group");
    }

    @Test
    void disabledPreservesExternalRules()
            throws Exception
    {
        HaGatewayConfiguration configuration = configuration(false);
        configuration.getRoutingRules().setRulesEngineEnabled(true);
        configuration.getRoutingRules().setRulesType(RulesType.EXTERNAL);
        RulesExternalConfiguration external = new RulesExternalConfiguration();
        external.setExcludeHeaders(java.util.List.of());
        external.setUrlPath("http://router.example.test/route");
        configuration.getRoutingRules().setRulesExternalConfiguration(external);
        assertThat(provider(configuration)).isInstanceOf(ExternalRoutingGroupSelector.class);
    }

    @Test
    void enabledDisablesCookieInitializationEvenWhenConfiguredWithoutSigningKey()
    {
        HaGatewayConfiguration configuration = configuration(true);
        configuration.getGatewayCookieConfiguration().setEnabled(true);
        try {
            assertThat(new HaGatewayProviderModule(configuration)).isNotNull();
            assertThat(GatewayCookieConfigurationPropertiesProvider.getInstance().isEnabled()).isFalse();
            assertThat(configuration.getGatewayCookieConfiguration().isEnabled()).isTrue();
        }
        finally {
            GatewayCookieConfigurationPropertiesProvider.getInstance().initialize(new GatewayCookieConfiguration());
        }
    }

    @Test
    void disabledPreservesCookieSigningKeyValidation()
    {
        HaGatewayConfiguration configuration = configuration(false);
        configuration.getGatewayCookieConfiguration().setEnabled(true);
        assertThatThrownBy(() -> new HaGatewayProviderModule(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cookieSigningSecret");
    }

    private static HaGatewayConfiguration configuration(boolean enabled)
    {
        HaGatewayConfiguration configuration = new HaGatewayConfiguration();
        configuration.getTransactionAwareness().setEnabled(enabled);
        configuration.getRouting().setHostnameToPool(Map.of("tenant.example.test", "hostname-pool"));
        return configuration;
    }

    private static RoutingGroupSelector provider(HaGatewayConfiguration configuration)
            throws Exception
    {
        return HaGatewayProviderModule.getRoutingGroupSelector(mock(HttpClient.class), configuration);
    }

    private static HttpServletRequest request(String hostname)
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaders("Host")).thenReturn(Collections.enumeration(java.util.List.of(hostname)));
        when(request.getHeader("X-Trino-Routing-Group")).thenReturn("header-group");
        return request;
    }
}
