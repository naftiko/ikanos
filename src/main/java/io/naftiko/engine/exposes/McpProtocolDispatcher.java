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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.restlet.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Transport-agnostic MCP JSON-RPC protocol dispatcher.
 * 
 * Handles MCP protocol methods (initialize, tools/list, tools/call, ping)
 * and produces JSON-RPC response envelopes. Used by both the Streamable HTTP
 * handler and the stdio handler.
 */
public class McpProtocolDispatcher {

    static final String JSONRPC_VERSION = "2.0";
    static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private final McpServerAdapter adapter;
    private final ObjectMapper mapper;

    public McpProtocolDispatcher(McpServerAdapter adapter) {
        this.adapter = adapter;
        this.mapper = new ObjectMapper();
    }

    /**
     * Dispatch a JSON-RPC request and return the response envelope.
     * 
     * @param request the parsed JSON-RPC request
     * @return the JSON-RPC response envelope, or {@code null} for notifications
     */
    public ObjectNode dispatch(JsonNode request) {
        try {
            String jsonrpc = request.path("jsonrpc").asText("");
            JsonNode idNode = request.get("id");
            String rpcMethod = request.path("method").asText("");
            JsonNode params = request.get("params");

            if (!JSONRPC_VERSION.equals(jsonrpc)) {
                return buildJsonRpcError(idNode, -32600,
                        "Invalid Request: jsonrpc must be '2.0'");
            }

            switch (rpcMethod) {
                case "initialize":
                    return handleInitialize(idNode);

                case "notifications/initialized":
                    // Notification — no response
                    return null;

                case "tools/list":
                    return handleToolsList(idNode);

                case "tools/call":
                    return handleToolsCall(idNode, params);

                case "ping":
                    return buildJsonRpcResult(idNode, mapper.createObjectNode());

                default:
                    return buildJsonRpcError(idNode, -32601,
                            "Method not found: " + rpcMethod);
            }
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);
            return buildJsonRpcError(null, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Handle MCP initialize request.
     */
    private ObjectNode handleInitialize(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);

        // Server capabilities — we support tools
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.putObject("tools");
        result.set("capabilities", capabilities);

        // Server info
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", adapter.getMcpServerSpec().getNamespace());
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);

        // Instructions from the spec description
        if (adapter.getMcpServerSpec().getDescription() != null) {
            result.put("instructions", adapter.getMcpServerSpec().getDescription());
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle tools/list request.
     */
    private ObjectNode handleToolsList(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");

        for (McpSchema.Tool tool : adapter.getTools()) {
            ObjectNode toolNode = mapper.createObjectNode();
            toolNode.put("name", tool.name());
            
            if (tool.description() != null) {
                toolNode.put("description", tool.description());
            }

            if (tool.inputSchema() != null) {
                toolNode.set("inputSchema", mapper.valueToTree(tool.inputSchema()));
            }

            toolsArray.add(toolNode);
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle tools/call request.
     */
    @SuppressWarnings("unchecked")
    private ObjectNode handleToolsCall(JsonNode id, JsonNode params) {
        if (params == null) {
            return buildJsonRpcError(id, -32602, "Invalid params: missing params");
        }

        String toolName = params.path("name").asText("");
        JsonNode argumentsNode = params.get("arguments");

        try {
            Map<String, Object> arguments = argumentsNode != null
                    ? mapper.treeToValue(argumentsNode, Map.class)
                    : new ConcurrentHashMap<>();
            McpSchema.CallToolResult toolResult =
                    adapter.getToolHandler().handleToolCall(toolName, arguments);
            ObjectNode result = mapper.valueToTree(toolResult);
            return buildJsonRpcResult(id, result);
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling tools call", e);
            return buildJsonRpcError(id, -32602, "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling tools call", e);
            // Tool execution error — return as a tool result with isError=true
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textContent = content.addObject();
            textContent.put("type", "text");
            textContent.put("text", "Error: " + e.getMessage());
            result.put("isError", true);
            return buildJsonRpcResult(id, result);
        }
    }

    /**
     * Build a JSON-RPC success response envelope.
     */
    ObjectNode buildJsonRpcResult(JsonNode id, JsonNode result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        Context.getCurrentLogger().log(Level.INFO, "Building JSON-RPC result for id: " + id);

        if (id != null) {
            envelope.set("id", id);
        }
        
        envelope.set("result", result);
        return envelope;
    }

    /**
     * Build a JSON-RPC error response envelope.
     */
    ObjectNode buildJsonRpcError(JsonNode id, int code, String message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        Context.getCurrentLogger().log(Level.INFO, "Building JSON-RPC error for id: " + id);

        if (id != null) {
            envelope.set("id", id);
        } else {
            envelope.putNull("id");
        }

        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return envelope;
    }

    ObjectMapper getMapper() {
        return mapper;
    }

}
