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
import java.util.logging.Level;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.restlet.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Jetty Handler implementing the MCP Streamable HTTP transport protocol.
 * 
 * Handles a single endpoint supporting:
 * - POST: JSON-RPC requests (initialize, tools/list, tools/call)
 * - GET: SSE stream for server-initiated messages (returns 405 - not supported)
 * - DELETE: Session termination
 * 
 * Delegates protocol dispatch to {@link McpProtocolDispatcher} and adds
 * HTTP-specific concerns: session management, HTTP status codes, content types.
 */
public class JettyMcpStreamableHandler extends Handler.Abstract {

    private static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    private final McpProtocolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final Map<String, Boolean> activeSessions;

    public JettyMcpStreamableHandler(McpServerAdapter adapter) {
        this.dispatcher = new McpProtocolDispatcher(adapter);
        this.mapper = dispatcher.getMapper();
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
                Context.getCurrentLogger().log(Level.WARNING, "Error processing request. Missing or empty body");
                ObjectNode error = dispatcher.buildJsonRpcError(null, -32700,
                        "Parse error: empty body");
                writeJson(response, callback, 200, error);
                return true;
            }

            JsonNode root = mapper.readTree(body);
            String rpcMethod = root.path("method").asText("");

            // Handle initialize specially — we need to create the session
            if ("initialize".equals(rpcMethod)) {
                String sessionId = UUID.randomUUID().toString();
                activeSessions.put(sessionId, true);
                response.getHeaders().put(HEADER_MCP_SESSION_ID, sessionId);
            }

            // Handle notifications/initialized — return 202 with no body
            if ("notifications/initialized".equals(rpcMethod)) {
                response.setStatus(202);
                response.write(true, null, callback);
                return true;
            }

            // Delegate to the shared protocol dispatcher
            ObjectNode result = dispatcher.dispatch(root);

            if (result != null) {
                writeJson(response, callback, 200, result);
            } else {
                // Notification — no response body
                response.setStatus(202);
                response.write(true, null, callback);
            }

            return true;
        } catch (JsonProcessingException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);
            ObjectNode error = dispatcher.buildJsonRpcError(null, -32700,
                    "Parse error: " + e.getMessage());
            try {
                writeJson(response, callback, 200, error);
            } catch (Exception ex) {
                writeError(response, callback, 500, "Internal server error");
            }
            return true;
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);
            ObjectNode error = dispatcher.buildJsonRpcError(null, -32603,
                    "Internal error: " + e.getMessage());
            try {
                writeJson(response, callback, 200, error);
            } catch (Exception ex) {
                writeError(response, callback, 500, "Internal server error");
            }
            return true;
        }
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
