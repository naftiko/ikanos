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
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Integration tests verifying that the stdio MCP transport ({@link StdioJsonRpcHandler}) produces
 * the same OTel span hierarchy and MDC population as the Streamable HTTP transport ({@link
 * McpServerResource}), including W3C trace context extraction from {@code params._meta} — the
 * only carrier available on stdio, which has no HTTP headers (see {@link
 * io.ikanos.engine.observability.McpMetaTraceContextBridge}).
 *
 * <p>Mirrors {@link ObservabilityMcpHttpLayerTest} (MDC) and {@link
 * io.ikanos.engine.exposes.rest.ObservabilityRestIntegrationTest} (traceparent extraction) for
 * the stdio transport.</p>
 */
class ObservabilityMcpStdioIntegrationTest {

    private static final String SCHEMA_VERSION = VersionHelper.getSchemaVersion();

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @BeforeEach
    void setUp() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .setPropagators(OtelTestFixtures.w3cPropagators())
                .build();
        TelemetryBootstrap.init(sdk);
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
        MDC.clear();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static String weatherStdioCapabilityYaml() {
        return """
                ikanos: "%s"
                info:
                  display: "weather-stdio-capability"
                capability:
                  exposes:
                    - type: "mcp"
                      transport: "stdio"
                      namespace: "weather"
                      tools:
                        - name: "get-forecast"
                          description: "Returns the weather forecast for a location"
                          ref: "forecast.get-forecast"
                          inputParameters:
                            - name: "location"
                              type: "string"
                              description: "City name"
                              required: true
                  aggregates:
                    - namespace: "forecast"
                      flows:
                        - name: "get-forecast"
                          inputParameters:
                            - name: "location"
                              type: "string"
                          outputParameters:
                            - name: "summary"
                              type: "string"
                              value: "Sunny"
                  consumes: []
                """.formatted(SCHEMA_VERSION);
    }

    private static String toolsCallRequest(String toolName, String metaExtra) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",\
                "params":{"name":"%s","arguments":{"location":"Paris"},\
                "_meta":{"io.modelcontextprotocol/protocolVersion":"%s"%s}}}
                """.formatted(toolName, ProtocolDispatcher.MCP_PROTOCOL_VERSION, metaExtra).strip();
    }

    private ObjectMapper runStdio(ProtocolDispatcher dispatcher, String requestLine)
            throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(
                (requestLine + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new StdioJsonRpcHandler(dispatcher, in, out).run();

        this.lastOutput = out.toString(StandardCharsets.UTF_8).strip();
        return dispatcher.getMapper();
    }

    private String lastOutput;

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void stdioToolsCallShouldProduceServerSpanWithCorrectAttributes() throws Exception {
        Capability capability = capabilityFromYaml(weatherStdioCapabilityYaml());
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().getFirst();
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);

        ObjectMapper mapper = runStdio(dispatcher, toolsCallRequest("get-forecast", ""));

        JsonNode response = mapper.readTree(lastOutput);
        assertEquals(1, response.path("id").asInt());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.request span: " + spans));

        assertEquals(SpanKind.SERVER, serverSpan.getKind());
        assertEquals("mcp",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_ADAPTER_TYPE));
        assertEquals("tools/call",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_OPERATION_ID));
        assertEquals("weather-stdio-capability",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_CAPABILITY));
    }

    @Test
    void stdioToolsCallShouldExtractInboundMetaTraceparent() throws Exception {
        Capability capability = capabilityFromYaml(weatherStdioCapabilityYaml());
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().getFirst();
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);

        // stdio has no HTTP headers — params._meta is the only carrier for trace context.
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String metaExtra = ",\"traceparent\":\"" + traceparent + "\"";

        runStdio(dispatcher, toolsCallRequest("get-forecast", metaExtra));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.request span: " + spans));

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", serverSpan.getTraceId(),
                "Server span should continue the trace carried in params._meta.traceparent");
        assertEquals("00f067aa0ba902b7", serverSpan.getParentSpanId(),
                "Server span parent should be the inbound _meta-carried span");
    }

    @Test
    void stdioToolsCallShouldPopulateMdcTraceIdAndSpanIdDuringExecution() throws Exception {
        Capability capability = capabilityFromYaml(weatherStdioCapabilityYaml());
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        AtomicReference<String> capturedTraceId =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicReference<String> capturedSpanId =
                new java.util.concurrent.atomic.AtomicReference<>();

        ProtocolDispatcher capturingDispatcher = new ProtocolDispatcher(adapter) {
            @Override
            public io.ikanos.engine.exposes.mcp.model.DispatchResult dispatch(JsonNode root) {
                // Runs within the SERVER span scope opened by dispatchWithTracing().
                capturedTraceId.set(MDC.get(TelemetryBootstrap.MDC_TRACE_ID));
                capturedSpanId.set(MDC.get(TelemetryBootstrap.MDC_SPAN_ID));
                return super.dispatch(root);
            }
        };

        runStdio(capturingDispatcher, toolsCallRequest("get-forecast", ""));

        assertNotNull(capturedTraceId.get(), "MDC trace_id must be non-null during stdio dispatch");
        assertFalse(capturedTraceId.get().isBlank());
        assertNotNull(capturedSpanId.get(), "MDC span_id must be non-null during stdio dispatch");
        assertFalse(capturedSpanId.get().isBlank());

        // MDC must be cleared once the stdio handler has processed the line.
        assertNull(MDC.get(TelemetryBootstrap.MDC_TRACE_ID),
                "MDC trace_id must be cleared after stdio dispatch completes");
        assertNull(MDC.get(TelemetryBootstrap.MDC_SPAN_ID),
                "MDC span_id must be cleared after stdio dispatch completes");
    }
}
