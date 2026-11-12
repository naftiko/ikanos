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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A JSON-RPC notification is never executed (issue #949): a {@code tools/call} without an
 * {@code id} must not run the tool, so the consumed API never receives a call, over Streamable HTTP
 * as over stdio. The same call with an {@code id} reaches the upstream, which shows the fixture
 * would see a call if the notification ran.
 */
class McpNotificationIntegrationTest {

    private static final ObjectMapper YAML_MAPPER = IkanosMappers.lenientYaml();

    private RecordingUpstream upstream;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new RecordingUpstream();
    }

    @AfterEach
    void tearDown() throws Exception {
        upstream.close();
    }

    @Test
    void httpToolCallNotificationShouldNotReachTheUpstream() throws Exception {
        HttpResponse<String> response = postToolCall(toolCallBody(null));

        assertEquals(202, response.statusCode(), response.body());
        assertEquals(List.of(), upstream.requests(),
                "#949: a tools/call notification must never run the tool");
    }

    @Test
    void httpToolCallRequestShouldReachTheUpstream() throws Exception {
        HttpResponse<String> response = postToolCall(toolCallBody(1));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, upstream.requests().size());
    }

    @Test
    void stdioToolCallNotificationShouldNotReachTheUpstream() throws Exception {
        String input = toolCallBody(null) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new StdioJsonRpcHandler(new ProtocolDispatcher(adapter("transport: \"stdio\"")),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out).run();

        assertTrue(out.toString(StandardCharsets.UTF_8).isBlank(),
                "#949: stdio must not answer a notification");
        assertEquals(List.of(), upstream.requests(),
                "#949: a tools/call notification must never run the tool");
    }

    @Test
    void stdioToolCallRequestShouldReachTheUpstream() throws Exception {
        String input = toolCallBody(1) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new StdioJsonRpcHandler(new ProtocolDispatcher(adapter("transport: \"stdio\"")),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out).run();

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("shipped"), out.toString());
        assertEquals(1, upstream.requests().size());
    }

    private HttpResponse<String> postToolCall(String body) throws Exception {
        McpServerAdapter adapter = adapter("address: \"127.0.0.1\"\n      port: " + findFreePort());
        adapter.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", "order-status")
                    .build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            adapter.stop();
        }
    }

    /** An MCP server exposing {@code order-status}, with the given transport lines. */
    private McpServerAdapter adapter(String transportYaml) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "mcp"
                      %s
                      namespace: "notification-test-mcp"
                      description: "MCP server for the notification tests"
                %s%s""".formatted(VersionHelper.getSchemaVersion(), transportYaml,
                RecordingUpstream.orderStatusToolYaml(), upstream.consumesYaml());
        IkanosSpec spec = YAML_MAPPER.readValue(yaml, IkanosSpec.class);
        return (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    /** A {@code tools/call} of {@code order-status}; a {@code null} id makes it a notification. */
    private static String toolCallBody(Integer id) {
        return "{\"jsonrpc\":\"2.0\"," + (id == null ? "" : "\"id\":" + id + ",")
                + "\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"order-status\",\"arguments\":{},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
