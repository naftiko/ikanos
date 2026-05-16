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

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Restlet ServerResource implementing the MCP Streamable HTTP transport protocol.
 * 
 * Handles a single endpoint supporting:
 * <ul>
 * <li>POST: JSON-RPC requests (initialize, tools/list, tools/call)</li>
 * <li>GET: SSE stream for server-initiated messages (returns 405 - not supported)</li>
 * <li>DELETE: Session termination</li>
 * </ul>
 * 
 * Delegates protocol dispatch to {@link ProtocolDispatcher} and adds HTTP-specific concerns:
 * session management, HTTP status codes, content types.
 */
public class McpServerResource extends ServerResource {

    private static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    @Post("json")
    public Representation handlePost(Representation entity) {
        ProtocolDispatcher dispatcher = getDispatcher();
        ObjectMapper mapper = dispatcher.getMapper();

        // Extract W3C trace context from incoming HTTP headers so downstream
        // spans (tools/call) are linked to the caller's trace.
        io.opentelemetry.context.Context extractedContext =
                OtelRestletBridge.extractContext(getRequest());

        try (Scope ignored = extractedContext.makeCurrent()) {
            return dispatchWithTraceContext(dispatcher, mapper, entity, extractedContext);
        }
    }

    private Representation dispatchWithTraceContext(ProtocolDispatcher dispatcher,
            ObjectMapper mapper, Representation entity,
            io.opentelemetry.context.Context extractedContext) {
        try {
            String body = (entity != null) ? entity.getText() : null;

            if (body == null || body.isBlank()) {
                getLogger().log(Level.WARNING,
                        "Error processing request. Missing or empty body");
                ObjectNode error = dispatcher.buildJsonRpcError(null, -32700,
                        "Parse error: empty body");
                return toJsonRepresentation(mapper, error);
            }

            JsonNode root = mapper.readTree(body);
            String rpcMethod = root.path("method").asText("");

            // Handle initialize specially — create the session
            if ("initialize".equals(rpcMethod)) {
                String sessionId = UUID.randomUUID().toString();
                getActiveSessions().put(sessionId, true);
                getResponse().getHeaders().set(HEADER_MCP_SESSION_ID, sessionId);
            }

            // Handle notifications/initialized — return 202 with no body
            if ("notifications/initialized".equals(rpcMethod)) {
                setStatus(Status.SUCCESS_ACCEPTED);
                return new StringRepresentation("");
            }

            // Create a SERVER span for the inbound MCP request
            String capabilityName = (String) getContext().getAttributes().get("capabilityName");
            Span span = TelemetryBootstrap.get().startServerSpan("mcp", rpcMethod,
                    extractedContext, null, capabilityName);
            try (Scope scope = span.makeCurrent()) {
                // Delegate to the shared protocol dispatcher
                ObjectNode result = dispatcher.dispatch(root);

                if (result != null) {
                    return toJsonRepresentation(mapper, result);
                } else {
                    // Notification — no response body
                    setStatus(Status.SUCCESS_ACCEPTED);
                    return new StringRepresentation("");
                }
            } catch (Exception e) {
                TelemetryBootstrap.recordError(span, e);
                throw e;
            } finally {
                TelemetryBootstrap.endSpan(span);
            }

        } catch (JsonProcessingException e) {
            getLogger().log(Level.SEVERE, "Error processing request", e);
            ObjectNode error = dispatcher.buildJsonRpcError(null, -32700,
                    "Parse error: " + e.getMessage());
            try {
                return toJsonRepresentation(mapper, error);
            } catch (Exception ex) {
                setStatus(Status.SERVER_ERROR_INTERNAL);
                return new StringRepresentation("Internal server error", MediaType.TEXT_PLAIN);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error processing request", e);
            ObjectNode error = dispatcher.buildJsonRpcError(null, -32603,
                    "Internal error: " + e.getMessage());
            try {
                return toJsonRepresentation(mapper, error);
            } catch (Exception ex) {
                setStatus(Status.SERVER_ERROR_INTERNAL);
                return new StringRepresentation("Internal server error", MediaType.TEXT_PLAIN);
            }
        }
    }

    @Delete
    public Representation handleDelete() {
        String sessionId =
                getRequest().getHeaders().getFirstValue(HEADER_MCP_SESSION_ID);
        if (sessionId != null) {
            getActiveSessions().remove(sessionId);
        }
        setStatus(Status.SUCCESS_OK);
        return emptyOkRepresentation();
    }

    @Get
    public Representation handleGet() {
        setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
        return new StringRepresentation("GET not supported", MediaType.TEXT_PLAIN);
    }

    /**
     * Returns a representation that Restlet treats as available (preventing auto-204 adjustment)
     * while carrying no meaningful content. This preserves HTTP 200 for DELETE responses,
     * matching the original Jetty transport behavior.
     */
    private Representation emptyOkRepresentation() {
        StringRepresentation repr = new StringRepresentation("", MediaType.APPLICATION_JSON) {
            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        return repr;
    }

    private ProtocolDispatcher getDispatcher() {
        return (ProtocolDispatcher) getContext().getAttributes().get("dispatcher");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> getActiveSessions() {
        return (Map<String, Boolean>) getContext().getAttributes().get("activeSessions");
    }

    private Representation toJsonRepresentation(ObjectMapper mapper, ObjectNode body)
            throws JsonProcessingException {
        String json = mapper.writeValueAsString(body);
        return new StringRepresentation(json, MediaType.APPLICATION_JSON);
    }
}
