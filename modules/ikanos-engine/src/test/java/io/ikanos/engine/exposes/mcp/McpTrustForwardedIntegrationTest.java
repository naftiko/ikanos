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

import static io.ikanos.engine.observability.OtelTestFixtures.stringAttribute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * End to end over a real Streamable HTTP server: with {@code trust: forwarded}, the gateway is the
 * authentication boundary (issue #483). A peer outside {@code trustedNetworks} is refused with 403
 * before any MCP handling, so no tool runs and no consumed API is called. From a trusted peer the
 * agent identity header is recorded on the {@code mcp.request} span. The test client connects from
 * loopback.
 */
class McpTrustForwardedIntegrationTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String LOOPBACK = "[\"127.0.0.0/8\", \"::1/128\"]";
    private static final String NOT_LOOPBACK = "[\"10.0.0.0/8\"]";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private RecordingUpstream upstream;

    @BeforeEach
    void setUp() throws Exception {
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .build());
        upstream = new RecordingUpstream();
    }

    @AfterEach
    void tearDown() throws Exception {
        TelemetryBootstrap.reset();
        exporter.reset();
        upstream.close();
    }

    @Test
    void requestFromTrustedPeerShouldRecordForwardedAgentIdentity() throws Exception {
        HttpResponse<String> response = callOrderStatus(LOOPBACK, "X-Agent-Id", "agent-42");
        assertEquals(200, response.statusCode(), response.body());
        SpanData span = mcpRequestSpan();
        assertEquals("agent-42", stringAttribute(span.getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID));
        assertEquals("forwarded", stringAttribute(span.getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID_SOURCE));
    }

    @Test
    void requestFromTrustedPeerShouldRunTheToolAgainstTheConsumedApi() throws Exception {
        HttpResponse<String> response = callOrderStatus(LOOPBACK, "X-Agent-Id", "agent-42");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("shipped"), response.body());
        assertEquals(1, upstream.requests().size());
    }

    @Test
    void requestFromUntrustedPeerShouldBeForbiddenBeforeAnyToolRuns() throws Exception {
        HttpResponse<String> response = callOrderStatus(NOT_LOOPBACK, "X-Agent-Id", "admin");
        assertEquals(403, response.statusCode(), response.body());
        assertEquals(List.of(), upstream.requests(), "No consumed API call may be made");
        assertTrue(mcpRequestSpans().isEmpty(), "The MCP dispatcher must not be reached");
    }

    @Test
    void requestFromUntrustedPeerWithoutIdentityHeaderShouldBeForbidden() throws Exception {
        HttpResponse<String> response = callOrderStatus(NOT_LOOPBACK);
        assertEquals(403, response.statusCode(), response.body());
        assertEquals(List.of(), upstream.requests());
    }

    @Test
    void forbiddenResponseShouldNotRevealTrustedNetworksOrPeerAddress() throws Exception {
        HttpResponse<String> response = callOrderStatus(NOT_LOOPBACK, "X-Agent-Id", "admin");
        assertTrue(!response.body().contains("10.0.0.0") && !response.body().contains("127.0.0.1"),
                response.body());
    }

    @Test
    void spoofedXForwardedForShouldNotMakeAnUntrustedPeerTrusted() throws Exception {
        HttpResponse<String> response = callOrderStatus(NOT_LOOPBACK,
                "X-Agent-Id", "admin", "X-Forwarded-For", "10.0.3.7");
        assertEquals(403, response.statusCode(), response.body());
        assertEquals(List.of(), upstream.requests());
    }

    @Test
    void duplicateIdentityHeaderFromTrustedPeerShouldNotRecordAnIdentity() throws Exception {
        HttpResponse<String> response = callOrderStatus(LOOPBACK,
                "X-Agent-Id", "agent-42", "X-Agent-Id", "admin");
        assertEquals(200, response.statusCode(), response.body());
        assertNull(stringAttribute(mcpRequestSpan().getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID));
    }

    @Test
    void identityHeaderWithDisallowedCharactersShouldNotBeRecorded() throws Exception {
        HttpResponse<String> response = callOrderStatus(LOOPBACK, "X-Agent-Id", "agent 42<script>");
        assertEquals(200, response.statusCode(), response.body());
        assertNull(stringAttribute(mcpRequestSpan().getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID));
    }

    @Test
    void oversizedIdentityHeaderShouldNotBeRecorded() throws Exception {
        HttpResponse<String> response = callOrderStatus(LOOPBACK,
                "X-Agent-Id", "a".repeat(AgentIdentityResolver.MAX_IDENTITY_LENGTH + 1));
        assertEquals(200, response.statusCode(), response.body());
        assertNull(stringAttribute(mcpRequestSpan().getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID));
    }

    private HttpResponse<String> callOrderStatus(String trustedNetworks, String... headers)
            throws Exception {
        McpServerAdapter adapter = startAdapter(trustedNetworks);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/"))
                    .POST(HttpRequest.BodyPublishers.ofString(toolCallBody()))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", "order-status");
            for (int i = 0; i < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            adapter.stop();
        }
    }

    private List<SpanData> mcpRequestSpans() {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .toList();
    }

    private SpanData mcpRequestSpan() {
        return mcpRequestSpans().stream().findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.request span"));
    }

    private McpServerAdapter startAdapter(String trustedNetworks) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Trust Forwarded Test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "trust-test-mcp"
                      description: "MCP server behind a gateway"
                      trust:
                        mode: "forwarded"
                        agentIdentityHeader: "X-Agent-Id"
                        trustedNetworks: %s
                %s%s""".formatted(VersionHelper.getSchemaVersion(), findFreePort(),
                trustedNetworks, RecordingUpstream.orderStatusToolYaml(), upstream.consumesYaml());
        IkanosSpec spec = YAML_MAPPER.readValue(yaml, IkanosSpec.class);
        McpServerAdapter adapter =
                (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
        adapter.start();
        return adapter;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String toolCallBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"order-status\",\"arguments\":{},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
    }
}
