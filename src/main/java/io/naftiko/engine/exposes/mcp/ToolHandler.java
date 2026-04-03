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
package io.naftiko.engine.exposes.mcp;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.engine.Resolver;
import io.naftiko.engine.exposes.MockResponseBuilder;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.spec.exposes.McpServerToolSpec;

/**
 * Handles MCP tool calls by delegating to consumed HTTP operations.
 * 
 * Mirrors the logic in ApiOperationsRestlet but adapted for MCP tool invocations: - Input
 * parameters come from MCP CallToolRequest arguments (not HTTP request) - Supports both simple call
 * mode and full step orchestration - Returns McpSchema.CallToolResult (not HTTP response)
 */
public class ToolHandler {

    private static final Logger logger = Logger.getLogger(ToolHandler.class.getName());
    private final Capability capability;
    private final Map<String, McpServerToolSpec> toolSpecs;
    private final OperationStepExecutor stepExecutor;
    private final String exposeNamespace;

    public ToolHandler(Capability capability, List<McpServerToolSpec> tools) {
        this(capability, tools, null);
    }

    public ToolHandler(Capability capability, List<McpServerToolSpec> tools,
            String exposeNamespace) {
        this.capability = capability;
        this.toolSpecs = new ConcurrentHashMap<>();
        this.stepExecutor = new OperationStepExecutor(capability);
        this.exposeNamespace = exposeNamespace;

        for (McpServerToolSpec tool : tools) {
            toolSpecs.put(tool.getName(), tool);
        }
    }

    /**
     * Handle an MCP tool call.
     * 
     * @param toolName the name of the tool to invoke
     * @param arguments the tool input arguments (from MCP CallToolRequest)
     * @return the tool result
     */
    public McpSchema.CallToolResult handleToolCall(String toolName, Map<String, Object> arguments)
            throws Exception {

        McpServerToolSpec toolSpec = toolSpecs.get(toolName);
        if (toolSpec == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }

        // Merge arguments with tool-level 'with' parameters.
        // 'with' values are Mustache templates resolved against the tool arguments,
        // allowing parameter renaming (e.g. imo → imo_number).
        // Arguments take precedence for keys not present in 'with'.
        Map<String, Object> parameters = new HashMap<>();
        if (arguments != null) {
            parameters.putAll(arguments);
        }
        if (toolSpec.getWith() != null) {
            for (Map.Entry<String, Object> entry : toolSpec.getWith().entrySet()) {
                Object rawValue = entry.getValue();
                if (rawValue instanceof String) {
                    String strValue = (String) rawValue;
                    Object resolved = resolveWithValue(strValue, arguments);
                    if (resolved != null) {
                        parameters.put(entry.getKey(), resolved);
                    }
                } else {
                    parameters.put(entry.getKey(), rawValue);
                }
            }
        }

        // Mock mode: no call and no steps — return static const values
        if (toolSpec.getCall() == null
                && (toolSpec.getSteps() == null || toolSpec.getSteps().isEmpty())) {
            return buildMockToolResult(toolSpec);
        }

        OperationStepExecutor.HandlingContext found;
        try {
            boolean isOrchestrated =
                    toolSpec.getSteps() != null && !toolSpec.getSteps().isEmpty();

            if (isOrchestrated) {
                OperationStepExecutor.StepExecutionResult stepResult =
                        stepExecutor.executeSteps(toolSpec.getSteps(), parameters);

                // Apply step output mappings if defined
                if (toolSpec.getMappings() != null && !toolSpec.getMappings().isEmpty()) {
                    String mapped = stepExecutor.resolveStepMappings(
                            toolSpec.getMappings(), stepResult.stepContext);
                    if (mapped != null) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(mapped)), false, null, null);
                    }
                }

                return buildToolResult(toolSpec, stepResult.lastContext);
            }

            found = stepExecutor.execute(toolSpec.getCall(), toolSpec.getSteps(), parameters,
                    "Tool '" + toolName + "'");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.warning("Error during HTTP client call for tool '" + toolName + "': " + e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "Error during HTTP client call: " + e.getMessage())),
                    true, null, null);
        }

        // Map the response to MCP CallToolResult
        return buildToolResult(toolSpec, found);
    }

    /**
     * Build an MCP CallToolResult from the HTTP client response.
     */
    private McpSchema.CallToolResult buildToolResult(McpServerToolSpec toolSpec,
            OperationStepExecutor.HandlingContext found) throws IOException {

        if (found == null) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "No response received: no matching client adapter found")),
                    true, null, null);
        }

        if (found.clientResponse == null) {
            return new McpSchema.CallToolResult(List
                    .of(new McpSchema.TextContent("No response received: client response is null")),
                    true, null, null);
        }

        // Check for error status
        int statusCode = found.clientResponse.getStatus().getCode();
        boolean isError = statusCode >= 400;

        if (found.clientResponse.getEntity() == null) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "No response entity received (HTTP " + statusCode + " "
                                    + found.clientResponse.getStatus().getReasonPhrase() + ")")),
                    true, null, null);
        }

        // Buffer entity text before any mapping to avoid double-read issues
        String responseText = found.clientResponse.getEntity().getText();

        // Apply output parameter mappings if defined
        String mapped = stepExecutor.applyOutputMappings(responseText, toolSpec.getOutputParameters());
        if (mapped != null) {
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(mapped)),
                    isError, null, null);
        }

        // Fall back to raw response
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(responseText != null ? responseText : "")),
                isError, null, null);
    }

    /**
     * Build an MCP CallToolResult from static const values (mock mode).
     */
    McpSchema.CallToolResult buildMockToolResult(McpServerToolSpec toolSpec) {
        if (!MockResponseBuilder.canBuildMockResponse(toolSpec.getOutputParameters())) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "Mock mode: no const values found in outputParameters")),
                    true, null, null);
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode mockData = MockResponseBuilder.buildMockData(
                    toolSpec.getOutputParameters(), mapper);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mockData);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(json)), false, null, null);
        } catch (Exception e) {
            logger.warning("Error building mock response for tool '" + toolSpec.getName()
                    + "': " + e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "Error building mock response: " + e.getMessage())),
                    true, null, null);
        }
    }

    /**
     * Resolve a {@code with} value. Handles two syntaxes:
     * <ul>
     *   <li>Namespace-qualified reference ({@code namespace.paramName}) per §3.15.1 —
     *       resolved by looking up {@code paramName} in the caller's arguments.</li>
     *   <li>Mustache template ({@code {{paramName}}}) — resolved via JMustache.</li>
     * </ul>
     *
     * @return the resolved value, or {@code null} if the reference is a namespace-qualified
     *         reference whose target parameter was not provided by the caller
     */
    private Object resolveWithValue(String value, Map<String, Object> arguments) {
        if (exposeNamespace != null && value.startsWith(exposeNamespace + ".")) {
            String paramName = value.substring(exposeNamespace.length() + 1);
            return arguments != null ? arguments.get(paramName) : null;
        }
        return Resolver.resolveMustacheTemplate(value, arguments);
    }

    public Capability getCapability() {
        return capability;
    }

}
