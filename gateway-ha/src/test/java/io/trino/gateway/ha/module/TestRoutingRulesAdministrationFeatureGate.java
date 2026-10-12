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

import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.domain.RoutingRule;
import io.trino.gateway.ha.resource.GatewayWebAppResource;
import io.trino.gateway.ha.router.BackendStateManager;
import io.trino.gateway.ha.router.GatewayBackendManager;
import io.trino.gateway.ha.router.QueryHistoryManager;
import io.trino.gateway.ha.router.RoutingRulesManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TestRoutingRulesAdministrationFeatureGate
{
    @Test
    void enabledDoesNotReadOrWriteRules()
            throws Exception
    {
        HaGatewayConfiguration configuration = new HaGatewayConfiguration();
        configuration.getTransactionAwareness().setEnabled(true);
        configuration.getRoutingRules().setRulesEngineEnabled(true);
        configuration.getRoutingRules().setRulesConfigPath("missing-rules.yml");
        RoutingRulesManager rules = mock(RoutingRulesManager.class);
        GatewayWebAppResource resource = resource(configuration, rules);
        assertThat(resource.getRoutingRules().getStatus()).isEqualTo(204);
        assertThat(resource.updateRoutingRules(mock(RoutingRule.class)).getStatus()).isEqualTo(409);
        verifyNoInteractions(rules);
    }

    @Test
    void disabledPreservesRulesAdministration()
            throws Exception
    {
        HaGatewayConfiguration configuration = new HaGatewayConfiguration();
        configuration.getTransactionAwareness().setEnabled(false);
        RoutingRulesManager rules = mock(RoutingRulesManager.class);
        RoutingRule rule = mock(RoutingRule.class);
        GatewayWebAppResource resource = resource(configuration, rules);
        assertThat(resource.getRoutingRules().getStatus()).isEqualTo(200);
        assertThat(resource.updateRoutingRules(rule).getStatus()).isEqualTo(200);
        verify(rules).getRoutingRules();
        verify(rules).updateRoutingRule(rule);
    }

    private static GatewayWebAppResource resource(HaGatewayConfiguration configuration, RoutingRulesManager rules)
    {
        return new GatewayWebAppResource(
                mock(GatewayBackendManager.class),
                mock(QueryHistoryManager.class),
                mock(BackendStateManager.class),
                rules,
                configuration);
    }
}
