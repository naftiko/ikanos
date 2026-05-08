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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.util.VersionHelper;

/**
 * Integration tests for MCP OAuth 2.1 authentication. Starts a mock authorization server
 * (serving AS metadata and JWKS) and an OAuth2-protected MCP server, then exercises the
 * full authentication flow with real HTTP calls.
 */
class McpOAuth2IntegrationTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper JSON = new ObjectMapper();

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
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
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

    @Test
    void oauth2ShouldRejectExpiredToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        HttpClient client = HttpClient.newHttpClient();
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
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode(),
                    "Expired token should return 401");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void oauth2ShouldAcceptValidToken() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        HttpClient client = HttpClient.newHttpClient();
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
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(),
                    "Valid token should return 200");

            JsonNode body = JSON.readTree(response.body());
            assertNotNull(body.path("result").path("protocolVersion").asText(null),
                    "Initialize response should contain protocolVersion");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void oauth2ShouldServeProtectedResourceMetadata() throws Exception {
        McpServerAdapter adapter = startOAuth2Server();
        HttpClient client = HttpClient.newHttpClient();
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
                    metadata.get("bearer_methods_supported").get(0).asText());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void oauth2ShouldReturnForbiddenForInsufficientScope() throws Exception {
        McpServerAdapter adapter = startOAuth2ServerWithScopes();
        HttpClient client = HttpClient.newHttpClient();
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
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
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

    // ─── Server Helpers ─────────────────────────────────────────────────────────

    private McpServerAdapter startOAuth2Server() throws Exception {
        int port = findFreePort();
        String resourceUri = "http://127.0.0.1:" + port + "/mcp";
        String yaml = """
                ikanos: "%s"
                info:
                  label: "OAuth2 MCP Test"
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

    private McpServerAdapter startOAuth2ServerWithScopes() throws Exception {
        int port = findFreePort();
        String resourceUri = "http://127.0.0.1:" + port + "/mcp";
        String yaml = """
                ikanos: "%s"
                info:
                  label: "OAuth2 MCP Test"
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

}
