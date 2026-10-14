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
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * End to end over a real Streamable HTTP server: with {@code trust: forwarded}, the agent identity
 * header is recorded on the {@code mcp.request} span only when the real connecting address is
 * trusted (issue #483). The test client connects from loopback.
 */
class McpTrustForwardedIntegrationTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @BeforeEach
    void setUp() {
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .build());
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    @Test
    void requestFromTrustedPeerShouldRecordForwardedAgentIdentity() throws Exception {
        SpanData span = callWithHeaders(startAdapter("[\"127.0.0.0/8\", \"::1/128\"]"),
                "X-Agent-Id", "agent-42");
        assertEquals("agent-42", stringAttribute(span.getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID));
        assertEquals("forwarded", stringAttribute(span.getAttributes(),
                TelemetryBootstrap.ATTR_AGENT_ID_SOURCE));
    }

    @Test
    void requestFromUntrustedPeerShouldNotRecordAgentIdentity() throws Exception {
        SpanData span = callWithHeaders(startAdapter("[\"10.0.0.0/8\"]"),
                "X-Agent-Id", "admin");
        assertNull(stringAttribute(span.getAttributes(), TelemetryBootstrap.ATTR_AGENT_ID));
    }

    @Test
    void spoofedXForwardedForShouldNotMakeAnUntrustedPeerTrusted() throws Exception {
        SpanData span = callWithHeaders(startAdapter("[\"10.0.0.0/8\"]"),
                "X-Agent-Id", "admin", "X-Forwarded-For", "10.0.3.7");
        assertNull(stringAttribute(span.getAttributes(), TelemetryBootstrap.ATTR_AGENT_ID));
    }

    private SpanData callWithHeaders(McpServerAdapter adapter, String... headers)
            throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + adapter.getMcpServerSpec().getPort() + "/"))
                    .POST(HttpRequest.BodyPublishers.ofString(discoverBody()))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                    .header("Mcp-Method", "server/discover");
            for (int i = 0; i < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
        } finally {
            adapter.stop();
        }
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.request span"));
    }

    private static McpServerAdapter startAdapter(String trustedNetworks) throws Exception {
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
                      tools:
                        hello:
                          description: "Say hello"
                          outputParameters:
                            msg:
                              type: "string"
                              value: "hi"
                  consumes: []
                """.formatted(VersionHelper.getSchemaVersion(), findFreePort(), trustedNetworks);
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

    private static String discoverBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";
    }
}
