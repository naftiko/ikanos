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

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Starts an MCP adapter from a fixture on a free local port and sends it 2026-07-28 JSON-RPC
 * requests over HTTP, with the required {@code MCP-*} headers. Close it to stop the adapter.
 */
public final class McpHttpTestClient implements AutoCloseable {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();
    private static final ObjectMapper JSON = IkanosMappers.json();

    private final McpServerAdapter adapter;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private int nextId = 1;

    private McpHttpTestClient(McpServerAdapter adapter, String baseUrl) {
        this.adapter = adapter;
        this.baseUrl = baseUrl;
    }

    /**
     * Starts the first MCP adapter of a fixture on a free port of 127.0.0.1.
     *
     * @param fixture the capability path, relative to the module
     * @return a client bound to the started adapter
     * @throws Exception if the capability cannot be loaded or the server cannot start
     */
    public static McpHttpTestClient start(String fixture) throws Exception {
        IkanosSpec spec = YAML.readValue(new File(fixture), IkanosSpec.class);
        McpServerSpec serverSpec = (McpServerSpec) spec.getCapability().getExposes().stream()
                .filter(McpServerSpec.class::isInstance).findFirst().orElseThrow();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        serverSpec.setPort(port);
        serverSpec.setAddress("127.0.0.1");

        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().stream()
                .filter(McpServerAdapter.class::isInstance).findFirst().orElseThrow();
        adapter.start();
        return new McpHttpTestClient(adapter, "http://127.0.0.1:" + port + "/");
    }

    /** @return the started adapter */
    public McpServerAdapter adapter() {
        return adapter;
    }

    /**
     * Sends {@code tools/list}.
     *
     * @param query the endpoint URL's query string without {@code ?}, or {@code null}
     * @return the raw HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> listTools(String query) throws Exception {
        return send(query, "tools/list", null, JSON.createObjectNode());
    }

    /**
     * Sends {@code tools/call}.
     *
     * @param query the endpoint URL's query string without {@code ?}, or {@code null}
     * @param name the tool name
     * @param arguments the tool arguments
     * @return the raw HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> callTool(String query, String name, ObjectNode arguments)
            throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return send(query, "tools/call", name, params);
    }

    private HttpResponse<String> send(String query, String method, String name,
            ObjectNode params) throws Exception {
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", nextId++);
        body.put("method", method);
        body.set("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(query == null ? baseUrl : baseUrl + "?" + query))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws Exception {
        http.close();
        adapter.stop();
    }
}
