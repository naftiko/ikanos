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
package io.ikanos.engine.consumes.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * The memo's Issue triage case through a whole capability: a bug becomes an incident page, a
 * feature a roadmap row, anything else a request for details; three paths, by nesting a choice
 * in else.
 */
class ChoiceAcceptanceTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String CAPABILITY = """
            ikanos: "%s"
            capability:
              consumes:
                - type: fake
                  namespace: triage
                  operations:
                    create-incident-page:
                      outputParameters: []
                    add-roadmap-row:
                      outputParameters: []
                    ask-for-details:
                      outputParameters: []
                  responses:
                    create-incident-page: '{"routed":"incident"}'
                    add-roadmap-row: '{"routed":"roadmap"}'
                    ask-for-details: '{"routed":"details"}'
              aggregates:
                - namespace: issues
                  flows:
                    route:
                      description: Route a new issue
                      inputParameters:
                        issue:
                          type: object
                          description: The issue
                      steps:
                        - name: route
                          type: choice
                          if: "issue.label == 'bug'"
                          then:
                            type: call
                            what: triage.create-incident-page
                          else:
                            type: choice
                            if: "issue.label == 'feature'"
                            then:
                              type: call
                              what: triage.add-roadmap-row
                            else:
                              type: call
                              what: triage.ask-for-details
                      mappings:
                        - target: routed
                          value: $.route.routed
                      outputParameters:
                        routed:
                          type: string
              exposes:
                - type: mcp
                  address: localhost
                  port: 0
                  namespace: issues-mcp
                  description: Issue tools
                  tools:
                    route:
                      description: Route a new issue
                      ref: issues.route
            """.formatted(IKANOS);

    @BeforeEach
    void setUp() {
        FakeClientAdapter.CALLS.clear();
    }

    @ParameterizedTest
    @CsvSource({"bug, incident", "feature, roadmap", "question, details"})
    void aChoiceShouldRunExactlyOnePath(String label, String routed) throws Exception {
        Capability capability = new Capability(
                IkanosMappers.lenientYaml().readValue(CAPABILITY, IkanosSpec.class));
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpSchema.CallToolResult result = mcp.getToolHandler().handleToolCall("route",
                Map.of("issue", Map.of("label", label)));

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        JsonNode payload = IkanosMappers.json().readTree(
                ((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals(routed, payload.path("routed").asString());
        assertEquals(1, FakeClientAdapter.CALLS.size(), "one path, one call");
    }

    @Test
    void theCapabilityShouldLoadWithNoGuardError() throws Exception {
        new Capability(IkanosMappers.lenientYaml().readValue(CAPABILITY, IkanosSpec.class));
    }
}
