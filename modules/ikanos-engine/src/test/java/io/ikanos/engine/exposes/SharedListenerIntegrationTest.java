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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * End-to-end tests of adapters sharing one listener (issue #893): a capability is started
 * in-process with REST and MCP adapters on the same port, routed by {@code path} and
 * {@code hosts}, and called over real HTTP. Requests are written on a raw socket so that each
 * test controls the {@code Host} header.
 */
class SharedListenerIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private static final String AGGREGATE = """
              aggregates:
                - namespace: "greeting"
                  flows:
                    hello:
                      description: "Builds a greeting."
                      inputParameters:
                        name:
                          type: "string"
                          description: "Name to greet"
                      outputParameters:
                        message:
                          type: "string"
                          value: "Hello, {{name}}!"
            """;

    private Capability capability;
    private int port;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopCapability() throws Exception {
        if (capability != null) {
            capability.stop();
        }
    }

    @Test
    void restMcpAndSkillsShouldAnswerOnOnePortUnderTheirPaths() throws Exception {
        start(restAt("path: \"/api\"") + mcpAt("path: \"/mcp\"") + skillsMcpAtRoot());

        Reply rest = get("localhost", "/api/hello?name=Ada");
        assertEquals(200, rest.status(), rest.body());
        assertEquals("Hello, Ada!", JSON.readTree(rest.body()).path("message").asString());

        Reply mcp = mcpDiscover("localhost", "/mcp");
        assertEquals(200, mcp.status(), mcp.body());
        assertTrue(mcp.body().contains("\"result\""), mcp.body());

        Reply skills = mcpDiscover("localhost", "/");
        assertEquals(200, skills.status(), skills.body());
        assertTrue(skills.body().contains("io.modelcontextprotocol/skills"), skills.body());
    }

    @Test
    void prefixShouldNotMatchALongerSegment() throws Exception {
        start(restAt("path: \"/api\"") + mcpAt("path: \"/mcp\""));

        assertEquals(404, mcpDiscover("localhost", "/mcpx").status());
        assertEquals(404, get("localhost", "/apix/hello").status());
    }

    @Test
    void sharedAdaptersShouldReportTheSharedServerAsStarted() throws Exception {
        start(restAt("path: \"/api\"") + mcpAt("path: \"/mcp\"") + skillsMcpAtRoot());

        ServerAdapter first = capability.getServerAdapters().get(0);
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            assertTrue(adapter.isSharedServer(), adapter.getSpec().getType());
            assertSame(first.getServer(), adapter.getServer(), adapter.getSpec().getType());
            assertTrue(adapter.getServer().isStarted(), adapter.getSpec().getType());
        }
        assertEquals(1, capability.getServerHost().getSharedListeners().size());
    }

    @Test
    void customDomainShouldReachTheAdapterMountedAtItsRoot() throws Exception {
        start(restAt("") + mcpAt("hosts: [\"mcp.example.test\"]"));

        Reply mcp = mcpDiscover("mcp.example.test", "/");
        assertEquals(200, mcp.status(), mcp.body());

        Reply rest = get("localhost", "/hello?name=Bo");
        assertEquals(200, rest.status(), rest.body());
        // Hosts are isolated: the MCP adapter is not reachable on other host names.
        assertEquals(404, mcpDiscover("localhost", "/").status());
    }

    @Test
    void hostMatchingShouldIgnoreCaseAndPort() throws Exception {
        start(restAt("") + mcpAt("hosts: [\"mcp.example.test\"]"));

        Reply mcp = mcpDiscover("MCP.Example.Test:" + port, "/");
        assertEquals(200, mcp.status(), mcp.body());
    }

    @Test
    void wildcardHostShouldMatchOneLabelOnly() throws Exception {
        start(restAt("hosts: [\"*.tenants.test\"]"));

        assertEquals(200, get("t1.tenants.test", "/hello?name=T1").status());
        // One label only, like a TLS wildcard; no adapter answers on other host names.
        assertEquals(404, get("a.b.tenants.test", "/hello?name=T1").status());
        assertEquals(404, get("tenants.test", "/hello?name=T1").status());
    }

    @Test
    void exactHostShouldWinOverAWildcardThatAlsoMatches() throws Exception {
        start(restAt("hosts: [\"*.example.test\"]") + mcpAt("hosts: [\"mcp.example.test\"]"));

        assertEquals(200, mcpDiscover("mcp.example.test", "/").status());
        assertEquals(200, get("api.example.test", "/hello?name=X").status());
    }

    @Test
    void hostShouldBeResolvedFromABinding() throws Exception {
        Path bindings = tempDir.resolve("deploy.yaml");
        Files.writeString(bindings, "custom-domain: \"mcp.customer.test\"\n");
        String binds = """
                binds:
                  - namespace: "deploy"
                    location: "%s"
                    keys:
                      CUSTOM_DOMAIN: "custom-domain"
                """.formatted(bindings.toUri());

        start(binds, restAt("") + mcpAt("hosts: [\"{{CUSTOM_DOMAIN}}\"]"));

        assertEquals(200, mcpDiscover("mcp.customer.test", "/").status());
        assertEquals(200, get("localhost", "/hello?name=Y").status());
    }

    @Test
    void wellKnownMetadataShouldBeServedOutsideTheMcpPrefix() throws Exception {
        port = freePort();
        String oauth2 = """
                path: "/mcp"
                authentication:
                  type: "oauth2"
                  authorizationServerUri: "http://127.0.0.1:1"
                  resource: "http://localhost:%d/mcp"
                """.formatted(port);
        start(restAt("path: \"/api\"") + mcpAt(oauth2));

        Reply metadata = get("localhost", "/.well-known/oauth-protected-resource/mcp");

        assertEquals(200, metadata.status(), metadata.body());
        JsonNode body = JSON.readTree(metadata.body());
        assertEquals("http://localhost:" + port + "/mcp", body.path("resource").asString());
        // The MCP endpoint itself still requires a token.
        assertEquals(401, mcpDiscover("localhost", "/mcp").status());
    }

    @Test
    void eachAdapterShouldKeepItsOwnAuthenticationOnASharedListener() throws Exception {
        start(restAt("""
                path: "/api"
                authentication:
                  type: "bearer"
                  token: "rest-token"
                """) + mcpAt("""
                path: "/mcp"
                authentication:
                  type: "bearer"
                  token: "mcp-token"
                """));

        assertEquals(200, get("localhost", "/api/hello?name=Ada", "rest-token").status());
        assertEquals(200, mcpDiscover("localhost", "/mcp", "mcp-token").status());

        // A token opens only its own adapter, and no token opens neither.
        assertEquals(401, get("localhost", "/api/hello?name=Ada", "mcp-token").status());
        assertEquals(401, mcpDiscover("localhost", "/mcp", "rest-token").status());
        assertEquals(401, get("localhost", "/api/hello?name=Ada").status());
        assertEquals(401, mcpDiscover("localhost", "/mcp").status());
    }

    @Test
    void adaptersOnTheirOwnPortsShouldKeepDedicatedServers() throws Exception {
        int other = freePort();
        start(restAt("") + """
                    - type: "mcp"
                      address: "localhost"
                      port: %d
                      namespace: "greeting-mcp"
                      tools:
                        hello:
                          description: "Return a greeting."
                          ref: "greeting.hello"
                """.formatted(other));

        assertTrue(capability.getServerHost().getSharedListeners().isEmpty());
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            assertFalse(adapter.isSharedServer(), adapter.getSpec().getType());
            assertTrue(adapter.getServer().isStarted(), adapter.getSpec().getType());
        }
        assertEquals(200, get("localhost", "/hello?name=Z").status());
    }

    // ── fixtures ──

    private String restAt(String mountLines) {
        return """
                    - type: "rest"
                      address: "localhost"
                      port: {PORT}
                      namespace: "greeting-rest"
                %s
                      resources:
                        hello:
                          path: "/hello"
                          operations:
                            hello:
                              method: "GET"
                              ref: "greeting.hello"
                              inputParameters:
                                name:
                                  in: "query"
                                  type: "string"
                                  description: "Name to greet"
                """.formatted(indent(mountLines));
    }

    private String mcpAt(String mountLines) {
        return """
                    - type: "mcp"
                      address: "localhost"
                      port: {PORT}
                      namespace: "greeting-mcp"
                %s
                      tools:
                        hello:
                          description: "Return a greeting."
                          ref: "greeting.hello"
                """.formatted(indent(mountLines));
    }

    /** A skills-only MCP adapter mounted at the root of the shared listener. */
    private String skillsMcpAtRoot() {
        return """
                    - type: "mcp"
                      address: "localhost"
                      port: {PORT}
                      namespace: "greetings"
                      skills:
                        greetings:
                          description: "Greets people"
                """;
    }

    /** Indents adapter-level YAML lines under an {@code exposes} entry. */
    private static String indent(String lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines.split("\n")) {
            if (!line.isBlank()) {
                result.append("      ").append(line).append('\n');
            }
        }
        return result.toString();
    }

    private void start(String exposes) throws Exception {
        start("", exposes);
    }

    private void start(String binds, String exposes) throws Exception {
        if (port == 0) {
            port = freePort();
        }
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Shared Listener Test"
                %scapability:
                %s  exposes:
                %s""".formatted(VersionHelper.getSchemaVersion(), binds, AGGREGATE, exposes)
                .replace("{PORT}", Integer.toString(port));
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
    }

    // ── raw HTTP ──

    private record Reply(int status, String body) {
    }

    private Reply get(String host, String path) throws IOException {
        return get(host, path, null);
    }

    private Reply get(String host, String path, String token) throws IOException {
        return send("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + bearer(token)
                + "Content-Length: 0\r\nConnection: close\r\n\r\n");
    }

    private Reply mcpDiscover(String host, String path) throws IOException {
        return mcpDiscover(host, path, null);
    }

    private Reply mcpDiscover(String host, String path, String token) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return send("POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + bearer(token)
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "MCP-Protocol-Version: " + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\r\n"
                + "Mcp-Method: server/discover\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n" + body);
    }

    private static String bearer(String token) {
        return token == null ? "" : "Authorization: Bearer " + token + "\r\n";
    }

    private Reply send(String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(response.substring(9, 12));
            int bodyStart = response.indexOf("\r\n\r\n");
            String body = bodyStart >= 0 ? response.substring(bodyStart + 4) : "";
            return new Reply(status, body);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
