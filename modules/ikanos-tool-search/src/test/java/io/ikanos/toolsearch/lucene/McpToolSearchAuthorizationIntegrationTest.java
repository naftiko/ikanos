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
package io.ikanos.toolsearch.lucene;

import static io.ikanos.toolsearch.lucene.McpTestServer.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.List;
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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call meta-tool enforces the {@code authorization} of each tool it runs, over real HTTP with
 * OAuth 2.1 tokens: a token gets the same decision, and the same {@code insufficient_scope}
 * challenge, whether it calls a tool directly or through {@code call-tool}.
 *
 * <p>The adapter requires {@code tools:read}. Its {@code order-status} tool requires
 * {@code tools:execute} and the {@code editor} or {@code admin} role instead; {@code ping} keeps
 * the adapter's requirement. Both are read-only, so deferred mode makes them searchable.</p>
 */
class McpToolSearchAuthorizationIntegrationTest {

    private static RSAKey signingKey;
    private static Server authorizationServer;
    private static int authorizationServerPort;
    private static McpServerAdapter adapter;
    private static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("tool-search-key").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        authorizationServerPort = freePort();
        String issuer = "http://127.0.0.1:" + authorizationServerPort;
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("issuer", issuer);
        metadata.put("jwks_uri", issuer + "/jwks");
        metadata.put("authorization_endpoint", issuer + "/authorize");
        metadata.put("token_endpoint", issuer + "/token");
        String metadataJson = JSON.writeValueAsString(metadata);
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

        int port = freePort();
        baseUrl = "http://127.0.0.1:" + port + "/";
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Tool search authorization"
                  description: "call-tool under per-tool authorization"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "shop"
                      description: "Order tools behind OAuth 2.1"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "%s"
                        resource: "http://127.0.0.1:%d/mcp"
                        scopes: ["tools:read", "tools:execute"]
                      authorization:
                        scopes: ["tools:read"]
                      toolSearch:
                        mode: "deferred"
                      tools:
                        - name: "order-status"
                          description: "Status of order 42"
                          hints:
                            readOnly: true
                          authorization:
                            scopes: ["tools:execute"]
                            claims:
                              roles: ["editor", "admin"]
                          outputParameters:
                            - type: "string"
                              value: "shipped"
                        - name: "ping"
                          description: "Answers pong"
                          hints:
                            readOnly: true
                          outputParameters:
                            - type: "string"
                              value: "pong"
                  consumes: []
                """.formatted(VersionHelper.getSchemaVersion(), port, issuer, port);
        adapter = McpTestServer.adapterOf(new Capability(
                IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class)));
        adapter.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
        if (authorizationServer != null) {
            authorizationServer.stop();
        }
    }

    @Test
    void callToolShouldRefuseATokenWithoutTheToolScopesAsADirectCallDoes() throws Exception {
        String token = token(new JWTClaimsSet.Builder().claim("scope", "tools:read")
                .claim("roles", List.of("editor")));

        HttpResponse<String> direct = callDirectly("order-status", token);
        HttpResponse<String> meta = callThroughCallTool(List.of("order-status"), token);

        assertEquals(403, direct.statusCode(), direct.body());
        assertEquals(403, meta.statusCode(), meta.body());
        String challenge = challenge(direct);
        assertTrue(challenge.contains("error=\"insufficient_scope\""), challenge);
        assertTrue(challenge.contains("scope=\"tools:execute tools:read\""), challenge);
        assertEquals(challenge, challenge(meta));
    }

    @Test
    void callToolShouldRefuseATokenWithoutTheToolClaimAsADirectCallDoes() throws Exception {
        String token = token(new JWTClaimsSet.Builder().claim("scope", "tools:read tools:execute")
                .claim("roles", List.of("viewer")));

        HttpResponse<String> direct = callDirectly("order-status", token);
        HttpResponse<String> meta = callThroughCallTool(List.of("order-status"), token);

        assertEquals(403, direct.statusCode(), direct.body());
        assertEquals(403, meta.statusCode(), meta.body());
        assertFalse(challenge(direct).contains("scope=\""), challenge(direct));
        assertEquals(challenge(direct), challenge(meta));
    }

    @Test
    void callToolShouldRefuseTheWholeBatchWhenOneToolIsNotAllowed() throws Exception {
        String token = token(new JWTClaimsSet.Builder().claim("scope", "tools:read"));

        HttpResponse<String> response = callThroughCallTool(List.of("ping", "order-status"),
                token);

        assertEquals(403, response.statusCode(), response.body());
        assertEquals(challenge(callDirectly("order-status", token)), challenge(response));
    }

    @Test
    void callToolShouldRunTheToolForATokenAllowedToCallIt() throws Exception {
        // The tool's requirement replaces the adapter's, through call-tool as directly
        String token = token(new JWTClaimsSet.Builder().claim("scp", List.of("tools:execute"))
                .claim("roles", List.of("viewer", "editor")));

        HttpResponse<String> direct = callDirectly("order-status", token);
        HttpResponse<String> meta = callThroughCallTool(List.of("order-status"), token);

        assertEquals(200, direct.statusCode(), direct.body());
        assertEquals(200, meta.statusCode(), meta.body());
        JsonNode result = JSON.readTree(meta.body()).path("result").path("structuredContent")
                .path("results").get(0).path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertTrue(result.toString().contains("shipped"), result.toString());
    }

    @Test
    void callToolShouldRunAToolThatKeepsTheAdapterRequirement() throws Exception {
        String token = token(new JWTClaimsSet.Builder().claim("scope", "tools:read"));

        HttpResponse<String> response = callThroughCallTool(List.of("ping"), token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("pong"), response.body());
    }

    @Test
    void searchToolsShouldFindToolsTheTokenCannotCallAsToolsListDoes() throws Exception {
        String token = token(new JWTClaimsSet.Builder().claim("scope", "tools:read"));
        ObjectNode arguments = JSON.createObjectNode().put("query", "order status");

        HttpResponse<String> response = send("search-tools", arguments, token);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode tools = JSON.readTree(response.body()).path("result")
                .path("structuredContent").path("tools");
        assertEquals("order-status", tools.get(0).path("name").asString(), tools.toString());
    }

    private static HttpResponse<String> callDirectly(String name, String token) throws Exception {
        return send(name, JSON.createObjectNode(), token);
    }

    private static HttpResponse<String> callThroughCallTool(List<String> names, String token)
            throws Exception {
        ObjectNode arguments = JSON.createObjectNode();
        ArrayNode calls = arguments.putArray("calls");
        for (String name : names) {
            ObjectNode call = calls.addObject();
            call.put("name", name);
            call.putObject("arguments");
        }
        return send("call-tool", arguments, token);
    }

    private static HttpResponse<String> send(String name, ObjectNode arguments, String token)
            throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", name);
        params.set("arguments", arguments);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(baseUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", name)
                    .header("Authorization", "Bearer " + token)
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String challenge(HttpResponse<String> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private static String token(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.issuer("http://127.0.0.1:" + authorizationServerPort)
                        .audience(baseUrl + "mcp")
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
