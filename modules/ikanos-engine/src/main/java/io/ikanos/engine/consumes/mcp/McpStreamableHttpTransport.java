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
package io.ikanos.engine.consumes.mcp;

import java.io.IOException;
import java.io.StringReader;
import java.io.BufferedReader;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Preference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.consumes.http.ConsumedAuthentication;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Stateless MCP {@code 2026-07-28} Streamable HTTP transport over the Restlet client stack.
 *
 * <p>Each call is one JSON-RPC POST: no {@code initialize}, no session id, no stream held open.
 * The request carries the protocol version and W3C trace context in {@code params._meta}, and the
 * {@code MCP-Protocol-Version}, {@code Mcp-Method} and (for named calls) {@code Mcp-Name} headers
 * the specification requires to mirror the body. A {@code text/event-stream} reply is read until
 * the JSON-RPC response matching the request id, then closed.</p>
 */
public class McpStreamableHttpTransport {

    /** MCP protocol revision spoken by this client. */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /** {@code _meta} key carrying the protocol version. */
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /** JSON-RPC error code for an unsupported protocol version. */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType EVENT_STREAM = MediaType.valueOf("text/event-stream");

    /** Longest body excerpt quoted in an HTTP-status error message. */
    private static final int MAX_EXCERPT = 200;

    /** Writes W3C trace-context entries into a {@code _meta} object node. */
    private static final TextMapSetter<ObjectNode> META_SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
            carrier.put(key, value);
        }
    };

    private final Client client;
    private final String endpoint;
    private final AuthenticationSpec authentication;
    private final AtomicLong ids = new AtomicLong();

    public McpStreamableHttpTransport(Client client, String endpoint,
            AuthenticationSpec authentication) {
        this.client = client;
        this.endpoint = endpoint;
        this.authentication = authentication;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Send one JSON-RPC request and return its {@code result} object.
     *
     * @param method     the MCP method, e.g. {@code tools/call}
     * @param name       the value for {@code Mcp-Name}, or {@code null}
     * @param params     the request params (a {@code _meta} object is added)
     * @param templates  parameters for credential templates (already merged with bindings)
     * @return the JSON-RPC {@code result}
     * @throws McpClientException on transport, HTTP or JSON-RPC failure
     */
    public JsonNode call(String method, String name, ObjectNode params,
            Map<String, Object> templates) {
        long id = ids.incrementAndGet();
        ObjectNode meta = params.with("_meta");
        meta.put(META_PROTOCOL_VERSION, PROTOCOL_VERSION);
        TelemetryBootstrap.get().getOpenTelemetry().getPropagators().getTextMapPropagator()
                .inject(OtelRestletBridge.currentContext(), meta, META_SETTER);

        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.set("params", params);

        Request request = new Request(Method.POST, endpoint);
        try {
            request.setEntity(JSON.writeValueAsString(envelope), MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new McpClientException("Cannot serialize MCP request", e);
        }
        request.getClientInfo().getAcceptedMediaTypes()
                .add(new Preference<>(MediaType.APPLICATION_JSON));
        request.getClientInfo().getAcceptedMediaTypes().add(new Preference<>(EVENT_STREAM));
        request.getHeaders().set("MCP-Protocol-Version", PROTOCOL_VERSION);
        request.getHeaders().set("Mcp-Method", method);
        if (name != null) {
            request.getHeaders().set("Mcp-Name", name);
        }
        ConsumedAuthentication.apply(authentication, null, request, endpoint, templates);
        OtelRestletBridge.injectContext(request);

        Response response = client.handle(request);
        return unwrap(method, id, response);
    }

    /** Extract the JSON-RPC result, or throw with a precise message. Package-private for tests. */
    JsonNode unwrap(String method, long id, Response response) {
        String body;
        try {
            body = response.getEntity() != null ? response.getEntity().getText() : null;
        } catch (IOException e) {
            throw new McpClientException("Cannot read MCP response", e);
        }
        int httpStatus = response.getStatus() != null ? response.getStatus().getCode() : 0;
        boolean httpOk = httpStatus >= 200 && httpStatus < 300;

        JsonNode rpc = null;
        if (body != null && !body.isBlank()) {
            MediaType type = response.getEntity().getMediaType();
            try {
                rpc = type != null && type.equals(EVENT_STREAM, true)
                        ? fromEventStream(body, id)
                        : parse(body);
            } catch (McpClientException notJsonRpc) {
                if (httpOk) {
                    throw notJsonRpc;
                }
                // Non-2xx with a body that is not JSON-RPC (e.g. a proxy's HTML page): the HTTP
                // status is the real cause, reported below.
            }
        }

        if (rpc != null && rpc.has("error")) {
            JsonNode error = rpc.get("error");
            int code = error.path("code").asInt();
            String message = error.path("message").asText("");
            if (code == UNSUPPORTED_PROTOCOL_VERSION) {
                throw new McpClientException("Upstream MCP server at " + endpoint
                        + " does not support protocol " + PROTOCOL_VERSION + " (" + message
                        + "). Only stateless upstreams are supported.");
            }
            throw new McpClientException("Upstream MCP " + method + " failed: " + code + " "
                    + message);
        }
        if (!httpOk) {
            throw new McpClientException("Upstream MCP server returned HTTP " + httpStatus
                    + " for " + method + excerpt(body));
        }
        if (rpc == null || !rpc.has("result")) {
            throw new McpClientException("Upstream MCP " + method
                    + " returned no JSON-RPC result");
        }
        return rpc.get("result");
    }

    /** A short, bounded, single-line excerpt of a response body, or an empty string. */
    static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return ": " + (flat.length() > MAX_EXCERPT
                ? flat.substring(0, MAX_EXCERPT) + "..."
                : flat);
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new McpClientException("Upstream MCP response is not JSON", e);
        }
    }

    /** Read SSE events until the JSON-RPC message whose id matches. */
    static JsonNode fromEventStream(String body, long id) {
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    JsonNode message = matching(data, id);
                    if (message != null) {
                        return message;
                    }
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
        } catch (IOException e) {
            throw new McpClientException("Cannot read MCP event stream", e);
        }
        JsonNode last = matching(data, id);
        if (last != null) {
            return last;
        }
        throw new McpClientException("Upstream MCP event stream ended without a response");
    }

    private static JsonNode matching(StringBuilder data, long id) {
        if (data.isEmpty()) {
            return null;
        }
        JsonNode message = parse(data.toString());
        JsonNode messageId = message.get("id");
        if (messageId != null && messageId.asLong() == id
                && (message.has("result") || message.has("error"))) {
            return message;
        }
        return null;
    }
}
