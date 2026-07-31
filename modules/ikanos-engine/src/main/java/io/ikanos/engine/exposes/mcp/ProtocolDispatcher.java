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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.handler.McpCallHandler;
import io.ikanos.engine.exposes.mcp.handler.McpCallHandlersFactory;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.observability.McpMetaTraceContextBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.exception.ProcessorException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.restlet.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_REQUEST;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.METHOD_NOT_FOUND;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;
import static org.restlet.Context.getCurrentLogger;

/**
 * Transport-agnostic MCP JSON-RPC protocol dispatcher.
 *
 * Handles MCP protocol methods (tools/list, tools/call, resources/list,
 * resources/read, resources/templates/list, prompts/list, prompts/get, ping)
 * and produces JSON-RPC response envelopes. Used by both the Streamable HTTP
 * handler and the stdio handler.
 */
public class ProtocolDispatcher {

    public static final String JSONRPC_VERSION = "2.0";
    public static final String MCP_PROTOCOL_VERSION = "2026-07-28";

    private final ObjectMapper mapper;
    private final Map<String, McpCallHandler> mcpHandlers = new HashMap<>();
    private final String capabilityName;

    public ProtocolDispatcher(McpServerAdapter adapter) {
        this.mapper = new ObjectMapper();
        this.capabilityName = resolveCapabilityName(adapter);
        for (McpCallHandler handler : McpCallHandlersFactory.createAll(adapter)) {
            mcpHandlers.put(handler.getMethodName(), handler);
        }
    }

    /**
     * Resolves the capability's display name, or {@code null} when absent. Used to tag the
     * SERVER span created by each transport (Streamable HTTP reads it from the Restlet
     * {@code Context} attributes instead; stdio has no such context, so it reads it here).
     */
    private static String resolveCapabilityName(McpServerAdapter adapter) {
        if (adapter.getCapability().getSpec().getInfo() != null
                && adapter.getCapability().getSpec().getInfo().getDisplay() != null) {
            return adapter.getCapability().getSpec().getInfo().getDisplay();
        }
        return null;
    }

    /**
     * Dispatch a JSON-RPC request and return the dispatch result.
     *
     * @param request the parsed JSON-RPC request.
     * @return the JSON-RPC response envelope + rpcError wrapped in a {@link DispatchResult}.
     */
    public DispatchResult dispatch(JsonNode request) {
        if (request == null) {
            return new DispatchResult(buildJsonRpcError(null, INVALID_REQUEST.getCode(),
                    "Invalid Request: request body is missing"), INVALID_REQUEST);
        }

        try {
            JsonNode idNode = request.get("id");
            String rpcMethod = request.path("method").asText("");

            McpCallHandler callHandler = mcpHandlers.get(rpcMethod);
            if (callHandler != null) {
                for (DispatchPreProcessor preProcessor : callHandler.getPreProcessors()) {
                    preProcessor.apply(request);
                }

                HandlerResult result = callHandler.handle(request);

                switch (result) {
                    case HandlerFailureResult failureResult -> {
                        // In case of failure, the response's body is already complete, we simply return it
                        return new DispatchResult(failureResult.body(), failureResult.rpcError());
                    }
                    case HandlerSuccessResult successResult -> {
                        ObjectNode rpcResponse = mapper.createObjectNode();
                        rpcResponse.put("jsonrpc", JSONRPC_VERSION);

                        if (idNode != null) {
                            rpcResponse.set("id", idNode);
                        }
                        rpcResponse.set("result", successResult.result());

                        for (DispatchPostProcessor postProcessor : callHandler.getPostProcessors()) {
                            postProcessor.apply(rpcResponse);
                        }

                        return new DispatchResult(rpcResponse);
                    }
                }
            } else {
                return new DispatchResult(buildJsonRpcError(idNode, METHOD_NOT_FOUND.getCode(),
                        "Method not found: " + rpcMethod), METHOD_NOT_FOUND);
            }
        } catch (ProcessorException e) {
            getCurrentLogger().log(Level.SEVERE, "An error has occurred while processing a request", e);
            return new DispatchResult(e.getResult(), e.getRpcError());
        } catch (Exception e) {
            getCurrentLogger().log(Level.SEVERE, "Error processing request", e);
            return new DispatchResult(buildJsonRpcError(request.get("id"), INTERNAL_ERROR.getCode(),
                    "Internal error: " + e.getMessage()), INTERNAL_ERROR);
        }
    }

    /**
     * Dispatch a JSON-RPC request within a SERVER span, extracting W3C trace context from the
     * request's {@code params._meta} object (transport-agnostic carrier per the MCP
     * {@code 2026-07-28} revision) and, when supplied, falling back to the given HTTP
     * {@code traceparent} header when {@code _meta} carries no trace context.
     *
     * <p>Used by both transports so span creation, MDC population, and error recording happen
     * identically for Streamable HTTP ({@link McpServerResource}) and stdio
     * ({@link StdioJsonRpcHandler}).</p>
     *
     * @param request     the parsed JSON-RPC request.
     * @param httpRequest the inbound Restlet HTTP request, or {@code null} on stdio.
     * @return the JSON-RPC response envelope + rpcError wrapped in a {@link DispatchResult}.
     */
    public DispatchResult dispatchWithTracing(JsonNode request, Request httpRequest) {
        if (request == null) {
            return new DispatchResult(buildJsonRpcError(null, INVALID_REQUEST.getCode(),
                    "Invalid Request: request body is missing"), INVALID_REQUEST);
        }

        String rpcMethod = request.path("method").asText("");
        Context extractedContext = (httpRequest != null)
                ? McpMetaTraceContextBridge.extractContext(request, httpRequest)
                : McpMetaTraceContextBridge.extractContext(request);

        Span span = TelemetryBootstrap.get().startServerSpan("mcp", rpcMethod,
                extractedContext, null, capabilityName);
        try (Scope scope = span.makeCurrent()) {
            TelemetryBootstrap.populateMdc(span);
            return dispatch(request);
        } catch (Exception e) {
            TelemetryBootstrap.recordError(span, e);
            throw e;
        } finally {
            TelemetryBootstrap.clearMdc();
            TelemetryBootstrap.endSpan(span);
        }
    }

    /**
     * Returns the method handler of a specific method.
     * @param methodName the method's name.
     * @return the handler if found, null otherwise.
     */
    public McpCallHandler getMethodHandler(String methodName) {
        return mcpHandlers.get(methodName);
    }

    ObjectMapper getMapper() {
        return mapper;
    }
}
