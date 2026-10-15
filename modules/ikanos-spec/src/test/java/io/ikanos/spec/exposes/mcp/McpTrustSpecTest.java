/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

class McpTrustSpecTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper YAML_MAPPER = IkanosMappers.lenientYaml();

    @Test
    void getTrustShouldBeNullWhenBlockIsOmitted() throws Exception {
        McpServerSpec mcp = parse("");
        assertNull(mcp.getTrust());
    }

    @Test
    void deserializationShouldReadForwardedTrustBlock() throws Exception {
        McpServerSpec mcp = parse("""
                    trust:
                      mode: forwarded
                      agentIdentityHeader: X-Agent-Id
                      trustedNetworks: ["10.0.0.0/8", "fd00::/8"]
                """);
        McpTrustSpec trust = mcp.getTrust();
        assertEquals(McpTrustSpec.MODE_FORWARDED, trust.getMode());
        assertEquals("X-Agent-Id", trust.getAgentIdentityHeader());
        assertEquals(List.of("10.0.0.0/8", "fd00::/8"), trust.getTrustedNetworks());
    }

    @Test
    void getModeShouldDefaultToNoneWhenModeIsMissing() {
        assertEquals(McpTrustSpec.MODE_NONE, new McpTrustSpec().getMode());
    }

    @Test
    void setTrustedNetworksShouldKeepAnImmutableCopy() {
        List<String> networks = new ArrayList<>(List.of("10.0.0.0/8"));
        McpTrustSpec trust = new McpTrustSpec(McpTrustSpec.MODE_FORWARDED);
        trust.setTrustedNetworks(networks);
        networks.add("0.0.0.0/0");
        assertEquals(List.of("10.0.0.0/8"), trust.getTrustedNetworks());
    }

    private static McpServerSpec parse(String trustBlock) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 3001
                    namespace: demo
                %s    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                """.formatted(IKANOS, trustBlock);
        IkanosSpec spec = YAML_MAPPER.readValue(yaml, IkanosSpec.class);
        return (McpServerSpec) spec.getCapability().getExposes().get(0);
    }
}
