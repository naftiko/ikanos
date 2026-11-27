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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for MCP Server Cards (SEP-2127, #1165): a capability that declares
 * {@code card} serves it at {@code GET <path>/server-card} on a running MCP adapter, outside or
 * behind its authentication, and the card agrees with {@code server/discover}.
 */
class McpServerCardIntegrationTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();
    private static final ObjectMapper JSON = IkanosMappers.lenientJson();
    private static final String TOKEN = "card-test-token-42";

    private static int authServerPort;
    private static Server authServer;

    /** A stand-in authorization server: the oauth2 adapter only needs it reachable. */
    @BeforeAll
    static void startAuthorizationServer() throws Exception {
        authServerPort = findFreePort();
        authServer = new Server(Protocol.HTTP, "127.0.0.1", authServerPort);
        authServer.setNext(new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("{\"keys\":[]}", MediaType.APPLICATION_JSON);
            }
        });
        authServer.start();
    }

    @AfterAll
    static void stopAuthorizationServer() throws Exception {
        authServer.stop();
    }

    private static String capability(int port, String authentication, String card) {
        return """
                ikanos: "%s"
                info:
                  display: "Shipyard Fleet Management"
                  description: "Server card integration test"
                  version: "1.4.0"
                  publisher: "io.naftiko"
                  repositoryUrl: "https://github.com/example/shipyard"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      path: "/mcp"
                      namespace: "shipyard-tools"
                      description: "Shipyard MCP tools for fleet management"
                %s
                %s
                      tools:
                        ping:
                          description: "Answer with ok"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                """.formatted(VersionHelper.getSchemaVersion(), port, authentication, card);
    }

    private static final String BEARER = """
                  authentication:
                    type: "bearer"
                    token: "%s"
            """.formatted(TOKEN);

    /** Forwarded trust whose trusted network excludes the test client (127.0.0.1). */
    private static final String FORWARDED_ELSEWHERE = """
                  trust:
                    mode: "forwarded"
                    agentIdentityHeader: "X-Agent-Id"
                    trustedNetworks: ["10.0.0.0/8"]
            """;

    /** Forwarded trust whose trusted network includes the test client. */
    private static final String FORWARDED_LOCAL = """
                  trust:
                    mode: "forwarded"
                    agentIdentityHeader: "X-Agent-Id"
                    trustedNetworks: ["127.0.0.0/8", "::1/128"]
            """;

    private static McpServerAdapter start(String yaml) throws Exception {
        IkanosSpec spec = YAML.readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        // A 'path' mounts the adapter on a listener the capability owns, so start the capability
        capability.start();
        return (McpServerAdapter) capability.getServerAdapters().get(0);
    }

    private static String cardUrl(McpServerAdapter adapter) {
        return "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp/server-card";
    }

    private static HttpResponse<String> get(String url, String... headers) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
            if (headers.length > 0) {
                request.headers(headers);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void publicCardShouldBeServedWithoutCredentialsOnABearerServer() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), BEARER,
                "      card: {}"));
        try {
            HttpResponse<String> response = get(cardUrl(adapter));
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith(ServerCardBuilder.MEDIA_TYPE), response.headers().toString());
            assertEquals("*", response.headers()
                    .firstValue("Access-Control-Allow-Origin").orElse(null));

            JsonNode card = JSON.readTree(response.body());
            ServerCardBuilderTest.assertConformsToUpstreamSchema(card);
            assertEquals("io.naftiko/shipyard-tools", card.path("name").asString());
            assertEquals("github", card.path("repository").path("source").asString());
            assertEquals("Bearer {token}", card.path("remotes").path(0).path("headers")
                    .path(0).path("value").asString());
            assertFalse(response.body().contains(TOKEN), "The card must never carry a secret");
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void mcpEndpointShouldStillRequireCredentialsWhenACardIsPublic() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), BEARER,
                "      card: {}"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:"
                            + adapter.getMcpServerSpec().getPort() + "/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                    .header("Mcp-Method", "server/discover").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void cardShouldAnswerNotModifiedToARevalidation() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), "", "      card: {}"));
        try {
            String etag = get(cardUrl(adapter)).headers().firstValue("ETag").orElseThrow();
            HttpResponse<String> response = get(cardUrl(adapter), "If-None-Match", etag);
            assertEquals(304, response.statusCode());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void cardShouldAnswerHeadLikeGetWithoutABody() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), "", "      card: {}"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> get = get(cardUrl(adapter));
            HttpResponse<String> head = client.send(HttpRequest
                    .newBuilder(URI.create(cardUrl(adapter)))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
            assertEquals(get.headers().firstValue("ETag"), head.headers().firstValue("ETag"));
            assertEquals(get.headers().firstValue("Cache-Control"),
                    head.headers().firstValue("Cache-Control"));
            assertTrue(head.headers().firstValue("Content-Type").orElse("")
                    .startsWith(ServerCardBuilder.MEDIA_TYPE), head.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void cardUrlShouldFollowTheHostAndIgnoreForwardedHeadersFromAnUntrustedPeer()
            throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), FORWARDED_ELSEWHERE,
                "      card: {}"));
        try {
            HttpResponse<String> response = get(cardUrl(adapter),
                    "X-Forwarded-Host", "evil.example", "X-Forwarded-Proto", "https");
            assertEquals(200, response.statusCode());
            assertEquals("http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/mcp",
                    JSON.readTree(response.body()).path("remotes").path(0).path("url")
                            .asString());
            assertFalse(response.headers().firstValue("Vary").isPresent(),
                    response.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void cardUrlShouldTakeTheForwardedHeadersOfATrustedPeer() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), FORWARDED_LOCAL,
                "      card: {}"));
        try {
            HttpResponse<String> response = get(cardUrl(adapter),
                    "X-Forwarded-Host", "fleet.example.com", "X-Forwarded-Proto", "https");
            assertEquals(200, response.statusCode());
            assertEquals("https://fleet.example.com/mcp", JSON.readTree(response.body())
                    .path("remotes").path(0).path("url").asString());
            assertEquals("*", response.headers().firstValue("Vary").orElse(null),
                    response.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void cardShouldAnswerTheCorsPreflight() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), BEARER, "      card: {}"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest
                    .newBuilder(URI.create(cardUrl(adapter)))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://agent.example.com")
                    .header("Access-Control-Request-Method", "GET").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode());
            assertEquals("*", response.headers()
                    .firstValue("Access-Control-Allow-Origin").orElse(null));
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void cardVersionShouldMatchTheServerInfoVersionOfServerDiscover() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), "",
                "      card:\n        version: \"2.0.0\""));
        try (HttpClient client = HttpClient.newHttpClient()) {
            JsonNode card = JSON.readTree(get(cardUrl(adapter)).body());
            HttpResponse<String> discover = client.send(HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:"
                            + adapter.getMcpServerSpec().getPort() + "/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                    .header("Mcp-Method", "server/discover").build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(discover.body()).path("result");

            assertEquals(card.path("version").asString(), result.path("_meta")
                    .path("io.modelcontextprotocol/serverInfo").path("version").asString());
            assertEquals(card.path("remotes").path(0).path("supportedProtocolVersions"),
                    result.path("supportedVersions"));
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void authenticatedCardShouldRequireTheServerCredentials() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), BEARER,
                "      card:\n        authenticated: true"));
        try {
            assertEquals(401, get(cardUrl(adapter)).statusCode());
            HttpResponse<String> response = get(cardUrl(adapter), "Authorization",
                    "Bearer " + TOKEN);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("")
                    .contains("private"), response.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void publicCardShouldBeServedAheadOfTheTrustedPeerCheck() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), FORWARDED_ELSEWHERE,
                "      card: {}"));
        try {
            assertEquals(200, get(cardUrl(adapter)).statusCode());
            assertEquals(403, get("http://127.0.0.1:" + adapter.getMcpServerSpec().getPort()
                    + "/mcp").statusCode(), "the MCP endpoint must still refuse the peer");
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void authenticatedCardShouldBeRefusedToAnUntrustedPeerInForwardedMode() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), FORWARDED_ELSEWHERE,
                "      card:\n        authenticated: true"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(403, get(cardUrl(adapter)).statusCode());
            HttpResponse<String> preflight = client.send(HttpRequest
                    .newBuilder(URI.create(cardUrl(adapter)))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://agent.example.com")
                    .header("Access-Control-Request-Method", "GET").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void authenticatedCardShouldBeServedPrivatelyToATrustedPeerInForwardedMode()
            throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), FORWARDED_LOCAL,
                "      card:\n        authenticated: true"));
        try {
            HttpResponse<String> response = get(cardUrl(adapter));
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("")
                    .contains("private"), response.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void authenticatedCardShouldAnswerThePreflightWithoutCredentials() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), BEARER,
                "      card:\n        authenticated: true"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest
                    .newBuilder(URI.create(cardUrl(adapter)))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://agent.example.com")
                    .header("Access-Control-Request-Method", "GET").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                    .contains("Authorization"), response.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void authenticatedCardShouldPointAnOAuth2ClientToTheResourceMetadata() throws Exception {
        int port = findFreePort();
        String oauth2 = """
                  authentication:
                    type: "oauth2"
                    authorizationServerUri: "http://127.0.0.1:%d"
                    resource: "http://127.0.0.1:%d/mcp"
                """.formatted(authServerPort, port).indent(4);
        McpServerAdapter adapter = start(capability(port, oauth2,
                "      card:\n        authenticated: true"));
        try {
            HttpResponse<String> response = get(cardUrl(adapter));
            assertEquals(401, response.statusCode());
            assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("")
                    .contains("resource_metadata="), response.headers().toString());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void adapterWithoutACardShouldNotServeOne() throws Exception {
        McpServerAdapter adapter = start(capability(findFreePort(), "", ""));
        try {
            assertTrue(get(cardUrl(adapter)).statusCode() >= 400);
            assertEquals(null, adapter.getServerCard());
        } finally {
            adapter.getCapability().stop();
        }
    }

    @Test
    void capabilityWithAnInvalidCardShouldFailToLoad() throws Exception {
        String yaml = capability(findFreePort(), "", "      card: {}")
                .replace("  publisher: \"io.naftiko\"\n", "");
        IkanosSpec spec = YAML.readValue(yaml, IkanosSpec.class);
        Exception error = assertThrows(Exception.class, () -> new Capability(spec));
        assertTrue(messages(error).contains("info.publisher"), messages(error));
    }

    private static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    private static String discoverBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
