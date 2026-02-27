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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Jetty Handler implementing the MCP Streamable HTTP transport protocol.
 * 
 * Handles a single endpoint supporting:
 * - POST: JSON-RPC requests (initialize, tools/list, tools/call)
 * - GET: SSE stream for server-initiated messages (returns 405 - not supported)
 * - DELETE: Session termination
 * 
 * Uses MCP Java SDK types (McpSchema) for JSON-RPC message serialization.
 */
public class JettyMcpStreamableHandler extends Handler.Abstract {

    private static final String JSONRPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";
    private static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    private final McpServerAdapter adapter;
    private final ObjectMapper mapper;
    private final Map<String, Boolean> activeSessions;

    public JettyMcpStreamableHandler(McpServerAdapter adapter) {
        this.adapter = adapter;
        this.mapper = new ObjectMapper();
        this.activeSessions = new ConcurrentHashMap<>();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String method = request.getMethod();

        switch (method) {
            case "POST":
                return handlePost(request, response, callback);
            case "GET":
                // SSE stream for server-initiated messages — not supported
                writeError(response, callback, 405, "GET not supported");
                return true;
            case "DELETE":
                return handleDelete(request, response, callback);
            default:
                writeError(response, callback, 405, "Method not allowed");
                return true;
        }
    }

    /**
     * Handle POST requests containing JSON-RPC messages.
     */
    private boolean handlePost(Request request, Response response, Callback callback) {
        try {
            String body = Content.Source.asString(request, StandardCharsets.UTF_8);

            if (body == null || body.isBlank()) {
                writeJsonRpcError(response, callback, null, -32700, "Parse error: empty body");
                return true;
            }

            JsonNode root = mapper.readTree(body);
            String jsonrpc = root.path("jsonrpc").asText("");
            JsonNode idNode = root.get("id");
            String rpcMethod = root.path("method").asText("");
            JsonNode params = root.get("params");

            if (!JSONRPC_VERSION.equals(jsonrpc)) {
                writeJsonRpcError(response, callback, idNode, -32600,
                        "Invalid Request: jsonrpc must be '2.0'");
                return true;
            }

            switch (rpcMethod) {
                case "initialize":
                    return handleInitialize(request, response, callback, idNode, params);

                case "notifications/initialized":
                    // Notification — no response needed
                    response.setStatus(202);
                    response.write(true, null, callback);
                    return true;

                case "tools/list":
                    return handleToolsList(request, response, callback, idNode);

                case "tools/call":
                    return handleToolsCall(request, response, callback, idNode, params);

                case "ping":
                    return handlePing(response, callback, idNode);

                default:
                    writeJsonRpcError(response, callback, idNode, -32601,
                            "Method not found: " + rpcMethod);
                    return true;
            }
        } catch (JsonProcessingException e) {
            writeJsonRpcError(response, callback, null, -32700, "Parse error: " + e.getMessage());
            return true;
        } catch (Exception e) {
            writeJsonRpcError(response, callback, null, -32603,
                    "Internal error: " + e.getMessage());
            return true;
        }
    }

    /**
     * Handle MCP initialize request.
     * Returns server capabilities and assigns a session ID.
     */
    private boolean handleInitialize(Request request, Response response, Callback callback,
            JsonNode id, JsonNode params) throws Exception {

        String sessionId = UUID.randomUUID().toString();
        activeSessions.put(sessionId, true);

        // Build InitializeResult
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

        // Set session header
        response.getHeaders().put(HEADER_MCP_SESSION_ID, sessionId);

        writeJsonRpcResult(response, callback, id, result);
        return true;
    }

    /**
     * Handle tools/list request.
     * Returns the list of available tools built from the spec.
     */
    private boolean handleToolsList(Request request, Response response, Callback callback,
            JsonNode id) throws Exception {

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

        writeJsonRpcResult(response, callback, id, result);
        return true;
    }

    /**
     * Handle tools/call request.
     * Dispatches tool invocation to the McpToolHandler.
     */
    private boolean handleToolsCall(Request request, Response response, Callback callback,
            JsonNode id, JsonNode params) throws Exception {

        if (params == null) {
            writeJsonRpcError(response, callback, id, -32602, "Invalid params: missing params");
            return true;
        }

        String toolName = params.path("name").asText("");
        JsonNode argumentsNode = params.get("arguments");

        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = argumentsNode != null
                ? mapper.treeToValue(argumentsNode, Map.class)
                : new ConcurrentHashMap<>();

        try {
            McpSchema.CallToolResult toolResult =
                    adapter.getToolHandler().handleToolCall(toolName, arguments);

            ObjectNode result = mapper.valueToTree(toolResult);
            writeJsonRpcResult(response, callback, id, result);
        } catch (IllegalArgumentException e) {
            writeJsonRpcError(response, callback, id, -32602,
                    "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            // Tool execution error — return as a tool result with isError=true
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textContent = content.addObject();
            textContent.put("type", "text");
            textContent.put("text", "Error: " + e.getMessage());
            result.put("isError", true);
            writeJsonRpcResult(response, callback, id, result);
        }

        return true;
    }

    /**
     * Handle ping request.
     */
    private boolean handlePing(Response response, Callback callback, JsonNode id)
            throws Exception {
        writeJsonRpcResult(response, callback, id, mapper.createObjectNode());
        return true;
    }

    /**
     * Handle DELETE requests for session termination.
     */
    private boolean handleDelete(Request request, Response response, Callback callback) {
        String sessionId = request.getHeaders().get(HEADER_MCP_SESSION_ID);
        if (sessionId != null) {
            activeSessions.remove(sessionId);
        }
        response.setStatus(200);
        response.write(true, null, callback);
        return true;
    }

    /**
     * Write a JSON-RPC success response.
     */
    private void writeJsonRpcResult(Response response, Callback callback, JsonNode id,
            JsonNode result) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        if (id != null) {
            envelope.set("id", id);
        }
        envelope.set("result", result);

        writeJson(response, callback, 200, envelope);
    }

    /**
     * Write a JSON-RPC error response.
     */
    private void writeJsonRpcError(Response response, Callback callback, JsonNode id, int code,
            String message) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("jsonrpc", JSONRPC_VERSION);
            if (id != null) {
                envelope.set("id", id);
            } else {
                envelope.putNull("id");
            }

            ObjectNode error = envelope.putObject("error");
            error.put("code", code);
            error.put("message", message);

            writeJson(response, callback, 200, envelope);
        } catch (Exception e) {
            writeError(response, callback, 500, "Internal server error");
        }
    }

    /**
     * Write a JSON response body.
     */
    private void writeJson(Response response, Callback callback, int status, ObjectNode body)
            throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(body);
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    /**
     * Write a plain text error response.
     */
    private void writeError(Response response, Callback callback, int status, String message) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

}
