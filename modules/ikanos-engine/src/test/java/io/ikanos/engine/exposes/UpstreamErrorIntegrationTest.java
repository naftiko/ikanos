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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.LogCapture;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #879: when the upstream answers an error with a body, a single {@code call:}, {@code steps:}
 * and a {@code ref:} to an aggregate flow answer the same way within each adapter.
 * <ul>
 * <li>REST: {@code 502} "An upstream call failed (reference: …)", the upstream status in the log
 * under the reference and the upstream body never returned. Before, {@code call:} and
 * {@code ref:} passed the upstream status and body through, and a flow with an output mapping
 * answered {@code 200} with the error body mapped.</li>
 * <li>MCP tools: an error result carrying the upstream body, so the agent can react (#925).
 * Before, {@code steps:} hid it, and a flow with an output mapping returned it as a success.</li>
 * <li>MCP resources, which have no {@code isError}: a JSON-RPC error with a reference. Before, a
 * {@code call:} resource returned the error body as its contents.</li>
 * </ul>
 */
class UpstreamErrorIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper MAPPER = IkanosMappers.json();
    private static final String UPSTREAM_MARKER = "UPSTREAM-DETAIL-42";
    private static final Pattern REFERENCE = Pattern.compile("\\(reference: (\\S+)\\)");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private HttpServer upstream;
    private Capability capability;
    private int restPort;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            int status = Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
            byte[] body = ("{\"message\":\"Ship not found\",\"detail\":\"" + UPSTREAM_MARKER
                    + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        restPort = freePort();
        capability = new Capability(IkanosMappers.lenientYaml().readValue(
                yaml(upstream.getAddress().getPort(), restPort, freePort()), IkanosSpec.class));
        capability.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        upstream.stop(0);
    }

    @ParameterizedTest(name = "{0} with upstream {1}")
    @CsvSource({"call,404", "call,500", "steps,404", "steps,500", "ref,404", "ref,500",
            "ref-mapped,404", "ref-mapped,500"})
    void restShouldAnswer502WithAReferenceForAnUpstreamError(String mode, int status)
            throws Exception {
        try (LogCapture logs = new LogCapture()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + restPort + "/" + mode + "/" + status))
                    .timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(502, response.statusCode(), mode + ": " + response.body());
            assertHidden(response.body(), status, logs);
        }
    }

    @ParameterizedTest(name = "{0} with upstream {1}")
    @CsvSource({"call,404", "call,500", "steps,404", "steps,500", "ref,404", "ref,500",
            "ref-mapped,404", "ref-mapped,500"})
    void mcpToolShouldReturnAnErrorCarryingTheUpstreamBody(String mode, int status) {
        JsonNode result = rpc("tools/call", params -> {
            params.put("name", mode + "-" + status);
            params.putObject("arguments");
        }).path("result");

        assertTrue(result.path("isError").asBoolean(), mode + ": " + result);
        String text = result.path("content").path(0).path("text").asString();
        assertTrue(text.contains(UPSTREAM_MARKER), mode + ": " + text);
    }

    @ParameterizedTest(name = "{0} with upstream {1}")
    @CsvSource({"call,404", "call,500", "steps,404", "steps,500"})
    void mcpResourceShouldFailWithAReferenceForAnUpstreamError(String mode, int status) {
        try (LogCapture logs = new LogCapture()) {
            JsonNode response = rpc("resources/read",
                    params -> params.put("uri", "demo://" + mode + "/" + status));

            assertTrue(response.path("result").isMissingNode(), mode + ": " + response);
            assertEquals(-32603, response.path("error").path("code").asInt(), response.toString());
            assertHidden(response.path("error").path("message").asString(), status, logs);
        }
    }

    /**
     * The answer is "An upstream call failed (reference: …)" and nothing of the upstream body;
     * the log line under the reference names the upstream status.
     */
    private static void assertHidden(String answer, int status, LogCapture logs) {
        assertTrue(answer.matches("An upstream call failed \\(reference: \\S+\\)"), answer);
        assertFalse(answer.contains(UPSTREAM_MARKER), "upstream body leaked: " + answer);
        Matcher matcher = REFERENCE.matcher(answer);
        assertTrue(matcher.find(), answer);
        String reference = matcher.group(1);
        assertTrue(logs.messages().stream().anyMatch(m -> m.contains("[ref=" + reference + "]")
                && m.contains("HTTP " + status)),
                "the log under " + reference + " names HTTP " + status + ": " + logs.messages());
    }

    private JsonNode rpc(String method, java.util.function.Consumer<ObjectNode> fill) {
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(1);
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = request.putObject("params");
        fill.accept(params);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return new ProtocolDispatcher(mcp).dispatch(request).responseBody();
    }

    private static String yaml(int upstreamPort, int restPort, int mcpPort) {
        StringBuilder consumed = new StringBuilder();
        StringBuilder flows = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        StringBuilder tools = new StringBuilder();
        StringBuilder resources = new StringBuilder();
        for (int status : new int[] {404, 500}) {
            consumed.append("""
                            s%1$d:
                              path: "/%1$d"
                              operations:
                                get-%1$d:
                                  method: GET
                    """.formatted(status));
            flows.append("""
                            get-%1$d:
                              description: "Get %1$d"
                              call: up.get-%1$d
                            mapped-%1$d:
                              description: "Get %1$d, mapped"
                              call: up.get-%1$d
                              outputParameters:
                                - type: string
                                  mapping: $.message
                    """.formatted(status));
            for (String mode : new String[] {"call", "steps", "ref", "ref-mapped"}) {
                String body = body(mode, status, " ".repeat(10));
                rest.append("""
                            %1$s-%2$d:
                              path: "/%1$s/%2$d"
                              operations:
                                get:
                                  method: GET
                    """.formatted(mode, status)).append(body(mode, status, " ".repeat(14)));
                tools.append("""
                            %1$s-%2$d:
                              description: "%1$s %2$d"
                    """.formatted(mode, status)).append(body);
                if (mode.equals("call") || mode.equals("steps")) {
                    resources.append("""
                                %1$s-%2$d:
                                  uri: "demo://%1$s/%2$d"
                                  description: "%1$s %2$d"
                        """.formatted(mode, status)).append(body);
                }
            }
        }
        return """
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: agg
                      flows:
                %s
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                %s
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                %s
                    - type: mcp
                      address: "localhost"
                      port: %d
                      namespace: demo-mcp
                      description: "Demo"
                      resources:
                %s
                      tools:
                %s
                """.formatted(IKANOS, flows.toString().stripTrailing(), upstreamPort,
                consumed.toString().stripTrailing(), restPort, rest.toString().stripTrailing(),
                mcpPort, resources.toString().stripTrailing(), tools.toString().stripTrailing());
    }

    /** The lines that make an operation, tool or resource use one mode, at the given indent. */
    private static String body(String mode, int status, String indent) {
        String lines = switch (mode) {
            case "call" -> "call: up.get-" + status + "\n";
            case "steps" -> "steps:\n  - type: call\n    name: fetch\n    call: up.get-" + status
                    + "\n";
            case "ref" -> "ref: agg.get-" + status + "\n";
            default -> "ref: agg.mapped-" + status + "\n";
        };
        return lines.lines().map(line -> indent + line).reduce("", (a, b) -> a + b + "\n");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
