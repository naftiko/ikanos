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
package io.ikanos.engine.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Integration tests for {@link McpMetaTraceContextBridge} — W3C trace context extraction from
 * the {@code params._meta} object of an MCP JSON-RPC request (transport-agnostic carrier per
 * the MCP {@code 2026-07-28} revision, mirroring {@link ContextPropagationTest} for the
 * HTTP-header carrier).
 */
public class McpMetaTraceContextBridgeTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final ObjectMapper mapper = new ObjectMapper();
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .setPropagators(OtelTestFixtures.w3cPropagators())
                .build();
        TelemetryBootstrap.init(sdk);
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    @Test
    void extractContextShouldRecoverTraceAndSpanIds() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get-forecast",
                    "_meta": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    }
                  }
                }
                """);

        Context extracted = McpMetaTraceContextBridge.extractContext(request);

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", data.getTraceId());
        assertEquals("00f067aa0ba902b7", data.getParentSpanId());
    }

    @Test
    void extractContextShouldFallBackToCurrentContextWhenMetaAbsent() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get-forecast"
                  }
                }
                """);

        Context extracted = McpMetaTraceContextBridge.extractContext(request);

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        // No remote parent — the span is its own trace root.
        assertEquals("0000000000000000", data.getParentSpanId());
    }

    @Test
    void headerGetterShouldReturnSingletonInstance() {
        assertSame(McpMetaTraceContextGetter.INSTANCE, McpMetaTraceContextBridge.headerGetter());
    }

    // ─── extractContext(JsonNode, Request) — layered HTTP + _meta carrier ──────

    @Test
    void extractContextWithHttpRequestShouldPreferMetaOverHttpHeader() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get-forecast",
                    "_meta": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    }
                  }
                }
                """);

        Request httpRequest = new Request(Method.POST, "http://localhost/mcp");
        httpRequest.getHeaders().add("traceparent",
                "00-abcdef0123456789abcdef0123456789-fedcba9876543210-01");

        Context extracted = McpMetaTraceContextBridge.extractContext(request, httpRequest);

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", data.getTraceId(),
                "_meta trace context should take precedence over the HTTP header");
        assertEquals("00f067aa0ba902b7", data.getParentSpanId());
    }

    @Test
    void extractContextWithHttpRequestShouldFallBackToHttpHeaderWhenMetaAbsent() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get-forecast"
                  }
                }
                """);

        Request httpRequest = new Request(Method.POST, "http://localhost/mcp");
        httpRequest.getHeaders().add("traceparent",
                "00-abcdef0123456789abcdef0123456789-fedcba9876543210-01");

        Context extracted = McpMetaTraceContextBridge.extractContext(request, httpRequest);

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertEquals("abcdef0123456789abcdef0123456789", data.getTraceId(),
                "should fall back to the HTTP header when _meta carries no trace context");
        assertEquals("fedcba9876543210", data.getParentSpanId());
    }

    @Test
    void extractContextWithHttpRequestShouldFallBackToCurrentContextWhenBothCarriersAbsent()
            throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get-forecast"
                  }
                }
                """);

        Request httpRequest = new Request(Method.POST, "http://localhost/mcp");

        Context extracted = McpMetaTraceContextBridge.extractContext(request, httpRequest);

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertEquals("0000000000000000", data.getParentSpanId());
    }

    @Test
    void extractContextWithHttpRequestShouldHandleNullHttpRequest() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get-forecast",
                    "_meta": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    }
                  }
                }
                """);

        Context extracted = McpMetaTraceContextBridge.extractContext(request, (Request) null);

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", data.getTraceId());
    }
}
