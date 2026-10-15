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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ikanos.Capability;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.util.IkanosMappers;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Integration tests for MCP OAuth 2.1 authentication. Starts a mock authorization server
 * (serving AS metadata and JWKS) and an OAuth2-protected MCP server, then exercises the
 * full authentication flow with real HTTP calls.
 */
class McpOAuth2IntegrationTest {

    private static final ObjectMapper YAML_MAPPER = IkanosMappers.lenientYaml();
    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    private static RSAKey rsaJWK;
    private static int mockAsPort;
    private static Server mockAsServer;
    private static String jwksJson;

    @BeforeAll
    static void startMockAuthorizationServer() throws Exception {
        rsaJWK = new RSAKeyGenerator(2048).keyID("integration-key").generate();
        RSAKey publicKey = rsaJWK.toPublicJWK();
        JWKSet jwkSet = new JWKSet(publicKey);
        jwksJson = jwkSet.toString();

        mockAsPort = findFreePort();

        // Create AS metadata JSON
        ObjectNode asMetadata = JSON.createObjectNode();
        asMetadata.put("issuer", "http://127.0.0.1:" + mockAsPort);
        asMetadata.put("jwks_uri", "http://127.0.0.1:" + mockAsPort + "/jwks");
        asMetadata.put("authorization_endpoint",
                "http://127.0.0.1:" + mockAsPort + "/authorize");
        asMetadata.put("token_endpoint", "http://127.0.0.1:" + mockAsPort + "/token");

        String asMetadataJson = JSON.writeValueAsString(asMetadata);

        // Use plain Restlet chain for the mock AS (no Router/ServerResource needed)
        Restlet handler = new Restlet() {

            @Override
            public void handle(Request request, Response response) {
                String path = request.getResourceRef().getPath();
                if ("/.well-known/oauth-authorization-server".equals(path)) {
                    response.setStatus(Status.SUCCESS_OK);
                    response.setEntity(asMetadataJson, MediaType.APPLICATION_JSON);
                } else if ("/jwks".equals(path)) {
                    response.setStatus(Status.SUCCESS_OK);
                    response.setEntity(jwksJson, MediaType.APPLICATION_JSON);
                } else {
                    response.setStatus(Status.CLIENT_ERROR_NOT_FOUND);
                }
            }
        };

        mockAsServer = new Server(Protocol.HTTP, "127.0.0.1", mockAsPort);
        mockAsServer.setNext(handler);
        mockAsServer.start();
    }

    @AfterAll
    static void stopMockAuthorizationServer() throws Exception {
        if (mockAsServer != null) {
            mockAsServer.stop();
        }
    }

    @Test
    void oauth2ShouldRejectRequestWithoutToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "server/discover")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(401, response.statusCode(),
                        "Request without bearer token should return 401");

                String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse("");
                assertTrue(wwwAuth.contains("Bearer"), "Should contain Bearer challenge");
                assertTrue(wwwAuth.contains("resource_metadata"),
                        "Should contain resource_metadata URL");
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void oauth2ShouldRejectExpiredToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                String token = signedJwt(new JWTClaimsSet.Builder()
                        .issuer("http://127.0.0.1:" + mockAsPort)
                        .audience("http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp")
                        .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                        .claim("scope", "tools:read")
                        .build());

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "server/discover")
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(401, response.statusCode(),
                        "Expired token should return 401");
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void oauth2ShouldAcceptValidToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                String resourceUri = "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp";
                String token = signedJwt(new JWTClaimsSet.Builder()
                        .issuer("http://127.0.0.1:" + mockAsPort)
                        .audience(resourceUri)
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .claim("scope", "tools:read")
                        .build());

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "server/discover")
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(),
                        "Valid token should return 200");

                JsonNode body = JSON.readTree(response.body());
                assertNotNull(body.path("result").path("supportedVersions"),
                        "server/discover response should contain supportedVersions");
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void oauth2ShouldServeProtectedResourceMetadata() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort();

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(
                                        URI.create(baseUrl + "/.well-known/oauth-protected-resource/mcp"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(),
                        "Protected Resource Metadata should be served");

                JsonNode metadata = JSON.readTree(response.body());
                assertNotNull(metadata.get("resource"), "Metadata should contain resource");
                assertNotNull(metadata.get("authorization_servers"),
                        "Metadata should contain authorization_servers");
                assertEquals("header",
                        metadata.get("bearer_methods_supported").get(0).asString());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void oauth2ShouldReturnForbiddenForInsufficientScope() throws Exception {
        McpServerAdapter adapter = startOAuth2ServerWithScopes();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                String resourceUri = "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp";
                String token = signedJwt(new JWTClaimsSet.Builder()
                        .issuer("http://127.0.0.1:" + mockAsPort)
                        .audience(resourceUri)
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .claim("scope", "tools:read")
                        .build());

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "server/discover")
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(403, response.statusCode(),
                        "Token missing required scope should return 403");

                String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse("");
                assertTrue(wwwAuth.contains("insufficient_scope"));
                assertTrue(wwwAuth.contains("resource_metadata"));
            } finally {
                adapter.stop();
            }
        }
    }

    // ─── Server Helpers ─────────────────────────────────────────────────────────

    private McpServerAdapter startOAuth2Server() throws Exception {
        int port = findFreePort();
        String resourceUri = "http://127.0.0.1:" + port + "/mcp";
        String yaml = """
                ikanos: "%s"
                info:
                  display: "OAuth2 MCP Test"
                  description: "OAuth2 integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "oauth2-test-mcp"
                      description: "OAuth2 protected MCP server"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "http://127.0.0.1:%d"
                        resource: "%s"
                        scopes:
                          - "tools:read"
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                  consumes: []
                """.formatted(VersionHelper.getSchemaVersion(), port, mockAsPort, resourceUri);

        return startAdapter(yaml);
    }

    @Test
    void verifyTrustShouldRecordValidatedTokenSubjectOnSpan() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .build());
        try {
            McpServerAdapter adapter = startOAuth2ServerWithTrust("verify");
            try (HttpClient client = HttpClient.newHttpClient()) {
                String resourceUri =
                        "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp";
                String token = signedJwt(new JWTClaimsSet.Builder()
                        .issuer("http://127.0.0.1:" + mockAsPort)
                        .audience(resourceUri)
                        .subject("agent-42")
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .claim("scope", "tools:read")
                        .build());
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrlFor(adapter)))
                                .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "server/discover")
                                .header("Authorization", "Bearer " + token)
                                .header("X-Agent-Id", "spoofed-agent")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
            } finally {
                adapter.stop();
            }
            SpanData span = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("mcp.request"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing mcp.request span"));
            assertEquals("agent-42", OtelTestFixtures.stringAttribute(span.getAttributes(),
                    TelemetryBootstrap.ATTR_AGENT_ID));
            assertEquals("verified", OtelTestFixtures.stringAttribute(span.getAttributes(),
                    TelemetryBootstrap.ATTR_AGENT_ID_SOURCE));
        } finally {
            TelemetryBootstrap.reset();
        }
    }

    @Test
    void verifyTrustShouldRecordTokenActorOnSpan() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .build());
        try {
            McpServerAdapter adapter = startOAuth2ServerWithTrust("verify");
            try {
                HttpResponse<String> response = sendDiscover(adapter, tokenFor(adapter,
                        new JWTClaimsSet.Builder().subject("agent-42")
                                .claim("act", Map.of("sub", "gateway-1"))));
                assertEquals(200, response.statusCode(), response.body());
            } finally {
                adapter.stop();
            }
            SpanData span = mcpRequestSpan(exporter);
            assertEquals("agent-42", OtelTestFixtures.stringAttribute(span.getAttributes(),
                    TelemetryBootstrap.ATTR_AGENT_ID));
            assertEquals("gateway-1", OtelTestFixtures.stringAttribute(span.getAttributes(),
                    TelemetryBootstrap.ATTR_AGENT_ACTOR_ID));
        } finally {
            TelemetryBootstrap.reset();
        }
    }

    @Test
    void verifyTrustShouldNeverForwardTheValidatedTokenToConsumedApis() throws Exception {
        try (RecordingUpstream upstream = new RecordingUpstream()) {
            McpServerAdapter adapter = startOAuth2Server("""
                    trust:
                      mode: "verify"
                    """, upstream);
            String token;
            try (HttpClient client = HttpClient.newHttpClient()) {
                token = tokenFor(adapter, new JWTClaimsSet.Builder().subject("agent-42"));
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrlFor(adapter)))
                                .POST(HttpRequest.BodyPublishers.ofString(orderStatusCallBody()))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "tools/call")
                                .header("Mcp-Name", "order-status")
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("shipped"), response.body());
            } finally {
                adapter.stop();
            }
            assertEquals(1, upstream.requests().size());
            Map<String, String> upstreamHeaders = upstream.requests().get(0);
            assertFalse(upstreamHeaders.containsKey("Authorization"),
                    "The agent's token must not be passed through: " + upstreamHeaders);
            assertFalse(upstreamHeaders.values().stream().anyMatch(v -> v.contains(token)),
                    "No upstream header may carry the agent's token: " + upstreamHeaders);
        }
    }

    @Test
    void forwardedTrustShouldWorkTogetherWithOAuth2Authentication() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .build());
        try {
            McpServerAdapter adapter = startOAuth2Server(FORWARDED_FROM_LOOPBACK, null);
            try (HttpClient client = HttpClient.newHttpClient()) {
                String token = tokenFor(adapter, new JWTClaimsSet.Builder().subject("gateway"));
                HttpResponse<String> response = client.send(
                        discoverRequest(adapter).header("Authorization", "Bearer " + token)
                                .header("X-Agent-Id", "agent-42").build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
            } finally {
                adapter.stop();
            }
            SpanData span = mcpRequestSpan(exporter);
            assertEquals("agent-42", OtelTestFixtures.stringAttribute(span.getAttributes(),
                    TelemetryBootstrap.ATTR_AGENT_ID));
            assertEquals("forwarded", OtelTestFixtures.stringAttribute(span.getAttributes(),
                    TelemetryBootstrap.ATTR_AGENT_ID_SOURCE));
        } finally {
            TelemetryBootstrap.reset();
        }
    }

    @Test
    void forwardedTrustWithOAuth2ShouldStillRejectTrustedPeerWithoutToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server(FORWARDED_FROM_LOOPBACK, null);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    discoverRequest(adapter).header("X-Agent-Id", "agent-42").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), response.body());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void forwardedTrustWithOAuth2ShouldRefuseUntrustedPeerEvenWithValidToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server("""
                trust:
                  mode: "forwarded"
                  agentIdentityHeader: "X-Agent-Id"
                  trustedNetworks: ["10.0.0.0/8"]
                """, null);
        try (HttpClient client = HttpClient.newHttpClient()) {
            String token = tokenFor(adapter, new JWTClaimsSet.Builder().subject("gateway"));
            HttpResponse<String> response = client.send(
                    discoverRequest(adapter).header("Authorization", "Bearer " + token).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode(), response.body());
        } finally {
            adapter.stop();
        }
    }

    private static final String FORWARDED_FROM_LOOPBACK = """
            trust:
              mode: "forwarded"
              agentIdentityHeader: "X-Agent-Id"
              trustedNetworks: ["127.0.0.0/8", "::1/128"]
            """;

    private String tokenFor(McpServerAdapter adapter, JWTClaimsSet.Builder claims)
            throws Exception {
        String resourceUri = "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp";
        return signedJwt(claims
                .issuer("http://127.0.0.1:" + mockAsPort)
                .audience(resourceUri)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("scope", "tools:read")
                .build());
    }

    private static HttpRequest.Builder discoverRequest(McpServerAdapter adapter) {
        return HttpRequest.newBuilder(URI.create(baseUrlFor(adapter)))
                .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .header("Mcp-Method", "server/discover");
    }

    private static HttpResponse<String> sendDiscover(McpServerAdapter adapter, String token)
            throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(discoverRequest(adapter)
                    .header("Authorization", "Bearer " + token).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static SpanData mcpRequestSpan(InMemorySpanExporter exporter) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.request span"));
    }

    private static String orderStatusCallBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"order-status\",\"arguments\":{},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
    }

    /**
     * Starts an OAuth2-protected MCP adapter with the given {@code trust} block (written at zero
     * indentation; it is placed under the adapter here). With an
     * {@code upstream}, it exposes the {@code order-status} tool calling that stub; otherwise a
     * constant tool.
     */
    private McpServerAdapter startOAuth2Server(String trustBlock, RecordingUpstream upstream)
            throws Exception {
        int port = findFreePort();
        String resourceUri = "http://127.0.0.1:" + port + "/mcp";
        String tools = upstream != null ? RecordingUpstream.orderStatusToolYaml() : """
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                """;
        String consumes = upstream != null ? upstream.consumesYaml() : "  consumes: []\n";
        String yaml = """
                ikanos: "%s"
                info:
                  display: "OAuth2 MCP Test"
                  description: "OAuth2 trust integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "oauth2-test-mcp"
                      description: "OAuth2 protected MCP server recording the caller"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "http://127.0.0.1:%d"
                        resource: "%s"
                        scopes:
                          - "tools:read"
                %s%s%s""".formatted(VersionHelper.getSchemaVersion(), port, mockAsPort,
                resourceUri, trustBlock.indent(6), tools, consumes);
        return startAdapter(yaml);
    }

    private McpServerAdapter startOAuth2ServerWithTrust(String mode) throws Exception {
        return startOAuth2Server("""
                trust:
                  mode: "%s"
                """.formatted(mode), null);
    }

    private McpServerAdapter startOAuth2ServerWithScopes() throws Exception {
        int port = findFreePort();
        String resourceUri = "http://127.0.0.1:" + port + "/mcp";
        String yaml = """
                ikanos: "%s"
                info:
                  display: "OAuth2 MCP Test"
                  description: "OAuth2 scope integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "oauth2-test-mcp"
                      description: "OAuth2 protected MCP server with scopes"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "http://127.0.0.1:%d"
                        resource: "%s"
                        scopes:
                          - "tools:read"
                          - "tools:execute"
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                  consumes: []
                """.formatted(VersionHelper.getSchemaVersion(), port, mockAsPort, resourceUri);

        return startAdapter(yaml);
    }

    private McpServerAdapter startAdapter(String yaml) throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        adapter.start();
        return adapter;
    }

    private static String signedJwt(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJWK.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJWK));
        return jwt.serialize();
    }

    private static String baseUrlFor(McpServerAdapter adapter) {
        return "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/";
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String discoverBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
    }

}
