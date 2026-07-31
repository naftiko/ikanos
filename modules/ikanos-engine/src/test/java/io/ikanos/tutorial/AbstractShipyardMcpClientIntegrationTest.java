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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.microcks.testcontainers.MicrocksContainer;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import org.junit.jupiter.api.AfterAll;
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
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.utility.DockerImageName;

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
    private static final MicrocksContainer microcks = new MicrocksContainer(DockerImageName.parse(System.getProperty("microcks.image")))
            .withMainArtifacts("openapi/naftiko-shipyard-legacy-dockyard-api.yaml", "openapi/naftiko-shipyard-maritime-registry-api.yaml");

    @BeforeAll
    static void setup() {
        microcks.setPortBindings(List.of("8080:8080"));
        microcks.start();
    }

    @AfterAll
    static void teardown() {
        microcks.close();
    }

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
        waitForServerReady();
    }

    /**
     * Like {@link #startServerFromSpec(IkanosSpec)} but keeps MCP authentication enabled.
     * Use this when the test explicitly exercises the authentication pipeline.
     */
    protected void startServerFromSpecWithAuth(IkanosSpec spec) throws Exception {
        int port = findFreePort();
        ServerSpec exposesSpec = spec.getCapability().getExposes().get(0);
        exposesSpec.setAddress("localhost");
        exposesSpec.setPort(port);

        Capability capability = new Capability(spec);
        adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        adapter.start();

        serverUrl = "http://localhost:" + port + "/";
        waitForServerReady();
    }

    protected void disableMcpAuthentication(IkanosSpec spec) {
        spec.getCapability().getExposes().stream()
                .filter(McpServerSpec.class::isInstance)
                .map(McpServerSpec.class::cast)
                .forEach(expose -> expose.setAuthentication(null));
    }

    /**
     * Sends {@code tools/list} and returns the {@code result.tools} array.
     */
    protected JsonNode callToolsList(HttpClient http) throws Exception {
        HttpResponse<String> response = http.send(
                buildPost("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """),
                string());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).path("result").path("tools");
    }

    /**
     * Sends {@code tools/call}, asserts {@code isError} is false, and returns the parsed
     * JSON payload from {@code result.content[0].text}.
     */
    protected JsonNode callTool(HttpClient http, String body) throws Exception {
        return doCallTool(http, body, true).orElseThrow();
    }

    protected Optional<JsonNode> callTool(HttpClient http, String body, boolean contentExpected) throws Exception {
        return doCallTool(http, body, contentExpected);
    }

    private Optional<JsonNode> doCallTool(HttpClient http, String body, boolean contentExpected) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(buildPost(body), string());
        assertEquals(200, response.statusCode());

        JsonNode envelope = json.readTree(response.body());
        JsonNode callResult = envelope.path("result");

        assertFalse(callResult.path("isError").asBoolean(false),
                "Tool must not return an error. Raw response: " + envelope.toPrettyString());

        JsonNode content = callResult.path("content");
        assertThat(content.isArray()).overridingErrorMessage(() -> "Expecting result.content to be an array").isTrue();
        assertThat(content.isEmpty()).overridingErrorMessage(() -> "Expecting result.content to be %s, actual content: %s".formatted(contentExpected ?
                "non-empty" : "empty", content.toPrettyString())).isEqualTo(!contentExpected);

        return contentExpected ? Optional.of(json.readTree(content.get(0).path("text").asText())) : Optional.empty();
    }

    protected HttpRequest buildPost(String body) {
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

        String enrichedBody = withProtocolVersionMeta(body.strip());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(enrichedBody));
        if (mcpServerToken != null) {
            builder.header("Authorization", "Bearer " + mcpServerToken);
        }

        try {
            JsonNode request = json.readTree(enrichedBody);
            String rpcMethod = request.path("method").asText("");
            if (!rpcMethod.isEmpty()) {
                builder.header("Mcp-Method", rpcMethod);
            }
            String toolOrPromptName = request.path("params").path("name").asText(null);
            String resourceUri = request.path("params").path("uri").asText(null);
            if (toolOrPromptName != null) {
                builder.header("Mcp-Name", toolOrPromptName);
            } else if (resourceUri != null) {
                builder.header("Mcp-Name", resourceUri);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON-RPC request body for header injection", e);
        }

        return builder.build();
    }

    /**
     * Injects {@code params._meta["io.modelcontextprotocol/protocolVersion"]} into a raw
     * JSON-RPC request body, creating {@code params} if it is absent.
     */
    private String withProtocolVersionMeta(String requestJson) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode request =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(requestJson);
            com.fasterxml.jackson.databind.node.ObjectNode params =
                    request.has("params") && request.get("params").isObject()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode) request.get("params")
                            : request.putObject("params");
            params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
            return json.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject protocolVersion into request body", e);
        }
    }

    protected static HttpResponse.BodyHandler<String> string() {
        return HttpResponse.BodyHandlers.ofString();
    }

    protected static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Polls the server until it accepts a TCP connection, retrying up to 50 times
     * with 100 ms between attempts (≈ 5 s ceiling). Guards against the race condition
     * where {@code adapter.start()} returns before Jetty is fully listening.
     */
    private void waitForServerReady() throws Exception {
        URI uri = URI.create(serverUrl);
        for (int i = 0; i < 50; i++) {
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(uri.getHost(), uri.getPort()), 200);
                return; // server is accepting connections
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Server did not become ready within 5 seconds at " + serverUrl);
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

            String from = importSpec.getFrom();
            if (from != null && from.startsWith(SHARED_RELATIVE_PREFIX)) {
                importSpec.setFrom(resolveSharedPath(from.substring(SHARED_RELATIVE_PREFIX.length())).toString());
            }
        }
    }

    private Path resolveSharedPath(String relativeLocation) {
        return TUTORIAL_SHARED_DIR.resolve(relativeLocation).normalize();
    }
}
