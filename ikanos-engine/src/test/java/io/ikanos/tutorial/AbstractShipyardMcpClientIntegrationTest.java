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
package io.ikanos.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.util.BindingSpec;

/**
 * Shared infrastructure for Shipyard tutorial MCP client integration tests.
 *
 * <p>Each concrete subclass loads one tutorial YAML file, optionally mutates the spec
 * (e.g. bind location, baseUri), then calls {@link #startServerFromSpec(IkanosSpec)} to
 * start a Jetty-backed MCP server on an ephemeral localhost port. The full MCP Streamable
 * HTTP handshake helpers ({@code initialize}, {@code tools/list}, {@code tools/call}) and
 * all HTTP plumbing are provided here so concrete classes contain only their
 * scenario-specific tests and a {@code @BeforeEach startServer()}.</p>
 */
abstract class AbstractShipyardMcpClientIntegrationTest {

    private static final String DEFAULT_TUTORIAL_SECRETS_FILE =
            "src/test/resources/tutorial/shared/secrets.yaml";
    private static final String SHARED_RELATIVE_PREFIX = "./shared/";
    private static final String SHARED_FILE_URI_PREFIX = "file:///./shared/";
    private static final Path TUTORIAL_SHARED_DIR =
            Paths.get("src", "test", "resources", "tutorial", "shared").toAbsolutePath().normalize();

    protected McpServerAdapter adapter;
    protected String serverUrl;
    protected final ObjectMapper json = new ObjectMapper();
    protected String mcpServerToken;

    @AfterEach
    public void stopServer() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
    }

    /**
     * Loads and deserializes a tutorial capability YAML file.
     */
    protected IkanosSpec loadSpec(String capabilityFile) throws Exception {
        File file = new File(capabilityFile);
        assertTrue(file.exists(), "Tutorial capability file must exist at " + capabilityFile);

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        yaml.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = yaml.readValue(file, IkanosSpec.class);
        normalizeTutorialSharedLocations(spec);
        return spec;
    }

    protected void useMcpServerToken(String secretsFile) throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Map<?, ?> secrets = yaml.readValue(new File(secretsFile), Map.class);
        Object token = secrets.get("mcp-server-token");
        assertTrue(token instanceof String && !((String) token).isBlank(),
                "shared tutorial secrets must define a non-blank mcp-server-token");
        mcpServerToken = (String) token;
    }

    /**
     * Applies an ephemeral localhost port override, builds the {@link Capability}, starts the
     * {@link McpServerAdapter}, and sets {@link #serverUrl}.
     */
    protected void startServerFromSpec(IkanosSpec spec) throws Exception {
        disableMcpAuthentication(spec);

        int port = findFreePort();
        ServerSpec exposesSpec = spec.getCapability().getExposes().get(0);
        exposesSpec.setAddress("localhost");
        exposesSpec.setPort(port);

        Capability capability = new Capability(spec);
        adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        adapter.start();

        serverUrl = "http://localhost:" + port + "/";
    }

    protected void disableMcpAuthentication(IkanosSpec spec) {
        spec.getCapability().getExposes().stream()
                .filter(McpServerSpec.class::isInstance)
                .map(McpServerSpec.class::cast)
                .forEach(expose -> expose.setAuthentication(null));
    }

    /**
     * Runs the MCP {@code initialize} + {@code notifications/initialized} handshake and
     * returns the session ID issued by the server.
     */
    protected String initialize(HttpClient http) throws Exception {
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25",
                           "clientInfo":{"name":"test-client","version":"1.0"},
                           "capabilities":{}}}
                """;

        HttpResponse<String> initResp = http.send(buildPost(initBody), string());
        assertEquals(200, initResp.statusCode(), "initialize must succeed");

        String sessionId = initResp.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new AssertionError("No Mcp-Session-Id in initialize response"));

        http.send(buildPost("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """, sessionId), string());

        return sessionId;
    }

    /**
     * Sends {@code tools/list} and returns the {@code result.tools} array.
     */
    protected JsonNode callToolsList(HttpClient http, String sessionId) throws Exception {
        HttpResponse<String> response = http.send(
                buildPost("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """, sessionId),
                string());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).path("result").path("tools");
    }

    /**
     * Sends {@code tools/call}, asserts {@code isError} is false, and returns the parsed
     * JSON payload from {@code result.content[0].text}.
     */
    protected JsonNode callTool(HttpClient http, String sessionId, String body) throws Exception {
        HttpResponse<String> response = http.send(buildPost(body, sessionId), string());
        assertEquals(200, response.statusCode());

        JsonNode envelope = json.readTree(response.body());
        JsonNode callResult = envelope.path("result");

        assertFalse(callResult.path("isError").asBoolean(false),
                "Tool must not return an error. Raw response: " + envelope.toPrettyString());

        JsonNode content = callResult.path("content");
        assertTrue(content.isArray() && !content.isEmpty(), "result.content must be non-empty");

        return json.readTree(content.get(0).path("text").asText());
    }

    protected HttpRequest buildPost(String body) {
        return buildPost(body, null);
    }

    protected HttpRequest buildPost(String body, String sessionId) {
        if (mcpServerToken == null) {
            File defaultSecretsFile = new File(DEFAULT_TUTORIAL_SECRETS_FILE);
            if (defaultSecretsFile.exists()) {
                try {
                    useMcpServerToken(DEFAULT_TUTORIAL_SECRETS_FILE);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to load default tutorial MCP token", e);
                }
            }
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.strip()));
        if (mcpServerToken != null) {
            builder.header("Authorization", "Bearer " + mcpServerToken);
        }
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return builder.build();
    }

    protected static HttpResponse.BodyHandler<String> string() {
        return HttpResponse.BodyHandlers.ofString();
    }

    protected static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void normalizeTutorialSharedLocations(IkanosSpec spec) {
        normalizeBindLocations(spec.getBinds());
        normalizeConsumesImports(spec.getConsumes());

        if (spec.getCapability() == null) {
            return;
        }

        normalizeBindLocations(spec.getCapability().getBinds());
        normalizeConsumesImports(spec.getCapability().getConsumes());
    }

    private void normalizeBindLocations(List<BindingSpec> binds) {
        for (BindingSpec bind : binds) {
            String location = bind.getLocation();
            if (location != null && location.startsWith(SHARED_FILE_URI_PREFIX)) {
                bind.setLocation(resolveSharedPath(location.substring(SHARED_FILE_URI_PREFIX.length())).toUri().toString());
            }
        }
    }

    private void normalizeConsumesImports(List<ClientSpec> consumes) {
        for (ClientSpec client : consumes) {
            if (!(client instanceof ImportedConsumesHttpSpec importSpec)) {
                continue;
            }

            String location = importSpec.getLocation();
            if (location != null && location.startsWith(SHARED_RELATIVE_PREFIX)) {
                importSpec.setLocation(resolveSharedPath(location.substring(SHARED_RELATIVE_PREFIX.length())).toString());
            }
        }
    }

    private Path resolveSharedPath(String relativeLocation) {
        return TUTORIAL_SHARED_DIR.resolve(relativeLocation).normalize();
    }
}
