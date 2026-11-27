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

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The download side of the MCP files extension (experimental) over the Streamable HTTP
 * transport, end to end: a binary upstream response and a presigned URL become download targets
 * for a client that declares {@code download}, and the hosted link serves the bytes once, behind
 * the same bearer authentication as the MCP endpoint.
 */
class McpFileDownloadsIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String TOKEN = "files-secret-token";
    private static final String PRESIGNED = "https://storage.example.com/q3.csv?sig=abc";

    private final byte[] photo = new byte[4096];
    private Component upstream;
    private McpServerAdapter adapter;
    private HttpClient http;
    private String baseUrl;
    private int upstreamPort;
    private String authHeader = "Authorization";
    private String authValue = "Bearer " + TOKEN;
    private int nextId = 1;

    @BeforeEach
    void start() throws Exception {
        new Random(7).nextBytes(photo);
        upstreamPort = freePort();
        upstream = upstream(upstreamPort);
        upstream.start();
        http = HttpClient.newHttpClient();
        startAdapter("""
                    authentication:
                      type: bearer
                      token: "%s"
                """.formatted(TOKEN));
    }

    /** Starts the MCP adapter on a free port, behind {@code authentication} (YAML lines). */
    private void startAdapter(String authentication) throws Exception {
        int port = freePort();
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: photos
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      photo:
                        path: /photos/binary
                        operations:
                          download:
                            method: GET
                            outputRawFormat: binary
                            outputMediaType: "image/png"
                      export:
                        path: /exports/q3
                        operations:
                          export:
                            method: GET
                  exposes:
                  - type: mcp
                    address: 127.0.0.1
                    port: %d
                    namespace: photos-mcp
                %s
                    files:
                      downloads:
                        inlineThreshold: "1KiB"
                    tools:
                      get-photo:
                        description: Get the photo bytes.
                        call: photos.download
                      export-orders:
                        description: Export the orders of Q3.
                        call: photos.export
                        outputParameters:
                          - type: object
                            mapping: "$"
                            properties:
                              url:
                                type: string
                                mapping: "$.signedUrl"
                              size:
                                type: number
                                mapping: "$.contentLength"
                        download:
                          url: url
                          size: size
                """.formatted(VersionHelper.getSchemaVersion(), upstreamPort, port,
                authentication.stripTrailing()), IkanosSpec.class);
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().getFirst();
        adapter.start();
        baseUrl = "http://127.0.0.1:" + port + "/";
    }

    @AfterEach
    void stop() throws Exception {
        http.close();
        adapter.stop();
        upstream.stop();
    }

    @Test
    void serverDiscoverShouldAdvertiseTheFilesExtensionWithTheInlineLimitOnly() throws Exception {
        JsonNode result = rpc("server/discover", null, JSON.createObjectNode(), false);

        JsonNode files = result.path("capabilities").path("extensions")
                .path("io.modelcontextprotocol/files");
        assertTrue(files.isObject(), result.toString());
        assertEquals(java.util.List.of("maxInlineBytes"),
                java.util.List.copyOf(files.propertyNames()),
                "download is a client setting, out-of-band uploads come later: " + result);
        assertEquals(1024 * 1024, files.path("maxInlineBytes").asLong());
    }

    @Test
    void binaryToolShouldStayInlineBehindAHeaderApiKey() throws Exception {
        adapter.stop();
        startAdapter("""
                    authentication:
                      type: apikey
                      placement: header
                      key: X-API-Key
                      value: "%s"
                """.formatted(TOKEN));
        authHeader = "X-API-Key";
        authValue = TOKEN;

        JsonNode result = callTool("get-photo", true);

        assertTrue(result.path("_meta").path("io.modelcontextprotocol/files").isMissingNode(),
                "A client resends only Authorization, so no link: " + result);
        assertEquals(Base64.getEncoder().encodeToString(photo),
                result.path("content").get(0).path("data").asString());
    }

    @Test
    void binaryToolShouldReturnAHostedDownloadThatServesTheBytesOnce() throws Exception {
        JsonNode result = callTool("get-photo", true);

        JsonNode target = result.path("_meta").path("io.modelcontextprotocol/files")
                .path("downloads").get(0);
        assertEquals("include", target.path("credentials").asString(), result.toString());
        assertEquals(photo.length, target.path("size").asInt());
        assertEquals("image/png", target.path("mimeType").asString());
        assertFalse(result.path("content").toString()
                .contains(Base64.getEncoder().encodeToString(photo).substring(0, 32)),
                "The bytes are not inline: " + result.path("content"));

        URI link = URI.create(baseUrl).resolve(target.path("path").asString().substring(1));
        assertEquals(401, get(link, null).statusCode(), "The link takes the MCP credential");
        HttpResponse<byte[]> download = get(link, TOKEN);
        assertEquals(200, download.statusCode());
        assertArrayEquals(photo, download.body());
        assertEquals("image/png", download.headers().firstValue("Content-Type").orElse(""));
        assertEquals(404, get(link, TOKEN).statusCode(), "A link serves its file once");
    }

    @Test
    void headShouldNotUseUpAHostedLink() throws Exception {
        JsonNode target = callTool("get-photo", true).path("_meta")
                .path("io.modelcontextprotocol/files").path("downloads").get(0);
        URI link = URI.create(baseUrl).resolve(target.path("path").asString().substring(1));

        HttpResponse<Void> head = http.send(HttpRequest.newBuilder(link)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer " + TOKEN).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(405, head.statusCode());

        HttpResponse<byte[]> download = get(link, TOKEN);
        assertEquals(200, download.statusCode());
        assertArrayEquals(photo, download.body());
    }

    @Test
    void binaryToolShouldStayInlineWhenTheRequestDoesNotDeclareDownload() throws Exception {
        JsonNode result = callTool("get-photo", false);

        assertTrue(result.path("_meta").path("io.modelcontextprotocol/files").isMissingNode(),
                result.toString());
        assertEquals(Base64.getEncoder().encodeToString(photo),
                result.path("content").get(0).path("data").asString());
    }

    @Test
    void presignedUrlShouldBecomeADownloadTargetWithCredentialsOmit() throws Exception {
        JsonNode result = callTool("export-orders", true);

        JsonNode target = result.path("_meta").path("io.modelcontextprotocol/files")
                .path("downloads").get(0);
        assertEquals(PRESIGNED, target.path("url").asString(), result.toString());
        assertEquals("omit", target.path("credentials").asString());
        assertEquals(209715200L, target.path("size").asLong());
        assertFalse(result.path("content").toString().contains(PRESIGNED),
                "A target is never in content: " + result.path("content"));
    }

    @Test
    void presignedUrlShouldStayInTheOutputWhenTheRequestDoesNotDeclareDownload()
            throws Exception {
        JsonNode result = callTool("export-orders", false);

        assertTrue(result.path("_meta").path("io.modelcontextprotocol/files").isMissingNode(),
                result.toString());
        assertTrue(result.path("content").get(0).path("text").asString().contains(PRESIGNED),
                result.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private JsonNode callTool(String name, boolean download) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.putObject("arguments");
        return rpc("tools/call", name, params, download);
    }

    private JsonNode rpc(String method, String name, ObjectNode params, boolean download)
            throws Exception {
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        if (download) {
            meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
                    .putObject("io.modelcontextprotocol/files").putObject("download");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", nextId++);
        body.put("method", method);
        body.set("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(authHeader, authValue)
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("result");
    }

    private HttpResponse<byte[]> get(URI link, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(link).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Component upstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/photos/binary", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new ByteArrayRepresentation(photo, MediaType.IMAGE_PNG));
                    }
                });
                router.attach("/exports/q3", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new StringRepresentation("{\"signedUrl\": \""
                                + PRESIGNED + "\", \"contentLength\": 209715200}",
                                MediaType.APPLICATION_JSON));
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
