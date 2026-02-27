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
package io.naftiko.engine.exposes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.engine.Converter;
import io.naftiko.engine.Resolver;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.engine.consumes.HttpClientAdapter;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.consumes.HttpClientOperationSpec;
import io.naftiko.spec.exposes.ApiServerCallSpec;
import io.naftiko.spec.exposes.ApiServerStepSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;

/**
 * Handles MCP tool calls by delegating to consumed HTTP operations.
 * 
 * Mirrors the logic in ApiOperationsRestlet but adapted for MCP tool invocations:
 * - Input parameters come from MCP CallToolRequest arguments (not HTTP request)
 * - Supports both simple call mode and full step orchestration
 * - Returns McpSchema.CallToolResult (not HTTP response)
 */
public class McpToolHandler {

    private final Capability capability;
    private final Map<String, McpServerToolSpec> toolSpecs;
    private final ObjectMapper mapper;

    public McpToolHandler(Capability capability, List<McpServerToolSpec> tools) {
        this.capability = capability;
        this.toolSpecs = new ConcurrentHashMap<>();
        this.mapper = new ObjectMapper();

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

        // Merge arguments with tool-level 'with' parameters
        Map<String, Object> parameters = new HashMap<>();
        if (arguments != null) {
            parameters.putAll(arguments);
        }
        if (toolSpec.getWith() != null) {
            parameters.putAll(toolSpec.getWith());
        }

        HandlingContext found = null;

        if (toolSpec.getCall() != null) {
            // Simple call mode
            found = findClientRequestFor(toolSpec.getCall(), parameters);

            if (found != null) {
                try {
                    found.handle();
                } catch (Exception e) {
                    return new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent(
                                    "Error during HTTP client call: " + e.getMessage())),
                            true, null, null);
                }
            } else {
                throw new IllegalArgumentException("Invalid call format: "
                        + (toolSpec.getCall() != null ? toolSpec.getCall().getOperation() : "null"));
            }
        } else if (toolSpec.getSteps() != null && !toolSpec.getSteps().isEmpty()) {
            // Step orchestration mode
            for (ApiServerStepSpec step : toolSpec.getSteps()) {
                Map<String, Object> stepParams = new ConcurrentHashMap<>(parameters);

                // Merge step-level 'with' parameters
                if (step.getWith() != null) {
                    stepParams.putAll(step.getWith());
                }

                // Merge call-level 'with' parameters (call level takes precedence)
                if (step.getCall() != null && step.getCall().getWith() != null) {
                    stepParams.putAll(step.getCall().getWith());
                }

                found = findClientRequestFor(step.getCall(), stepParams);

                if (found != null) {
                    found.handle();
                } else {
                    throw new IllegalArgumentException("Invalid call format in step: "
                            + (step.getCall() != null ? step.getCall().getOperation() : "null"));
                }
            }
        } else {
            throw new IllegalArgumentException(
                    "Tool '" + toolName + "' has neither call nor steps defined");
        }

        // Map the response to MCP CallToolResult
        return buildToolResult(toolSpec, found);
    }

    /**
     * Build an MCP CallToolResult from the HTTP client response.
     */
    private McpSchema.CallToolResult buildToolResult(McpServerToolSpec toolSpec,
            HandlingContext found) throws IOException {

        if (found == null) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("No response received: no matching client adapter found")),
                    true, null, null);
        }

        if (found.clientResponse == null) {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("No response received: client response is null")),
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

        // Apply output parameter mappings if defined
        if (toolSpec.getOutputParameters() != null && !toolSpec.getOutputParameters().isEmpty()) {
            String mapped = mapOutputParameters(toolSpec, found);
            if (mapped != null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(mapped)), isError, null, null);
            }
        }

        // Fall back to raw response
        String responseText = found.clientResponse.getEntity().getText();
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(responseText != null ? responseText : "")),
                isError, null, null);
    }

    /**
     * Map client response to the tool's declared outputParameters and return a JSON string.
     * Returns null when mapping could not be applied.
     */
    private String mapOutputParameters(McpServerToolSpec toolSpec, HandlingContext found)
            throws IOException {
        if (found == null || found.clientResponse == null
                || found.clientResponse.getEntity() == null) {
            return null;
        }

        JsonNode root = Converter.convertToJson(null, null, found.clientResponse.getEntity());

        for (OutputParameterSpec outputParameter : toolSpec.getOutputParameters()) {
            JsonNode mapped = Resolver.resolveOutputMappings(outputParameter, root, mapper);

            if (mapped != null && !(mapped instanceof NullNode)) {
                return mapper.writeValueAsString(mapped);
            }
        }

        return null;
    }

    /**
     * Find and construct a client request context for a call specification.
     */
    private HandlingContext findClientRequestFor(ApiServerCallSpec call,
            Map<String, Object> requestParams) {

        if (call == null) {
            return null;
        }

        Map<String, Object> merged = new HashMap<>();
        if (requestParams != null) {
            merged.putAll(requestParams);
        }
        if (call.getWith() != null) {
            merged.putAll(call.getWith());
        }

        if (call.getOperation() != null) {
            String[] tokens = call.getOperation().split("\\.");
            if (tokens.length == 2) {
                return findClientRequestFor(tokens[0], tokens[1], merged);
            }
        }

        return null;
    }

    /**
     * Find and construct a client request context for a given namespace, operation name,
     * and parameters.
     */
    private HandlingContext findClientRequestFor(String clientNamespace, String clientOpName,
            Map<String, Object> parameters) {

        for (ClientAdapter adapter : capability.getClientAdapters()) {
            if (adapter instanceof HttpClientAdapter) {
                HttpClientAdapter clientAdapter = (HttpClientAdapter) adapter;

                if (clientAdapter.getHttpClientSpec().getNamespace().equals(clientNamespace)) {
                    HttpClientOperationSpec clientOp = clientAdapter.getOperationSpec(clientOpName);

                    if (clientOp != null) {
                        String clientResUri = clientAdapter.getHttpClientSpec().getBaseUri()
                                + clientOp.getParentResource().getPath();

                        // Resolve Mustache templates
                        clientResUri = Resolver.resolveMustacheTemplate(clientResUri, parameters);

                        // Validate all templates are resolved
                        if (clientResUri.contains("{{") && clientResUri.contains("}}")) {
                            throw new IllegalArgumentException(
                                    "Unresolved template parameters in URI: " + clientResUri
                                            + ". Available parameters: "
                                            + (parameters != null ? parameters.keySet() : "none"));
                        }

                        HandlingContext ctx = new HandlingContext();
                        ctx.clientRequest = new Request();
                        ctx.clientAdapter = clientAdapter;
                        ctx.clientResponse = new Response(ctx.clientRequest);

                        // Apply client-level and operation-level input parameters
                        Resolver.resolveInputParametersToRequest(ctx.clientRequest,
                                clientAdapter.getHttpClientSpec().getInputParameters(), parameters);
                        Resolver.resolveInputParametersToRequest(ctx.clientRequest,
                                clientOp.getInputParameters(), parameters);

                        ctx.clientRequest.setMethod(Method.valueOf(clientOp.getMethod()));
                        ctx.clientRequest.setResourceRef(new Reference(
                                Resolver.resolveMustacheTemplate(clientResUri, parameters)));

                        if (clientOp.getBody() != null) {
                            String resolvedBody = Resolver
                                    .resolveMustacheTemplate(clientOp.getBody(), parameters);

                            if (resolvedBody.contains("{{") && resolvedBody.contains("}}")) {
                                throw new IllegalArgumentException(
                                        "Unresolved template parameters in body: " + resolvedBody
                                                + ". Available parameters: "
                                                + (parameters != null ? parameters.keySet()
                                                        : "none"));
                            }

                            ctx.clientRequest.setEntity(resolvedBody, MediaType.APPLICATION_JSON);
                        }

                        // Set authentication and headers
                        ctx.clientAdapter.setChallengeResponse(ctx.clientRequest,
                                ctx.clientRequest.getResourceRef().toString(), parameters);
                        ctx.clientAdapter.setHeaders(ctx.clientRequest);
                        return ctx;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Internal context for managing an HTTP client request-response pair.
     */
    private static class HandlingContext {
        HttpClientAdapter clientAdapter;
        Request clientRequest;
        Response clientResponse;

        void handle() {
            clientAdapter.getHttpClient().handle(clientRequest, clientResponse);
        }
    }

}
