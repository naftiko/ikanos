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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientToolSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * An MCP tool input without {@code type} is a {@code string}: the default is resolved when the
 * spec is loaded, for MCP tool inputs, aggregate flow inputs and consumed MCP tool arguments, and
 * nowhere else (#986).
 */
class McpToolInputDefaultTypeTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static IkanosSpec spec;

    @BeforeAll
    static void load() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: issues
                      flows:
                        get-issue:
                          description: "Get one issue."
                          inputParameters:
                            owner:
                              description: "Repository owner."
                            issue_number:
                              type: integer
                              description: "Issue number."
                          call: github.get-issue
                  consumes:
                    - type: http
                      namespace: github
                      baseUri: "https://api.github.test"
                      resources:
                        issues:
                          path: "/repos/{{owner}}/issues/{{issue_number}}"
                          operations:
                            get-issue:
                              method: GET
                              inputParameters:
                                accept:
                                  in: header
                                  value: "application/json"
                    - type: mcp
                      namespace: upstream
                      endpoint: "https://mcp.upstream.test/mcp"
                      tools:
                        search:
                          description: "Search upstream."
                          inputParameters:
                            query:
                              description: "Search text."
                            limit:
                              type: integer
                              description: "Maximum hits."
                  exposes:
                    - type: mcp
                      namespace: tools
                      port: 3001
                      tools:
                        get-issue:
                          description: "Get one issue."
                          inputParameters:
                            owner:
                              description: "Repository owner."
                            issue_number:
                              type: integer
                              description: "Issue number."
                          call: github.get-issue
                    - type: rest
                      namespace: api
                      port: 3002
                      resources:
                        issues:
                          path: "/issues/{{owner}}"
                          operations:
                            get-issue:
                              method: GET
                              ref: issues.get-issue
                              inputParameters:
                                owner:
                                  in: path
                                  description: "Repository owner."
                """.formatted(IKANOS);
        spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }

    private static InputParameterSpec named(List<InputParameterSpec> params, String name) {
        return params.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
    }

    @Test
    void mcpToolInputShouldDefaultToStringWhenTypeIsOmitted() {
        McpServerSpec mcp = (McpServerSpec) spec.getCapability().getExposes().get(0);
        McpServerToolSpec tool = mcp.getTools().get("get-issue");

        assertEquals("string", named(tool.getInputParameters(), "owner").getType());
    }

    @Test
    void mcpToolInputShouldKeepDeclaredType() {
        McpServerSpec mcp = (McpServerSpec) spec.getCapability().getExposes().get(0);
        McpServerToolSpec tool = mcp.getTools().get("get-issue");

        assertEquals("integer", named(tool.getInputParameters(), "issue_number").getType());
    }

    @Test
    void aggregateFlowInputShouldDefaultToStringWhenTypeIsOmitted() {
        Map<String, InputParameterSpec> inputs = spec.getCapability().getAggregates()
                .values().iterator().next().getFlows().get("get-issue").getInputParameters();

        assertEquals("string", inputs.get("owner").getType());
        assertEquals("integer", inputs.get("issue_number").getType());
    }

    @Test
    void consumedMcpToolArgumentShouldDefaultToStringWhenTypeIsOmitted() {
        McpClientSpec upstream = (McpClientSpec) spec.getCapability().getConsumes().get(1);
        McpClientToolSpec tool = upstream.getTools().get("search");

        assertEquals("string", named(tool.getInputParameters(), "query").getType());
        assertEquals("integer", named(tool.getInputParameters(), "limit").getType());
    }

    @Test
    void consumedHttpInputShouldStayUntypedWhenTypeIsOmitted() {
        HttpClientSpec http = (HttpClientSpec) spec.getCapability().getConsumes().get(0);
        List<InputParameterSpec> inputs = http.getResources().get("issues").getOperations()
                .get("get-issue").getInputParameters();

        assertNull(named(inputs, "accept").getType());
    }

    @Test
    void restExposedInputShouldStayUntypedWhenTypeIsOmitted() {
        RestServerSpec rest = (RestServerSpec) spec.getCapability().getExposes().get(1);
        List<InputParameterSpec> inputs = rest.getResources().get("issues").getOperations()
                .get("get-issue").getInputParameters();

        assertNull(named(inputs, "owner").getType());
    }

    @Test
    void setInputParametersShouldDefaultProgrammaticMcpToolInputs() {
        McpServerToolSpec tool = new McpServerToolSpec("t", null, "d");
        InputParameterSpec param = new InputParameterSpec();
        param.setName("q");
        Map<String, InputParameterSpec> params = new LinkedHashMap<>();
        params.put("q", param);

        tool.setInputParameters(params);

        assertEquals("string", tool.getInputParameters().get(0).getType());
    }

    @Test
    void applyMcpToolInputDefaultsShouldLeaveNestedSchemasUntouched() {
        InputParameterSpec items = new InputParameterSpec();
        InputParameterSpec param = new InputParameterSpec();
        param.setType("array");
        param.setItems(items);
        List<InputParameterSpec> params = new ArrayList<>();
        params.add(param);

        InputParameterSpec.applyMcpToolInputDefaults(params);

        assertEquals("array", param.getType());
        assertNull(items.getType());
    }

    @Test
    void applyMcpToolInputDefaultsShouldAcceptNull() {
        assertDoesNotThrow(() -> InputParameterSpec.applyMcpToolInputDefaults(null));
    }
}
