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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Out-of-band file uploads through upstream presigned URLs (draft MCP files extension,
 * {@code upload_required}), end to end over real HTTP. A stub object store issues presigned URLs
 * on itself (on loopback), stores what is {@code PUT} there and answers {@code HEAD}; the test
 * plays the client that sends the bytes and retries the call.
 */
class McpFileUploadsIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String FILES = "io.modelcontextprotocol/files";
    private static final byte[] PDF = "%PDF-1.7 a scanned page".getBytes(StandardCharsets.UTF_8);

    private record Stored(byte[] bytes, String type) {
    }

    private final Map<String, Stored> objects = new ConcurrentHashMap<>();
    private final List<String> imported = new ArrayList<>();
    private Capability capability;
    private HttpServer upstream;
    private HttpClient http;
    private String mcpUrl;
    private int nextId = 1;

    @BeforeEach
    void start() throws Exception {
        int upstreamPort = freePort();
        upstream = upstream(upstreamPort);
        upstream.start();

        int mcpPort = freePort();
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  aggregates:
                  - namespace: documents
                    flows:
                      import-scan:
                        description: Import a scan, stored in the object store first.
                        semantics:
                          idempotent: false
                        inputParameters:
                          scan:
                            type: file
                            accept: ["application/pdf"]
                            maxSize: 1MiB
                            required: true
                            description: The scan to import.
                        upload:
                          presign:
                            call: storage.create-upload-url
                            with:
                              key: "{{scan.uploadId}}"
                              contentType: "{{scan.mimeType}}"
                            url: "$.uploadUrl"
                            headers: "$.headers"
                            expiresAt: "$.expiresAt"
                          verify:
                            call: storage.head-object
                            with:
                              key: "{{scan.uploadId}}"
                        call: records.import
                        with:
                          key: "{{scan.uploadId}}"
                      refresh-scan:
                        description: Store a scan again, safe to repeat.
                        semantics:
                          idempotent: true
                        inputParameters:
                          scan:
                            type: file
                            required: true
                            description: The scan to store.
                        upload:
                          presign:
                            call: storage.create-upload-url
                            with:
                              key: "{{scan.uploadId}}"
                              contentType: "{{scan.mimeType}}"
                            url: "$.uploadUrl"
                            headers: "$.headers"
                          verify:
                            call: storage.head-object
                            with:
                              key: "{{scan.uploadId}}"
                        call: records.import
                        with:
                          key: "{{scan.uploadId}}"
                  consumes:
                  - namespace: storage
                    type: http
                    baseUri: http://127.0.0.1:%d
                    resources:
                      upload-urls:
                        path: /upload-urls/{{key}}
                        operations:
                          create-upload-url:
                            method: POST
                            inputParameters:
                              contentType:
                                in: query
                      objects:
                        path: /objects/{{key}}
                        operations:
                          head-object:
                            method: HEAD
                  - namespace: records
                    type: http
                    baseUri: http://127.0.0.1:%d
                    resources:
                      imports:
                        path: /imports
                        operations:
                          import:
                            method: POST
                            inputParameters:
                              key:
                                in: query
                  exposes:
                  - type: mcp
                    address: 127.0.0.1
                    port: %d
                    namespace: documents-mcp
                    files:
                      uploads:
                        maxInlineBytes: 1KiB
                    tools:
                      import-scan:
                        description: Import a scan.
                        ref: documents.import-scan
                      refresh-scan:
                        description: Store a scan again.
                        ref: documents.refresh-scan
                """.formatted(VersionHelper.getSchemaVersion(), upstreamPort, upstreamPort,
                mcpPort), IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
        http = HttpClient.newHttpClient();
        mcpUrl = "http://127.0.0.1:" + mcpPort + "/";
    }

    @AfterEach
    void stop() throws Exception {
        http.close();
        capability.stop();
        upstream.stop(0);
    }

    @Test
    void serverDiscoverShouldAdvertiseUpload() throws Exception {
        JsonNode files = rpc("server/discover", null, JSON.createObjectNode(), true)
                .path("result").path("capabilities").path("extensions").path(FILES);

        assertTrue(files.path("upload").isObject(), files.toString());
        assertEquals(1024, files.path("maxInlineBytes").asInt(), files.toString());
    }

    @Test
    void callWithoutDataShouldReturnAnUploadTargetFromThePresignCall() throws Exception {
        JsonNode result = callTool("import-scan", scanArguments(), null, true).path("result");

        assertEquals("upload_required", result.path("resultType").asString(), result.toString());
        assertEquals(1, result.path("uploads").size());
        JsonNode target = result.path("uploads").get(0);
        assertEquals("scan-1", target.path("fileId").asString());
        assertTrue(target.path("url").asString().startsWith("http://127.0.0.1:"),
                target.toString());
        assertEquals("omit", target.path("credentials").asString());
        assertEquals("application/pdf", target.path("headers").path("Content-Type").asString());
        assertFalse(target.path("expiresAt").asString().isEmpty(), target.toString());
        assertFalse(result.path("requestState").asString().isEmpty());
        assertTrue(imported.isEmpty(), "The flow must not run before the upload");
    }

    @Test
    void retryAfterTheUploadShouldRunTheFlowWithTheStoredObject() throws Exception {
        JsonNode first = callTool("import-scan", scanArguments(), null, true).path("result");
        JsonNode target = first.path("uploads").get(0);
        assertEquals(200, put(target, PDF));

        JsonNode result = callTool("import-scan", scanArguments(),
                first.path("requestState").asString(), true).path("result");

        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals(1, imported.size());
        String key = imported.get(0);
        assertArrayEquals(PDF, objects.get(key).bytes());
        assertTrue(target.path("url").asString().endsWith("/objects/" + key));
    }

    @Test
    void retryWithoutTheUploadShouldBeAnErrorWithoutRunningTheFlow() throws Exception {
        JsonNode first = callTool("import-scan", scanArguments(), null, true).path("result");

        JsonNode result = callTool("import-scan", scanArguments(),
                first.path("requestState").asString(), true).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(imported.isEmpty());
    }

    @Test
    void retryAfterAnUploadOfAnotherSizeShouldBeAnError() throws Exception {
        JsonNode first = callTool("import-scan", scanArguments(), null, true).path("result");
        put(first.path("uploads").get(0), "%PDF-short".getBytes(StandardCharsets.UTF_8));

        JsonNode result = callTool("import-scan", scanArguments(),
                first.path("requestState").asString(), true).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(imported.isEmpty());
    }

    @Test
    void secondRetryOfANonIdempotentFlowShouldBeRefused() throws Exception {
        JsonNode first = callTool("import-scan", scanArguments(), null, true).path("result");
        put(first.path("uploads").get(0), PDF);
        String state = first.path("requestState").asString();
        callTool("import-scan", scanArguments(), state, true);

        JsonNode replay = callTool("import-scan", scanArguments(), state, true);

        assertEquals(-32602, replay.path("error").path("code").asInt(), replay.toString());
        assertEquals(1, imported.size(), "The flow must run once");
    }

    @Test
    void secondRetryOfAnIdempotentFlowShouldRunItAgain() throws Exception {
        JsonNode first = callTool("refresh-scan", scanArguments(), null, true).path("result");
        put(first.path("uploads").get(0), PDF);
        String state = first.path("requestState").asString();
        callTool("refresh-scan", scanArguments(), state, true);

        JsonNode replay = callTool("refresh-scan", scanArguments(), state, true).path("result");

        assertFalse(replay.path("isError").asBoolean(), replay.toString());
        assertEquals(2, imported.size());
    }

    @Test
    void retryWithOtherArgumentsShouldBeRefused() throws Exception {
        JsonNode first = callTool("import-scan", scanArguments(), null, true).path("result");
        put(first.path("uploads").get(0), PDF);
        ObjectNode changed = scanArguments();
        ((ObjectNode) changed.path("scan")).put("name", "other.pdf");

        JsonNode retry = callTool("import-scan", changed, first.path("requestState").asString(),
                true);

        assertEquals(-32602, retry.path("error").path("code").asInt(), retry.toString());
        assertTrue(imported.isEmpty());
    }

    @Test
    void callWithoutDataFromAClientWithoutUploadShouldNeedTheCapability() throws Exception {
        JsonNode response = callTool("import-scan", scanArguments(), null, false);

        assertEquals(-32021, response.path("error").path("code").asInt(), response.toString());
        assertTrue(response.path("error").path("data").path("requiredCapabilities")
                .path("extensions").path(FILES).path("upload").isObject(), response.toString());
    }

    @Test
    void fileOfATypeTheInputDoesNotAcceptShouldBeAnErrorBeforeAnyTarget() throws Exception {
        ObjectNode arguments = scanArguments();
        ((ObjectNode) arguments.path("scan")).put("mimeType", "text/html");

        JsonNode result = callTool("import-scan", arguments, null, true).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(objects.isEmpty());
    }

    @Test
    void inlineFileShouldBeStoredThroughThePresignedUrlBeforeTheFlowRuns() throws Exception {
        ObjectNode arguments = scanArguments();
        ((ObjectNode) arguments.path("scan")).put("data",
                Base64.getEncoder().encodeToString(PDF));

        JsonNode result = callTool("import-scan", arguments, null, true).path("result");

        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals(1, imported.size());
        assertArrayEquals(PDF, objects.get(imported.get(0)).bytes());
        assertEquals("application/pdf", objects.get(imported.get(0)).type());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static ObjectNode scanArguments() {
        ObjectNode scan = JSON.createObjectNode();
        scan.put("id", "scan-1");
        scan.put("name", "page.pdf");
        scan.put("mimeType", "application/pdf");
        scan.put("size", PDF.length);
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("scan", scan);
        return arguments;
    }

    /** Sends the bytes to a target, as a client would. */
    private int put(JsonNode target, byte[] bytes) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(target.path("url").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        target.path("headers").properties()
                .forEach(header -> request.header(header.getKey(), header.getValue().asString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private JsonNode callTool(String name, ObjectNode arguments, String requestState,
            boolean upload) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments.deepCopy());
        if (requestState != null) {
            params.put("requestState", requestState);
        }
        return rpc("tools/call", name, params, upload);
    }

    /** The JSON-RPC response: its {@code result} or its {@code error}. */
    private JsonNode rpc(String method, String name, ObjectNode params, boolean upload)
            throws Exception {
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode files = meta.putObject("io.modelcontextprotocol/clientCapabilities")
                .putObject("extensions").putObject(FILES);
        if (upload) {
            files.putObject("upload");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", nextId++);
        body.put("method", method);
        body.set("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(mcpUrl))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    /** The stub object store and records API. */
    private HttpServer upstream(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/upload-urls/", exchange -> {
            String key = exchange.getRequestURI().getPath().substring("/upload-urls/".length());
            String type = query(exchange.getRequestURI(), "contentType");
            ObjectNode answer = JSON.createObjectNode();
            answer.put("uploadUrl", "http://127.0.0.1:" + port + "/objects/" + key);
            answer.putObject("headers").put("Content-Type", type);
            answer.put("expiresAt", "2099-01-01T00:00:00Z");
            respond(exchange, 200, answer.toString().getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/objects/", exchange -> {
            String key = exchange.getRequestURI().getPath().substring("/objects/".length());
            String method = exchange.getRequestMethod();
            if ("PUT".equals(method)) {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                objects.put(key, new Stored(bytes,
                        exchange.getRequestHeaders().getFirst("Content-Type")));
                respond(exchange, 200, new byte[0]);
                return;
            }
            Stored stored = objects.get(key);
            if (stored == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            // A HEAD answer: the stored object's size and type, without its bytes
            exchange.getResponseHeaders().set("Content-Type", stored.type());
            exchange.getResponseHeaders().set("Content-Length",
                    Integer.toString(stored.bytes().length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/imports", exchange -> {
            synchronized (imported) {
                imported.add(query(exchange.getRequestURI(), "key"));
            }
            respond(exchange, 200, "{\"imported\": true}".getBytes(StandardCharsets.UTF_8));
        });
        return server;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body)
            throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static String query(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).equals(name)) {
                    return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
