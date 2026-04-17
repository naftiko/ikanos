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
package io.naftiko.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.engine.telemetry.TelemetryBootstrap;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.NaftikoSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration tests verifying that MCP tool calls produce the expected OTel span hierarchy.
 * Uses an aggregate-based mock capability so no real HTTP calls are needed.
 */
@SuppressWarnings("null")
public class ObservabilityMcpIntegrationTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private Capability capability;

    @BeforeEach
    void setUp() throws Exception {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        TelemetryBootstrap.init(sdk);

        File file = new File("src/test/resources/aggregates/aggregate-basic.yaml");
        assertTrue(file.exists());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
        capability = new Capability(spec);
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    @Test
    void mcpToolCallShouldProduceServerSpan() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ToolHandler handler = adapter.getToolHandler();

        Map<String, Object> args = new HashMap<>();
        args.put("location", "Paris");

        // get-forecast uses a mock aggregate (no HTTP call, no steps → mock mode)
        McpSchema.CallToolResult result = handler.handleToolCall("get-forecast", args);
        assertNotNull(result);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 2, "Should produce at least 2 spans (server + aggregate)");

        // Find the server span
        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.request span"));

        assertEquals(SpanKind.SERVER, serverSpan.getKind());
        assertEquals("mcp",
                serverSpan.getAttributes().get(TelemetryBootstrap.ATTR_ADAPTER_TYPE));
        assertEquals("get-forecast",
                serverSpan.getAttributes().get(TelemetryBootstrap.ATTR_OPERATION_ID));
    }

    @Test
    void mcpToolCallWithAggregateShouldProduceAggregateFunctionSpan() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ToolHandler handler = adapter.getToolHandler();

        Map<String, Object> args = new HashMap<>();
        args.put("location", "London");

        handler.handleToolCall("get-forecast", args);

        List<SpanData> spans = exporter.getFinishedSpanItems();

        // Find aggregate.function span
        SpanData aggregateSpan = spans.stream()
                .filter(s -> s.getName().equals("aggregate.function"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing aggregate.function span"));

        assertEquals(SpanKind.INTERNAL, aggregateSpan.getKind());
        assertEquals("forecast.get-forecast",
                aggregateSpan.getAttributes().get(TelemetryBootstrap.ATTR_AGGREGATE_REF));

        // Aggregate span should be a child of the server span
        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst().orElseThrow();

        assertEquals(serverSpan.getSpanId(), aggregateSpan.getParentSpanId(),
                "aggregate.function should be a child of mcp.request");
        assertEquals(serverSpan.getTraceId(), aggregateSpan.getTraceId(),
                "Both spans should share the same trace");
    }

    @Test
    void mcpToolCallShouldRecordErrorOnUnknownTool() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ToolHandler handler = adapter.getToolHandler();

        assertThrows(Exception.class, () ->
                handler.handleToolCall("nonexistent-tool", Map.of()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertFalse(spans.isEmpty(), "Should produce a span even on error");

        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.request"))
                .findFirst().orElseThrow();

        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                serverSpan.getStatus().getStatusCode());
    }
}
