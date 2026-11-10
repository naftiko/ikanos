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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads a capability whose MCP tool inputs leave {@code required} out (#977) and asserts the
 * {@code required} list advertised in {@code tools/list}: an input is optional unless it says
 * {@code required: true}, for an inline tool and for a tool that reuses an aggregate flow.
 */
class McpToolOptionalInputIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    @Test
    void toolsListShouldAdvertiseOnlyExplicitlyRequiredInputsOfAnInlineTool() throws Exception {
        JsonNode inputSchema = inputSchemaOf("list-issues");

        assertEquals(jsonMapper.readTree("[\"team\"]"), inputSchema.path("required"));
    }

    @Test
    void toolsListShouldAdvertiseOnlyExplicitlyRequiredInputsOfAFlowTool() throws Exception {
        JsonNode inputSchema = inputSchemaOf("get-issue");

        assertEquals(jsonMapper.readTree("[\"id\"]"), inputSchema.path("required"));
    }

    @Test
    void toolsListShouldOmitRequiredWhenNoInputIsRequired() throws Exception {
        JsonNode inputSchema = inputSchemaOf("search-issues");

        assertFalse(inputSchema.has("required"), inputSchema.toString());
    }

    private JsonNode inputSchemaOf(String toolName) throws Exception {
        JsonNode tools = dispatch(load(), "tools/list").path("result").path("tools");
        return toolNamed(tools, toolName).path("inputSchema");
    }

    private McpServerAdapter load() throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Optional inputs"
                  description: "MCP tool inputs are optional unless they say otherwise"
                capability:
                  aggregates:
                    - display: "Issues"
                      namespace: "issues-domain"
                      flows:
                        get-issue:
                          description: "Get an issue."
                          call: "tracker.get-issue"
                          inputParameters:
                            id:
                              type: "string"
                              required: true
                              description: "Issue ID."
                            expand:
                              type: "string"
                              description: "Related objects to expand."
                  consumes:
                    - type: "http"
                      namespace: "tracker"
                      baseUri: "http://localhost:1"
                      resources:
                        issues:
                          path: "/issues"
                          operations:
                            list-issues:
                              method: "GET"
                            get-issue:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "tracker-mcp"
                      tools:
                        list-issues:
                          description: "List issues of a team."
                          call: "tracker.list-issues"
                          inputParameters:
                            team:
                              type: "string"
                              required: true
                              description: "Team key."
                            assignee:
                              type: "string"
                              description: "Assignee ID."
                            cursor:
                              type: "string"
                              description: "Cursor of the next page."
                        search-issues:
                          description: "Search issues."
                          call: "tracker.list-issues"
                          inputParameters:
                            query:
                              type: "string"
                              description: "Search text."
                        get-issue:
                          ref: "issues-domain.get-issue"
                """.formatted(IKANOS);
        IkanosSpec spec = yamlMapper.readValue(yaml, IkanosSpec.class);
        return (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = request.putObject("params");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private static JsonNode toolNamed(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asString())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name + " in " + tools);
    }
}
