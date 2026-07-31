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
import io.ikanos.engine.exposes.mcp.handler.*;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.exception.ProcessorException;
import org.restlet.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_REQUEST;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.METHOD_NOT_FOUND;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

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

    public ProtocolDispatcher(McpServerAdapter adapter) {
        this.mapper = new ObjectMapper();
        for (McpCallHandler handler : McpCallHandlersFactory.createAll(adapter)) {
            mcpHandlers.put(handler.getMethodName(), handler);
        }
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
            Context.getCurrentLogger().log(Level.SEVERE, "An error has occurred while processing a request", e);
            return new DispatchResult(e.getResult(), e.getRpcError());
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);
            return new DispatchResult(buildJsonRpcError(request.get("id"), INTERNAL_ERROR.getCode(),
                    "Internal error: " + e.getMessage()), INTERNAL_ERROR);
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
