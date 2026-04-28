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
import org.restlet.Context;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.engine.aggregates.AggregateFunction;
import io.naftiko.engine.aggregates.FunctionResult;
import io.naftiko.engine.observability.TelemetryBootstrap;
import io.naftiko.engine.util.OperationStepExecutor;
import io.naftiko.engine.util.Resolver;
import io.naftiko.spec.exposes.mcp.McpServerToolSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Handles MCP tool calls by delegating to consumed HTTP operations.
 * 
 * Mirrors the logic in ApiOperationsRestlet but adapted for MCP tool invocations: - Input
 * parameters come from MCP CallToolRequest arguments (not HTTP request) - Supports both simple call
 * mode and full step orchestration - Returns McpSchema.CallToolResult (not HTTP response)
 */
public class ToolHandler {

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
        this.stepExecutor = new OperationStepExecutor(capability, exposeNamespace);
        this.exposeNamespace = exposeNamespace;

        if (tools != null) {
            for (McpServerToolSpec tool : tools) {
                if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                    Context.getCurrentLogger().warning(
                            "Skipping malformed MCP tool entry: tool or name is missing");
                    continue;
                }
                toolSpecs.put(tool.getName(), tool);
            }
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

        TelemetryBootstrap telemetry = TelemetryBootstrap.get();
        Span span = telemetry.startToolHandlerSpan(toolName);
        long startNanos = System.nanoTime();
        String status = "OK";
        try (Scope scope = span.makeCurrent()) {
            McpSchema.CallToolResult result = doHandleToolCall(toolName, arguments);
            if (result.isError() != null && result.isError()) {
                status = "ERROR";
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "tool returned error");
                telemetry.getMetrics().recordRequestError("mcp", toolName, "handled_error");
            }
            return result;
        } catch (Exception e) {
            status = "ERROR";
            TelemetryBootstrap.recordError(span, e);
            telemetry.getMetrics().recordRequestError("mcp", toolName,
                    e.getClass().getSimpleName());
            throw e;
        } finally {
            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            telemetry.getMetrics().recordRequest("mcp", toolName, status, durationSec);
            TelemetryBootstrap.endSpan(span);
        }
    }

    McpSchema.CallToolResult doHandleToolCall(String toolName, Map<String, Object> arguments)
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

        // Delegate to aggregate function when ref is set
        if (toolSpec.getRef() != null) {
            return executeViaAggregate(toolSpec, toolName, parameters);
        }

        OperationStepExecutor.HandlingContext found;
        try {
            boolean isOrchestrated =
                    toolSpec.getSteps() != null && !toolSpec.getSteps().isEmpty();
            boolean hasCall = toolSpec.getCall() != null;

            if (!hasCall && !isOrchestrated) {
                // Mock mode — no call, no steps: build response from output value fields
                return buildMockToolResult(toolSpec, parameters);
            }

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
            Context.getCurrentLogger().warning("Error during HTTP client call for tool '" + toolName + "': " + e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "Error during HTTP client call: " + e.getMessage())),
                    true, null, null);
        }

        // Map the response to MCP CallToolResult
        return buildToolResult(toolSpec, found);
    }

    /**
     * Execute a tool call by delegating to its referenced aggregate function.
     */
    private McpSchema.CallToolResult executeViaAggregate(McpServerToolSpec toolSpec,
            String toolName, Map<String, Object> parameters) throws Exception {
        try {
            AggregateFunction fn = capability.lookupFunction(toolSpec.getRef());
            FunctionResult result = fn.execute(parameters);

            if (result.isMock()) {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(
                        result.mockOutput != null ? result.mockOutput
                                : mapper.createObjectNode());
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(json)), false, null, null);
            }

            if (result.hasMappedOutput()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(result.mappedOutput)), false, null,
                        null);
            }

            return buildToolResult(toolSpec, result.lastContext);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            Context.getCurrentLogger().warning("Error during aggregate function call for tool '" + toolName + "': "
                    + e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "Error during aggregate function call: " + e.getMessage())),
                    true, null, null);
        }
    }

    /**
     * Build an MCP CallToolResult from output parameter {@code value} fields (mock mode).
     * Mustache templates in values are resolved against the given parameters.
     */
    private McpSchema.CallToolResult buildMockToolResult(McpServerToolSpec toolSpec,
            Map<String, Object> parameters) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode mockRoot = Resolver.buildMockData(toolSpec.getOutputParameters(), mapper,
                parameters);

        String json = mapper.writeValueAsString(mockRoot != null ? mockRoot : mapper.createObjectNode());
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(json)), false, null, null);
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

        // Apply output parameter mappings if defined, converting from the declared format
        String outputRawFormat = found.clientOperation != null
                ? found.clientOperation.getOutputRawFormat() : null;
        String outputSchema = found.clientOperation != null
                ? found.clientOperation.getOutputSchema() : null;
        String mapped = stepExecutor.applyOutputMappings(responseText,
                toolSpec.getOutputParameters(), outputRawFormat, outputSchema);
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
