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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import io.ikanos.Capability;
import io.ikanos.engine.LogCapture;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;

/**
 * #873: when a consumed call gets no HTTP answer (connection refused), every exposed path answers
 * like {@code steps:} already does: a 502 with an error reference on REST, an error result with a
 * reference on MCP. Before, single-call mode forwarded Restlet's connector code 1001 as the HTTP
 * status, with an empty body and nothing to find the cause in the logs.
 */
class ConnectorErrorIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper MAPPER = IkanosMappers.json();
    private static final Pattern REFERENCE = Pattern.compile("\\(reference: (\\S+)\\)");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private Capability capability;
    private int restPort;

    @BeforeEach
    void startCapabilityAgainstAClosedPort() throws Exception {
        int[] ports = TestUtils.freePorts(3);
        int closedPort = ports[0];
        restPort = ports[1];
        capability = new Capability(IkanosMappers.lenientYaml().readValue(
                yaml(closedPort, restPort, ports[2]), IkanosSpec.class));
        capability.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/call", "/steps", "/ref", "/forward/items"})
    void restOperationShouldAnswer502WithAReferenceWhenTheUpstreamIsUnreachable(String path)
            throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + path))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(502, response.statusCode(), path + ": " + response.body());
        assertTrue(response.body().matches("An upstream call failed \\(reference: \\S+\\)"),
                path + ": " + response.body());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"call-tool", "steps-tool", "ref-tool"})
    void mcpToolShouldReturnAnErrorWithAReferenceWhenTheUpstreamIsUnreachable(String tool) {
        JsonNode result = callTool(tool);

        assertTrue(result.path("isError").asBoolean(), tool + ": " + result);
        String text = result.path("content").path(0).path("text").asString();
        assertTrue(text.matches("An upstream call failed \\(reference: \\S+\\)"),
                tool + ": " + text);
    }

    /**
     * The log line under the reference carries why the call failed (here a refused connection),
     * so an operator can tell it from a timeout or a DNS failure. Before, the cause was dropped
     * and the line only said "got no response (status 1001)".
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/call", "/steps", "/ref", "/forward/items"})
    void restLogLineUnderTheReferenceShouldCarryTheConnectionFailure(String path)
            throws Exception {
        try (LogCapture logs = new LogCapture()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + restPort + path))
                    .timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertConnectionFailureLoggedUnder(reference(response.body()), logs);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"call-tool", "steps-tool", "ref-tool"})
    void mcpLogLineUnderTheReferenceShouldCarryTheConnectionFailure(String tool) {
        try (LogCapture logs = new LogCapture()) {
            String text = callTool(tool).path("content").path(0).path("text").asString();

            assertConnectionFailureLoggedUnder(reference(text), logs);
        }
    }

    /**
     * {@code resources/read} has no {@code isError}: an unreachable upstream comes back as a
     * JSON-RPC error with the same message and a reference, never as empty contents. Before, a
     * {@code call:} resource answered success with empty text.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"demo://call", "demo://steps"})
    void mcpResourceShouldFailWithAReferenceWhenTheUpstreamIsUnreachable(String uri) {
        try (LogCapture logs = new LogCapture()) {
            JsonNode response = readResource(uri);

            assertTrue(response.path("result").isMissingNode(), uri + ": " + response);
            assertEquals(-32603, response.path("error").path("code").asInt(), uri + ": " + response);
            String message = response.path("error").path("message").asString();
            assertTrue(message.matches("An upstream call failed \\(reference: \\S+\\)"),
                    uri + ": " + message);
            assertConnectionFailureLoggedUnder(reference(message), logs);
        }
    }

    private static String reference(String body) {
        Matcher matcher = REFERENCE.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static void assertConnectionFailureLoggedUnder(String reference, LogCapture logs) {
        ILoggingEvent event = logs.events().stream()
                .filter(e -> e.getFormattedMessage().contains("[ref=" + reference + "]"))
                .findFirst().orElseThrow(() -> new AssertionError("no log line for " + reference));
        List<String> chain = new ArrayList<>();
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            chain.add(t.getClassName());
        }
        assertTrue(chain.contains("java.net.ConnectException"),
                event.getFormattedMessage() + " logged with " + chain);
    }

    private JsonNode readResource(String uri) {
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(1);
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "resources/read");
        ObjectNode params = request.putObject("params");
        params.put("uri", uri);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return new ProtocolDispatcher(mcp).dispatch(request).responseBody();
    }

    private JsonNode callTool(String tool) {
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(1);
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.putObject("arguments");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return new ProtocolDispatcher(mcp).dispatch(request).responseBody().path("result");
    }

    private static String yaml(int closedPort, int restPort, int mcpPort) {
        return """
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: agg
                      flows:
                        get-item:
                          description: "Get the item"
                          call: up.get-item
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        item:
                          path: "/item"
                          operations:
                            get-item:
                              method: GET
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                        call:
                          path: "/call"
                          operations:
                            get:
                              method: GET
                              call: up.get-item
                        steps:
                          path: "/steps"
                          operations:
                            get:
                              method: GET
                              steps:
                                - type: call
                                  name: fetch
                                  call: up.get-item
                        ref:
                          path: "/ref"
                          operations:
                            get:
                              method: GET
                              ref: agg.get-item
                        forward:
                          path: "/forward/{{path}}"
                          forward:
                            targetNamespace: up
                    - type: mcp
                      address: "localhost"
                      port: %d
                      namespace: demo-mcp
                      description: "Demo"
                      resources:
                        call-resource:
                          uri: "demo://call"
                          description: "Single call"
                          call: up.get-item
                        steps-resource:
                          uri: "demo://steps"
                          description: "Steps"
                          steps:
                            - type: call
                              name: fetch
                              call: up.get-item
                      tools:
                        call-tool:
                          description: "Single call"
                          call: up.get-item
                        steps-tool:
                          description: "Steps"
                          steps:
                            - type: call
                              name: fetch
                              call: up.get-item
                        ref-tool:
                          description: "Aggregate"
                          ref: agg.get-item
                """.formatted(IKANOS, closedPort, restPort, mcpPort);
    }
}
