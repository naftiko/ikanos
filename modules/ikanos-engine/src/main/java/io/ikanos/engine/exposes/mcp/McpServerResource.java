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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.CallerClaims;
import io.ikanos.engine.exposes.mcp.handler.LegacyInitializeHandler;
import io.ikanos.engine.exposes.mcp.files.DownloadStore;
import io.ikanos.engine.exposes.mcp.files.HostedUploads;
import io.ikanos.engine.exposes.mcp.files.McpFileDownloads;
import io.ikanos.engine.exposes.mcp.files.McpFileUploads;
import io.ikanos.engine.exposes.mcp.handler.McpCallHandler;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.engine.exposes.mcp.model.JsonRpcCodeMapper;
import io.ikanos.engine.exposes.mcp.model.JsonRpcError;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.spec.exposes.AuthorizationSpec;
import org.restlet.data.CacheDirective;
import org.restlet.data.Disposition;
import org.restlet.data.Header;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.restlet.resource.ServerResource;
import org.restlet.util.Series;
import tools.jackson.core.JacksonException;
import java.util.List;
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
 * <li>POST: JSON-RPC requests (tools/list, tools/call, server/discover, …). A legacy
 * {@code initialize} is answered with an {@code UnsupportedProtocolVersionError} naming the
 * supported versions.</li>
 * <li>GET: the hosted download links of the files extension ({@code <address>/files/<token>});
 * any other GET, such as an SSE stream for server-initiated messages, gets 405</li>
 * <li>PUT: the hosted upload targets of the files extension ({@code <address>/files/<token>});
 * any other PUT gets 405</li>
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

            // Refuse a token that lacks the tool's (or adapter's) scopes or claims before
            // dispatching: the OAuth2 restlet in front turns the denial into a 403 with an
            // insufficient_scope challenge, so the client can step up its authorization (#999).
            // The challenge keeps the granted scopes the adapter still requires elsewhere. A
            // call of the call meta-tool is checked against each tool it runs, so the first tool
            // the token falls short of gets the challenge a direct call of it would get.
            for (AuthorizationSpec requirement : dispatcher.requirementsFor(root, getRequest())) {
                if (!AccessControl.enforce(requirement, dispatcher.requiredScopes(),
                        getRequest(), getResponse())) {
                    return new StringRepresentation("Forbidden", MediaType.TEXT_PLAIN);
                }
            }

            // Extract W3C trace context (params._meta takes precedence over the inbound HTTP
            // traceparent header — see McpMetaTraceContextBridge), create the SERVER span,
            // populate MDC, and delegate to the shared protocol dispatcher.
            // The caller's validated claims, for {{auth.claims...}} templates of tools and
            // resources (#998): taken from this request here, read once by the handlers.
            DispatchResult result = CallerClaims.runAs(CallerClaims.of(getRequest()),
                    () -> dispatcher.dispatchWithTracing(root, getRequest()));
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
        } catch (JacksonException e) {
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
            setStatusCode(INTERNAL_ERROR);
            ObjectNode error = McpErrors.internalError(null, "Error processing request", e);
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
        // A legacy initialize carries none of the 2026-07-28 headers. Let it through so the
        // dispatcher answers with an UnsupportedProtocolVersionError naming the supported
        // versions, instead of a header error that gives the client no hint (#732).
        if (LegacyInitializeHandler.METHOD_NAME.equals(requestBody.path("method").asString())) {
            return Optional.empty();
        }

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
        ProtocolDispatcher dispatcher = getDispatcher();
        McpFileDownloads downloads = dispatcher.getAdapter().getFileDownloads();
        String token = downloads != null
                ? McpFileDownloads.tokenOf(getReference().getPath()) : null;
        if (token == null) {
            setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            return new StringRepresentation("GET not supported", MediaType.TEXT_PLAIN);
        }
        // Restlet answers HEAD with this method: a HEAD must not use up the one download
        if (!Method.GET.equals(getMethod())) {
            setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            getResponse().getAllowedMethods().add(Method.GET);
            return new StringRepresentation("Only GET serves a download link",
                    MediaType.TEXT_PLAIN);
        }
        return download(downloads, token, dispatcher.ownerOf(getRequest()));
    }

    /**
     * Serves a hosted download link: once, to the principal the tool call that issued it ran
     * for, before it expires. The authentication chain in front has already checked the
     * credential, as for any MCP request. An unknown, used, expired or foreign link gets the same
     * 404, so a link cannot be probed.
     */
    private Representation download(McpFileDownloads downloads, String token, String owner) {
        Optional<DownloadStore.StoredDownload> file = downloads.take(token, owner);
        if (file.isEmpty()) {
            setStatus(Status.CLIENT_ERROR_NOT_FOUND);
            return new StringRepresentation("Download not found or expired",
                    MediaType.TEXT_PLAIN);
        }
        DownloadStore.StoredDownload stored = file.get();
        ByteArrayRepresentation body = new ByteArrayRepresentation(stored.bytes(),
                MediaType.valueOf(stored.mediaType()));
        Disposition disposition = new Disposition(Disposition.TYPE_ATTACHMENT);
        disposition.setFilename(stored.name());
        body.setDisposition(disposition);
        getResponse().setCacheDirectives(List.of(CacheDirective.noStore()));
        setStatus(Status.SUCCESS_OK);
        return body;
    }

    @Put
    public Representation handlePut(Representation entity) {
        ProtocolDispatcher dispatcher = getDispatcher();
        McpFileUploads uploads = dispatcher.getAdapter().getFileUploads();
        HostedUploads hosted = uploads != null ? uploads.hosted() : null;
        String token = hosted != null ? McpFileDownloads.tokenOf(getReference().getPath()) : null;
        if (token == null) {
            setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            return new StringRepresentation("PUT not supported", MediaType.TEXT_PLAIN);
        }
        return upload(hosted, token, dispatcher.ownerOf(getRequest()), entity);
    }

    /**
     * Receives the bytes of a hosted upload target: once, from the principal the tool call that
     * issued it ran for, before it expires, with the declared {@code Content-Type} and exactly
     * the declared size. The authentication chain in front has already checked the credential,
     * as for any MCP request. The body is read up to the declared size and no further. An
     * unknown, expired or foreign target gets the same 404, so a target cannot be probed.
     */
    private Representation upload(HostedUploads hosted, String token, String owner,
            Representation entity) {
        String contentType = getRequest().getHeaders().getFirstValue("Content-Type", true);
        long length = entity != null ? entity.getSize() : 0;
        HostedUploads.Received received;
        java.io.InputStream body = null;
        try {
            body = entity != null ? entity.getStream() : null;
            received = hosted.receive(token, owner, contentType,
                    entity != null && length == Representation.UNKNOWN_SIZE ? -1 : length, body);
        } catch (java.io.IOException e) {
            received = HostedUploads.Received.TOO_SHORT;
        }
        if (received != HostedUploads.Received.STORED) {
            discard(body);
        }
        getResponse().setCacheDirectives(List.of(CacheDirective.noStore()));
        setStatus(Status.valueOf(received.status()));
        if (received == HostedUploads.Received.STORED) {
            return null;
        }
        return new StringRepresentation(switch (received) {
            case NOT_FOUND -> "Upload target not found or expired";
            case ALREADY_WRITTEN -> "Upload target already received its file";
            case LENGTH_REQUIRED -> "Content-Length is required";
            case WRONG_TYPE -> "Content-Type must be the one the upload target lists";
            case TOO_LARGE -> "Body is larger than the declared file size";
            default -> "Body is shorter than the declared file size";
        }, MediaType.TEXT_PLAIN);
    }

    /**
     * Reads and drops what is left of a refused upload's body, up to {@link #DISCARD_LIMIT}, so
     * the connector can send the answer on a connection whose request it has read. A larger body
     * is left unread: the connection then breaks instead of the server reading it.
     */
    private static void discard(java.io.InputStream body) {
        if (body == null) {
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            long left = DISCARD_LIMIT;
            int read;
            while (left > 0 && (read = body.read(buffer, 0,
                    (int) Math.min(buffer.length, left))) != -1) {
                left -= read;
            }
        } catch (java.io.IOException e) {
            // The answer is sent anyway
        }
    }

    /** The most bytes of a refused upload's body read and dropped: 1 MiB. */
    private static final long DISCARD_LIMIT = 1024L * 1024;

    private ProtocolDispatcher getDispatcher() {
        return (ProtocolDispatcher) getContext().getAttributes().get("dispatcher");
    }

    private Representation toJsonRepresentation(ObjectMapper mapper, ObjectNode body) {
        String json = mapper.writeValueAsString(body);
        return new StringRepresentation(json, MediaType.APPLICATION_JSON);
    }
}
