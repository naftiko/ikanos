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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Uploads hosted on the MCP adapter (draft MCP files extension, {@code upload_required} with a
 * {@code path} target and {@code credentials: include}), end to end over real HTTP. The adapter
 * is protected by OAuth 2.1 tokens from a mock authorization server, so two callers have two
 * principals; the test plays the client that {@code PUT}s the bytes on the adapter with its MCP
 * credential and retries the call, and a stub upstream records the body the flow sends it.
 */
class McpHostedUploadsIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String FILES = "io.modelcontextprotocol/files";

    private static RSAKey signingKey;
    private static Server authorizationServer;
    private static int authorizationServerPort;

    private record Received(byte[] body, String type) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private Capability capability;
    private HttpServer upstream;
    private HttpClient http;
    private String mcpUrl;
    private String alice;
    private String mallory;
    private int nextId = 1;

    @BeforeAll
    static void startAuthorizationServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("hosted-uploads").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        authorizationServerPort = freePort();
        String issuer = "http://127.0.0.1:" + authorizationServerPort;
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("issuer", issuer);
        metadata.put("jwks_uri", issuer + "/jwks");
        metadata.put("authorization_endpoint", issuer + "/authorize");
        metadata.put("token_endpoint", issuer + "/token");
        String metadataJson = metadata.toString();
        authorizationServer = new Server(Protocol.HTTP, "127.0.0.1", authorizationServerPort);
        authorizationServer.setNext(new Restlet() {

            @Override
            public void handle(Request request, Response response) {
                String path = request.getResourceRef().getPath();
                if ("/.well-known/oauth-authorization-server".equals(path)) {
                    response.setEntity(metadataJson, MediaType.APPLICATION_JSON);
                } else if ("/jwks".equals(path)) {
                    response.setEntity(jwks, MediaType.APPLICATION_JSON);
                } else {
                    response.setStatus(Status.CLIENT_ERROR_NOT_FOUND);
                }
            }
        });
        authorizationServer.start();
    }

    @AfterAll
    static void stopAuthorizationServer() throws Exception {
        authorizationServer.stop();
    }

    /** Starts the capability, its hosted uploads with this quota and target lifetime. */
    private void start(String quota, String ttl) throws Exception {
        int upstreamPort = freePort();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", upstreamPort), 0);
        upstream.createContext("/reports", exchange -> {
            received.add(new Received(exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            byte[] answer = "{\"stored\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        upstream.start();

        int mcpPort = freePort();
        mcpUrl = "http://127.0.0.1:" + mcpPort + "/mcp";
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  aggregates:
                  - namespace: documents
                    flows:
                      import-report:
                        description: Store a report, sent as the request body.
                        inputParameters:
                          report:
                            type: file
                            accept: ["application/pdf"]
                            maxSize: 64KiB
                            required: true
                            description: The report to store.
                        call: records.store
                  consumes:
                  - namespace: records
                    type: http
                    baseUri: http://127.0.0.1:%d
                    resources:
                      reports:
                        path: /reports
                        operations:
                          store:
                            method: PUT
                            body:
                              type: binary
                              data: "{{report.data}}"
                              contentType: "{{report.mimeType}}"
                  exposes:
                  - type: mcp
                    address: 127.0.0.1
                    port: %d
                    namespace: documents-mcp
                    authentication:
                      type: oauth2
                      authorizationServerUri: "http://127.0.0.1:%d"
                      resource: "%s"
                    files:
                      uploads:
                        maxInlineBytes: 1KiB
                        hosted: true
                        quota: %s
                        ttl: %s
                    tools:
                      import-report:
                        description: Store a report.
                        ref: documents.import-report
                """.formatted(VersionHelper.getSchemaVersion(), upstreamPort, mcpPort,
                authorizationServerPort, mcpUrl, quota, ttl), IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
        http = HttpClient.newHttpClient();
        alice = token("alice");
        mallory = token("mallory");
    }

    @AfterEach
    void stop() throws Exception {
        if (http != null) {
            http.close();
        }
        if (capability != null) {
            capability.stop();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void serverDiscoverShouldAdvertiseUpload() throws Exception {
        start("16KiB", "PT5M");

        JsonNode files = rpc("server/discover", null, JSON.createObjectNode(), alice)
                .path("result").path("capabilities").path("extensions").path(FILES);

        assertTrue(files.path("upload").isObject(), files.toString());
    }

    @Test
    void hostedUploadShouldReachTheUpstreamExactly() throws Exception {
        start("16KiB", "PT5M");
        byte[] pdf = pdf(4096);

        JsonNode first = callTool(arguments(pdf), null, alice).path("result");
        assertEquals("upload_required", first.path("resultType").asString(), first.toString());
        JsonNode target = first.path("uploads").get(0);
        assertEquals("report-1", target.path("fileId").asString());
        assertTrue(target.path("path").asString().matches("/files/[A-Za-z0-9_-]{43}"),
                target.toString());
        assertFalse(target.has("url"), target.toString());
        assertEquals("include", target.path("credentials").asString());
        assertEquals("application/pdf", target.path("headers").path("Content-Type").asString());
        assertFalse(target.path("expiresAt").asString().isEmpty());
        assertTrue(received.isEmpty(), "The flow must not run before the upload");

        assertEquals(204, put(target, pdf, alice));
        JsonNode result = callTool(arguments(pdf), first.path("requestState").asString(), alice)
                .path("result");

        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals(1, received.size());
        assertArrayEquals(pdf, received.get(0).body());
        assertEquals("application/pdf", received.get(0).type());
    }

    @Test
    void retryShouldCheckTheDigestOfTheHostedBytes() throws Exception {
        start("16KiB", "PT5M");
        byte[] pdf = pdf(2048);
        ObjectNode arguments = arguments(pdf);
        ((ObjectNode) arguments.path("report")).putObject("digest").put("algorithm", "sha-256")
                .put("value", FileInputs.digestOf(pdf(1024)));

        JsonNode first = callTool(arguments, null, alice).path("result");
        assertEquals(204, put(first.path("uploads").get(0), pdf, alice));
        JsonNode result = callTool(arguments, first.path("requestState").asString(), alice)
                .path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(received.isEmpty());
    }

    @Test
    void bytesThatAreNotOfTheDeclaredTypeShouldNotRunTheFlow() throws Exception {
        start("16KiB", "PT5M");
        byte[] html = "<html><body>not a pdf at all, but large enough</body></html>".repeat(40)
                .getBytes(StandardCharsets.US_ASCII);

        JsonNode first = callTool(arguments(html), null, alice).path("result");
        assertEquals(204, put(first.path("uploads").get(0), html, alice));
        JsonNode result = callTool(arguments(html), first.path("requestState").asString(), alice)
                .path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(result.path("content").get(0).path("text").asString()
                .contains("does not look like"), result.toString());
        assertTrue(received.isEmpty());
    }

    @Test
    void uploadWithoutCredentialsShouldBeRefusedAndLeaveTheTargetOpen() throws Exception {
        start("16KiB", "PT5M");
        byte[] pdf = pdf(2048);
        JsonNode target = callTool(arguments(pdf), null, alice).path("result").path("uploads")
                .get(0);

        assertEquals(401, put(target, pdf, null));
        assertEquals(204, put(target, pdf, alice));
    }

    @Test
    void anotherPrincipalShouldNeitherUploadNorRetry() throws Exception {
        start("16KiB", "PT5M");
        byte[] pdf = pdf(2048);
        JsonNode first = callTool(arguments(pdf), null, alice).path("result");
        JsonNode target = first.path("uploads").get(0);

        assertEquals(404, put(target, pdf, mallory));
        assertEquals(204, put(target, pdf, alice));
        JsonNode stolen = callTool(arguments(pdf), first.path("requestState").asString(), mallory);

        assertEquals(-32602, stolen.path("error").path("code").asInt(), stolen.toString());
        assertTrue(received.isEmpty());
    }

    @Test
    void filesOverTheQuotaShouldGetAnErrorBeforeAnyTarget() throws Exception {
        start("16KiB", "PT5M");

        JsonNode result = callTool(arguments(pdf(20 * 1024)), null, alice).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(result.path("uploads").isMissingNode(), result.toString());
        assertTrue(result.path("content").get(0).path("text").asString().contains("do not fit"),
                result.toString());
    }

    @Test
    void quotaShouldBeFreedOnceTheCallCompletes() throws Exception {
        start("5KiB", "PT5M");
        byte[] pdf = pdf(4096);
        JsonNode first = callTool(arguments(pdf), null, alice).path("result");
        assertTrue(callTool(arguments(pdf), null, alice).path("result").path("isError")
                .asBoolean(), "The quota is taken by the first call");

        put(first.path("uploads").get(0), pdf, alice);
        callTool(arguments(pdf), first.path("requestState").asString(), alice);

        assertEquals("upload_required", callTool(arguments(pdf), null, alice).path("result")
                .path("resultType").asString());
    }

    @Test
    void fileOverItsMaxSizeShouldGetAnErrorBeforeAnyTarget() throws Exception {
        start("1MiB", "PT5M");

        JsonNode result = callTool(arguments(pdf(65 * 1024)), null, alice).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(result.path("content").get(0).path("text").asString().contains("maxSize"),
                result.toString());
    }

    @Test
    void uploadLargerThanTheDeclaredSizeShouldBeRefused() throws Exception {
        start("16KiB", "PT5M");
        byte[] pdf = pdf(2048);
        JsonNode target = callTool(arguments(pdf), null, alice).path("result").path("uploads")
                .get(0);

        assertEquals(413, put(target, pdf(4096), alice));
        assertEquals(400, put(target, pdf(1024), alice));
        assertEquals(204, put(target, pdf, alice));
    }

    @Test
    void uploadAfterTheTargetExpiredShouldBeRefused() throws Exception {
        start("16KiB", "PT1S");
        byte[] pdf = pdf(2048);
        JsonNode first = callTool(arguments(pdf), null, alice).path("result");
        Thread.sleep(2100);

        assertEquals(404, put(first.path("uploads").get(0), pdf, alice));
        JsonNode result = callTool(arguments(pdf), first.path("requestState").asString(), alice)
                .path("result");
        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(received.isEmpty());
    }

    @Test
    void replayedRetryShouldBeRefusedAndTheBytesDeleted() throws Exception {
        start("16KiB", "PT5M");
        byte[] pdf = pdf(2048);
        JsonNode first = callTool(arguments(pdf), null, alice).path("result");
        JsonNode target = first.path("uploads").get(0);
        put(target, pdf, alice);
        String state = first.path("requestState").asString();
        callTool(arguments(pdf), state, alice);

        JsonNode replay = callTool(arguments(pdf), state, alice);

        assertEquals(-32602, replay.path("error").path("code").asInt(), replay.toString());
        assertEquals(1, received.size(), "The flow must run once");
        assertEquals(404, put(target, pdf, alice), "The bytes are deleted after the call");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** PDF-looking bytes of the given size: the PDF signature, then random bytes. */
    private static byte[] pdf(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        byte[] signature = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }

    private static ObjectNode arguments(byte[] bytes) {
        ObjectNode report = JSON.createObjectNode();
        report.put("id", "report-1");
        report.put("name", "report.pdf");
        report.put("mimeType", "application/pdf");
        report.put("size", bytes.length);
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", report);
        return arguments;
    }

    /** Sends the bytes to a hosted target, resolved under the MCP address, as a client would. */
    private int put(JsonNode target, byte[] bytes, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(mcpUrl + target.path("path").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        target.path("headers").properties()
                .forEach(header -> request.header(header.getKey(), header.getValue().asString()));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private JsonNode callTool(ObjectNode arguments, String requestState, String token)
            throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", "import-report");
        params.set("arguments", arguments.deepCopy());
        if (requestState != null) {
            params.put("requestState", requestState);
        }
        return rpc("tools/call", "import-report", params, token);
    }

    /** The JSON-RPC response of a client that declares {@code upload}. */
    private JsonNode rpc(String method, String name, ObjectNode params, String token)
            throws Exception {
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
                .putObject(FILES).putObject("upload");
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
                .header("Mcp-Method", method)
                .header("Authorization", "Bearer " + token);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    /** A token for {@code subject}, valid for this adapter. */
    private String token(String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).build(), new JWTClaimsSet.Builder()
                        .issuer("http://127.0.0.1:" + authorizationServerPort)
                        .audience(mcpUrl)
                        .subject(subject)
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
