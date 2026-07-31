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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.handler.McpCallHandler;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.engine.exposes.mcp.model.JsonRpcCodeMapper;
import io.ikanos.engine.exposes.mcp.model.JsonRpcError;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import org.restlet.data.Header;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.restlet.util.Series;

import java.util.Optional;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.HEADER_MISMATCH;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.PARSE_ERROR;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * Restlet ServerResource implementing the MCP Streamable HTTP transport protocol.
 *
 * Handles a single endpoint supporting:
 * <ul>
 * <li>POST: JSON-RPC requests (initialize, tools/list, tools/call)</li>
 * <li>GET: SSE stream for server-initiated messages (returns 405 - not supported)</li>
 * <li>DELETE: returns 405 - not supported, kept for backwards compatibility</li>
 * </ul>
 *
 * Delegates protocol dispatch to {@link ProtocolDispatcher} and adds HTTP-specific concerns:
 * session management, HTTP status codes, content types.
 */
public class McpServerResource extends ServerResource {

    @Post("json")
    public Representation handlePost(Representation entity) {
        ProtocolDispatcher dispatcher = getDispatcher();
        ObjectMapper mapper = dispatcher.getMapper();

        try {
            String body = (entity != null) ? entity.getText() : null;

            if (body == null || body.isBlank()) {
                getLogger().log(Level.WARNING,
                        "Error processing request. Missing or empty body");
                ObjectNode error = buildJsonRpcError(null, PARSE_ERROR.getCode(),
                        "Parse error: empty body");
                setStatusCode(PARSE_ERROR);
                return toJsonRepresentation(mapper, error);
            }

            JsonNode root = mapper.readTree(body);
            Series<Header> headers = getRequest().getHeaders();
            Optional<String> error = validateHeaders(headers, root);
            if (error.isPresent()) {
                setStatusCode(HEADER_MISMATCH);
                return toJsonRepresentation(mapper, buildJsonRpcError(root.get("id"), HEADER_MISMATCH.getCode(), error.get()));
            }

            // Extract W3C trace context (params._meta takes precedence over the inbound HTTP
            // traceparent header — see McpMetaTraceContextBridge), create the SERVER span,
            // populate MDC, and delegate to the shared protocol dispatcher.
            DispatchResult result = dispatcher.dispatchWithTracing(root, getRequest());
            if (result.isOnError()) {
                setStatusCode(result.rpcError());
            }

            if (result.responseBody() != null) {
                return toJsonRepresentation(mapper, result.responseBody());
            } else {
                // Notification — no response body
                setStatus(Status.SUCCESS_ACCEPTED);
                return new StringRepresentation("");
            }
        } catch (JsonProcessingException e) {
            getLogger().log(Level.SEVERE, "Error processing request", e);
            setStatusCode(PARSE_ERROR);
            ObjectNode error = buildJsonRpcError(null, PARSE_ERROR.getCode(),
                    "Parse error: " + e.getMessage());
            try {
                return toJsonRepresentation(mapper, error);
            } catch (Exception ex) {
                setStatusCode(INTERNAL_ERROR);
                return new StringRepresentation("Internal server error", MediaType.TEXT_PLAIN);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error processing request", e);
            setStatusCode(INTERNAL_ERROR);
            ObjectNode error = buildJsonRpcError(null, INTERNAL_ERROR.getCode(),
                    "Internal error: " + e.getMessage());
            try {
                return toJsonRepresentation(mapper, error);
            } catch (Exception ex) {
                return new StringRepresentation("Internal server error", MediaType.TEXT_PLAIN);
            }
        }
    }

    /**
     * Sets the HTTP status based on the JsonRpc error.
     * @param rpcError the {@link JsonRpcError}.
     */
    private void setStatusCode(JsonRpcError rpcError) {
        int httpStatus = JsonRpcCodeMapper.getHttpStatusCodeOfJsonRpcCode(rpcError);
        if (httpStatus != -1) {
            setStatus(Status.valueOf(httpStatus));
        }
    }

    private Optional<String> validateHeaders(Series<Header> headers, JsonNode requestBody) {
        // 1. Validate the presence of the mandatory headers
        // The Mcp-Method is required on all requests and allows us to get the mandatory headers for the relevant method
        String mcpMethod = McpHeader.MCP_METHOD.getHeaderValue(headers);
        Optional<String> error = validateHeader(McpHeader.MCP_METHOD, mcpMethod, headers, requestBody);
        if (error.isPresent()) {
            return error;
        }

        McpCallHandler methodHandler = getDispatcher().getMethodHandler(mcpMethod);
        if (methodHandler != null) { // The absence of a handler will be handled by the dispatcher
            for (var header : methodHandler.getRequiredHeaders()) {
                try {
                    Optional<String> header1 = validateHeader(header, mcpMethod, headers, requestBody);
                    if (header1.isPresent()) {
                        return header1;
                    }
                } catch (IllegalArgumentException ignored) {
                    return Optional.of("The value of the header %s contains invalid characters".formatted(header.getHeaderName()));
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> validateHeader(McpHeader header,
                                            String mcpMethod,
                                            Series<Header> headers,
                                            JsonNode requestBody) {
        String headerValue = header.getHeaderValue(headers);
        if (headerValue == null) {
            return Optional.of("Missing mandatory header: " + header.getHeaderName());
        } else {
            String bodyValue = header.extractValueFromRequestBody(mcpMethod, requestBody);
            if (!headerValue.equals(bodyValue)) {
                return Optional.of("Header mismatch: %s header value '%s' does not match body value '%s'".formatted(header.getHeaderName(),
                        headerValue, bodyValue));
            }
        }

        return Optional.empty();
    }

    @Delete
    public Representation handleDelete() {
        setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
        return new StringRepresentation("DELETE not supported", MediaType.TEXT_PLAIN);
    }

    @Get
    public Representation handleGet() {
        setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
        return new StringRepresentation("GET not supported", MediaType.TEXT_PLAIN);
    }

    private ProtocolDispatcher getDispatcher() {
        return (ProtocolDispatcher) getContext().getAttributes().get("dispatcher");
    }

    private Representation toJsonRepresentation(ObjectMapper mapper, ObjectNode body)
            throws JsonProcessingException {
        String json = mapper.writeValueAsString(body);
        return new StringRepresentation(json, MediaType.APPLICATION_JSON);
    }
}
