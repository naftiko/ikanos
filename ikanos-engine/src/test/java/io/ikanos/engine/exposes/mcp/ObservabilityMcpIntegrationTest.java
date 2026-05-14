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

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema;
import io.ikanos.Capability;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.IkanosSpec;
import javax.annotation.Nonnull;
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
public class ObservabilityMcpIntegrationTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private Capability capability;

    @BeforeEach
    void setUp() throws Exception {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider())
                .build();
        TelemetryBootstrap.init(sdk);

        File file = new File("src/test/resources/aggregates/aggregate-basic.yaml");
        assertTrue(file.exists());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);
        capability = new Capability(spec);
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    @Test
    void mcpToolCallShouldProduceToolHandlerSpan() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ToolHandler handler = adapter.getToolHandler();

        Map<String, Object> args = new HashMap<>();
        args.put("location", "Paris");

        // get-forecast uses a mock aggregate (no HTTP call, no steps → mock mode)
        McpSchema.CallToolResult result = handler.handleToolCall("get-forecast", args);
        assertNotNull(result);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 2, "Should produce at least 2 spans (tool handler + aggregate)");

        // Find the tool handler span (INTERNAL — the SERVER span lives in McpServerResource)
        SpanData toolSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.tool"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mcp.tool span"));

        assertEquals(SpanKind.INTERNAL, toolSpan.getKind());
        assertEquals("mcp",
                stringAttribute(toolSpan.getAttributes(), TelemetryBootstrap.ATTR_ADAPTER_TYPE));
        assertEquals("get-forecast",
                stringAttribute(toolSpan.getAttributes(), TelemetryBootstrap.ATTR_OPERATION_ID));
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
                stringAttribute(aggregateSpan.getAttributes(), TelemetryBootstrap.ATTR_AGGREGATE_REF));

        // Aggregate span should be a child of the tool handler span
        SpanData toolSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.tool"))
                .findFirst().orElseThrow();

        assertEquals(toolSpan.getSpanId(), aggregateSpan.getParentSpanId(),
                "aggregate.function should be a child of mcp.tool");
        assertEquals(toolSpan.getTraceId(), aggregateSpan.getTraceId(),
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

        SpanData toolSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.tool"))
                .findFirst().orElseThrow();

        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                toolSpan.getStatus().getStatusCode());
    }

        @Nonnull
        private SdkTracerProvider tracerProvider() {
                return java.util.Objects.requireNonNull(SdkTracerProvider.builder()
                                .addSpanProcessor(spanProcessor())
                                .build());
        }

        @Nonnull
        private io.opentelemetry.sdk.trace.SpanProcessor spanProcessor() {
                return java.util.Objects.requireNonNull(SimpleSpanProcessor.create(spanExporter()));
        }

        @Nonnull
        private io.opentelemetry.sdk.trace.export.SpanExporter spanExporter() {
                return java.util.Objects.requireNonNull(exporter);
        }

        private static String stringAttribute(Attributes attributes, AttributeKey<String> key) {
                return attributes.get(java.util.Objects.requireNonNull(key));
        }
}
