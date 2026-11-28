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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * The memo's Release notes case through a whole capability: fetch the linked issue of each
 * merged pull request, in order, optionally only the first ones.
 */
class ForEachAcceptanceTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static String capabilityYaml(String limit, int issueStatus) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: fake
                      namespace: github
                      operations:
                        list-merged-pulls:
                          outputParameters: []
                        get-linked-issue:
                          outputParameters: []
                      responses:
                        list-merged-pulls: '[{"number":1},{"number":2},{"number":3}]'
                        get-linked-issue: '{"pull":"{{pull}}","title":"Issue of {{pull}}"}'
                      statuses:
                        get-linked-issue: %d
                  aggregates:
                    - namespace: releases
                      flows:
                        notes:
                          description: Release notes
                          inputParameters:
                            max:
                              type: number
                              description: How many pull requests at most
                          steps:
                            - name: merged
                              type: call
                              what: github.list-merged-pulls
                            - name: issues
                              type: forEach
                              items: merged
                              as: pr
                %s
                              do:
                                type: call
                                what: github.get-linked-issue
                                with:
                                  pull: "{{pr.number}}"
                          mappings:
                            - target: issues
                              value: $.issues
                          outputParameters:
                            issues:
                              type: array
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: releases-mcp
                      description: Release tools
                      tools:
                        notes:
                          description: Release notes
                          ref: releases.notes
                """.formatted(IKANOS, issueStatus, limit.indent(14).stripTrailing());
    }

    private static McpSchema.CallToolResult notes(String limit, int issueStatus,
            Map<String, Object> arguments) throws Exception {
        Capability capability = new Capability(IkanosMappers.lenientYaml().readValue(
                capabilityYaml(limit, issueStatus), IkanosSpec.class));
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);
        return mcp.getToolHandler().handleToolCall("notes", arguments);
    }

    private static JsonNode issues(McpSchema.CallToolResult result) {
        return IkanosMappers.json().readTree(
                ((McpSchema.TextContent) result.content().get(0)).text()).path("issues");
    }

    @BeforeEach
    void setUp() {
        FakeClientAdapter.CALLS.clear();
    }

    @Test
    void aForEachStepShouldRunItsStepForEachItemInOrder() throws Exception {
        McpSchema.CallToolResult result = notes("", 200, Map.of());

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        JsonNode issues = issues(result);
        assertEquals(3, issues.size());
        assertEquals("Issue of 1", issues.get(0).path("title").asString());
        assertEquals("Issue of 3", issues.get(2).path("title").asString());
    }

    @Test
    void aLimitShouldRunTheFirstItemsOnly() throws Exception {
        McpSchema.CallToolResult result = notes("limit: 2", 200, Map.of());

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(2, issues(result).size());
        assertEquals(3, FakeClientAdapter.CALLS.size(), "the list, then two issues");
    }

    @Test
    void aLimitBoundToAnInputShouldLetTheCallerSetIt() throws Exception {
        McpSchema.CallToolResult result = notes("limit: max", 200, Map.of("max", 1));

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(1, issues(result).size());
    }

    @Test
    void aFailingItemShouldFailTheStep() throws Exception {
        McpSchema.CallToolResult result = notes("", 500, Map.of());

        assertTrue(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(2, FakeClientAdapter.CALLS.size(), "the list, then the first item only");
    }
}
