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

import java.io.IOException;
import java.util.logging.Level;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.restlet.Context;
import io.modelcontextprotocol.spec.McpSchema;
import io.ikanos.engine.exposes.ErrorReference;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.aggregates.FlowResult;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import com.fasterxml.jackson.core.type.TypeReference;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Capability capability;
    private final Map<String, McpServerToolSpec> toolSpecs;
    private final OperationStepExecutor stepExecutor;
    private final String exposeNamespace;

    /**
     * Per-tool output contracts (MCP {@code outputSchema}), keyed by tool name. Only tools whose
     * output can be described as a JSON object have an entry. See {@link McpToolOutputSchema}.
     */
    private final Map<String, McpToolOutputSchema.Contract> outputContracts;

    /**
     * Adapter-level {@code maxBinarySize} sized string ({@code exposes.<name>.maxBinarySize}), or
     * {@code null} when none is declared. Threaded into
     * {@link io.ikanos.engine.consumes.ConsumedOperationView#maxBinaryBytes(String)} so the per-op
     * value still wins but the adapter cap overrides the engine default (§4.7 / §8.1).
     */
    private final String maxBinarySize;

    public ToolHandler(Capability capability, Map<String, McpServerToolSpec> tools) {
        this(capability, tools, null);
    }

    public ToolHandler(Capability capability, Map<String, McpServerToolSpec> tools,
            String exposeNamespace) {
        this(capability, tools, exposeNamespace, null);
    }

    public ToolHandler(Capability capability, Map<String, McpServerToolSpec> tools,
            String exposeNamespace, String maxBinarySize) {
        this.capability = capability;
        this.toolSpecs = new ConcurrentHashMap<>();
        this.outputContracts = new ConcurrentHashMap<>();
        this.stepExecutor = new OperationStepExecutor(capability, exposeNamespace);
        this.exposeNamespace = exposeNamespace;
        this.maxBinarySize = maxBinarySize;

        if (tools != null) {
            for (McpServerToolSpec tool : tools.values()) {
                if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                    Context.getCurrentLogger().warning(
                            "Skipping malformed MCP tool entry: tool or name is missing");
                    continue;
                }
                toolSpecs.put(tool.getName(), tool);
                McpToolOutputSchema.Contract contract =
                        McpToolOutputSchema.resolve(tool, capability);
                if (contract != null) {
                    outputContracts.put(tool.getName(), contract);
                }
            }
        }
    }

    /**
     * Return the output contract (MCP {@code outputSchema}) of a tool, or {@code null} when the
     * tool's output is not described.
     *
     * @param toolName the tool name
     * @return the contract, or {@code null}
     */
    McpToolOutputSchema.Contract getOutputContract(String toolName) {
        return toolName != null ? outputContracts.get(toolName) : null;
    }

    /**
     * Build a text tool result for a mapped JSON payload, adding {@code structuredContent} when the
     * tool advertises an {@code outputSchema} for this result path.
     *
     * <p>The serialized JSON is always kept as a {@link McpSchema.TextContent} block for clients
     * that do not read {@code structuredContent} (MCP backward-compatibility recommendation).
     * {@code structuredContent} is attached only when the result is not an error, the tool has an
     * output contract whose {@link McpToolOutputSchema.Source} matches {@code source}, and the
     * payload parses to a JSON object.</p>
     *
     * @param toolName the invoked tool name
     * @param mapped   the mapped JSON payload
     * @param isError  whether the result is an error
     * @param source   the result path that produced {@code mapped}
     * @return the tool result
     */
    McpSchema.CallToolResult mappedResult(String toolName, String mapped, boolean isError,
            McpToolOutputSchema.Source source) {
        Object structured = isError ? null : structuredContentFor(toolName, mapped, source);
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(mapped)), isError,
                structured, null);
    }

    /**
     * Parse {@code mapped} into a {@code structuredContent} map when the tool's output contract
     * covers {@code source}; {@code null} otherwise.
     */
    Map<String, Object> structuredContentFor(String toolName, String mapped,
            McpToolOutputSchema.Source source) {
        McpToolOutputSchema.Contract contract = getOutputContract(toolName);
        if (contract == null || contract.source() != source || mapped == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(mapped);
            if (node == null || !node.isObject()) {
                return null;
            }
            return JSON.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (IOException | IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.FINE,
                    "Mapped output of tool '" + toolName + "' is not a JSON object", e);
            return null;
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

        ConsumedResult found;
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
                            toolSpec.getMappings(), toolSpec.getOutputParameters(),
                            stepResult.stepContext);
                    if (mapped != null) {
                        return mappedResult(toolName, mapped, false,
                                McpToolOutputSchema.Source.STEPS);
                    }
                }

                return buildToolResult(toolSpec, stepResult.lastResult);
            }

            found = stepExecutor.execute(toolSpec.getCall(), toolSpec.getSteps(), parameters,
                    "Tool '" + toolName + "'");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            return errorResult("Error during consumed call",
                    "Error during consumed call for tool '" + toolName + "'", e);
        }

        // Map the response to MCP CallToolResult
        return buildToolResult(toolSpec, found);
    }

    /**
     * Execute a tool call by delegating to its referenced aggregate flow.
     */
    private McpSchema.CallToolResult executeViaAggregate(McpServerToolSpec toolSpec,
            String toolName, Map<String, Object> parameters) throws Exception {
        try {
            AggregateFlow fn = capability.lookupFlow(toolSpec.getRef());
            FlowResult result = fn.execute(parameters);

            if (result.isMock()) {
                String json = JSON.writeValueAsString(
                        result.mockOutput != null ? result.mockOutput
                                : JSON.createObjectNode());
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(json)), false, null, null);
            }

            if (result.hasMappedOutput()) {
                McpToolOutputSchema.Source source = fn.getSteps() != null
                        && !fn.getSteps().isEmpty()
                                ? McpToolOutputSchema.Source.STEPS
                                : McpToolOutputSchema.Source.CALL;
                return mappedResult(toolName, result.mappedOutput, false, source);
            }

            return buildToolResult(toolSpec, result.lastResult);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            return errorResult("Error during aggregate function call",
                    "Error during aggregate function call for tool '" + toolName + "'", e);
        }
    }

    /**
     * Build an MCP CallToolResult from output parameter {@code value} fields (mock mode).
     * Mustache templates in values are resolved against the given parameters.
     */
    private McpSchema.CallToolResult buildMockToolResult(McpServerToolSpec toolSpec,
            Map<String, Object> parameters) throws IOException {
        JsonNode mockRoot = Resolver.buildMockData(toolSpec.getOutputParameters(), JSON,
                parameters);

        String json = JSON.writeValueAsString(mockRoot != null ? mockRoot : JSON.createObjectNode());
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(json)), false, null, null);
    }

    /**
     * Build an error tool result that carries only a generic message and a correlation
     * identifier; the exception detail is logged under that identifier. See
     * {@link ErrorReference}.
     */
    private static McpSchema.CallToolResult errorResult(String publicMessage, String logContext,
            Throwable cause) {
        String ref = ErrorReference.record(Level.WARNING, logContext, cause);
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                        ErrorReference.withReference(publicMessage, ref))),
                true, null, null);
    }

    /**
     * Build an MCP CallToolResult from a consumed result.
     */
    private McpSchema.CallToolResult buildToolResult(McpServerToolSpec toolSpec,
            ConsumedResult found) throws IOException {

        if (found == null) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "No response received: no matching client adapter found")),
                    true, null, null);
        }

        int statusCode = found.status();
        boolean isError = found.outcome() != Outcome.SUCCESS;
        boolean hasBody = found.hasBody();

        if (!isError && !hasBody) {
            return new McpSchema.CallToolResult(Collections.emptyList(),
                    false, null, null);
        } else if (!hasBody) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "No response entity received (HTTP " + statusCode + " "
                                    + found.reason() + ")")),
                    true, null, null);
        }

        // Binary path: the consumed operation declared `outputRawFormat: binary`. Buffer the raw
        // bytes under the maxBinarySize cap and emit the MIME-appropriate MCP content block
        // (ImageContent / AudioContent / EmbeddedResource). outputParameters mappings are skipped —
        // they are nonsensical for raw bytes. See capability-binary-content.md §4.4 / §8.2.
        if (found.operation().isBinary()) {
            return buildBinaryToolResult(toolSpec, found, isError);
        }

        // Buffer entity text before any mapping to avoid double-read issues
        String responseText = found.text();

        // Apply output parameter mappings if defined, converting from the declared format
        String mapped = toolSpec.getOutputParameters() != null
                && !toolSpec.getOutputParameters().isEmpty()
                && responseText != null && !responseText.isEmpty()
                        ? stepExecutor.mapResult(found, toolSpec.getOutputParameters())
                        : null;
        if (mapped != null) {
            return mappedResult(toolSpec.getName(), mapped, isError,
                    McpToolOutputSchema.Source.CALL);
        }

        // Fall back to raw response
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(responseText != null ? responseText : "")),
                isError, null, null);
    }

    /**
     * Build an MCP {@code CallToolResult} for a binary upstream response.
     *
     * <p>The raw bytes are buffered under the per-operation {@code maxBinarySize} cap (falling back
     * to the adapter-level {@code maxBinarySize}, then the engine default 10&nbsp;MiB) and
     * base64-encoded into the MIME-appropriate content block via
     * {@link #buildBinaryContent}. {@code outputParameters} mappings are skipped with an INFO log
     * (§4.6). When the upstream payload exceeds the cap, an error result is returned rather than an
     * exception, so the agent receives a usable diagnostic.</p>
     */
    private McpSchema.CallToolResult buildBinaryToolResult(McpServerToolSpec toolSpec,
            ConsumedResult found, boolean isError) {
        if (toolSpec.getOutputParameters() != null && !toolSpec.getOutputParameters().isEmpty()) {
            Context.getCurrentLogger().info(
                    "Skipping outputParameters mappings for tool '" + toolSpec.getName()
                            + "': response is binary (" + found.mediaType() + ")");
        }

        byte[] bytes;
        try {
            bytes = found.bytes(found.operation().maxBinaryBytes(maxBinarySize));
        } catch (OperationStepExecutor.BinarySizeExceededException e) {
            Context.getCurrentLogger().warning(
                    "Binary tool response exceeded maxBinarySize for '" + toolSpec.getName()
                            + "': " + e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                            "Upstream response exceeded maxBinarySize (limit=" + e.getMaxBytes()
                                    + " bytes)")),
                    true, null, null);
        } catch (IOException e) {
            return errorResult("Error buffering binary response",
                    "Error buffering binary tool response for '" + toolSpec.getName() + "'", e);
        }

        if (bytes == null) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("No binary response entity received")),
                    true, null, null);
        }

        String mediaType = found.mediaType() != null
                ? found.mediaType()
                : "application/octet-stream";
        McpSchema.Content content = buildBinaryContent(toolSpec.getName(), bytes, mediaType);
        return new McpSchema.CallToolResult(List.of(content), isError, null, null);
    }

    /**
     * Dispatch a base64-encoded binary payload to the MCP content block that matches its MIME type
     * (§4.4):
     *
     * <ul>
     *   <li>{@code image/*} → {@link McpSchema.ImageContent}</li>
     *   <li>{@code audio/*} → {@link McpSchema.AudioContent}</li>
     *   <li>anything else → {@link McpSchema.EmbeddedResource} wrapping a transient
     *       {@link McpSchema.BlobResourceContents} with a generated, non-addressable URI of the
     *       form {@code ikanos://transient/{capability}/{toolName}/{uuid}}</li>
     * </ul>
     *
     * @param toolName  the invoked tool name (used in the transient resource URI)
     * @param bytes     the raw response bytes
     * @param mediaType the resolved upstream/contract media type (never {@code null})
     * @return the MIME-appropriate MCP content block
     */
    McpSchema.Content buildBinaryContent(String toolName, byte[] bytes, String mediaType) {
        String data = Base64.getEncoder().encodeToString(bytes);
        String lower = mediaType.toLowerCase();

        if (lower.startsWith("image/")) {
            return new McpSchema.ImageContent(null, data, mediaType);
        }
        if (lower.startsWith("audio/")) {
            return new McpSchema.AudioContent(null, data, mediaType);
        }

        String capabilityName = exposeNamespace != null ? exposeNamespace : "ikanos";
        String uri = "ikanos://transient/" + capabilityName + "/" + toolName + "/"
                + UUID.randomUUID();
        McpSchema.BlobResourceContents blob =
                new McpSchema.BlobResourceContents(uri, mediaType, data);
        return new McpSchema.EmbeddedResource(null, blob);
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
