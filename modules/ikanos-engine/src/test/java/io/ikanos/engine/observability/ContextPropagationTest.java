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

import static io.ikanos.engine.observability.OtelNullSafety.nonNull;
import static org.junit.jupiter.api.Assertions.*;

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

/**
 * Integration tests for W3C trace context propagation round-trip:
 * extract from inbound headers → create child span → inject into outbound headers.
 */
public class ContextPropagationTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
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
    void extractShouldRecoverTraceAndSpanIds() {
        Request inboundRequest = new Request(Method.GET, "http://localhost/test");
        inboundRequest.getHeaders().add("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        Context extracted = sdk.getPropagators().getTextMapPropagator()
                .extract(OtelRestletBridge.currentContext(), inboundRequest,
                        OtelRestletBridge.headerGetter());

        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(nonNull(extracted))
                .startSpan();
        serverSpan.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", data.getTraceId());
        assertEquals("00f067aa0ba902b7", data.getParentSpanId());
    }

    @Test
    void injectShouldSetTraceparentHeader() {
        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("test.request")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        Request outboundRequest = new Request(Method.GET, "http://api.example.com/data");

        try (var scope = serverSpan.makeCurrent()) {
            sdk.getPropagators().getTextMapPropagator()
                    .inject(OtelRestletBridge.currentContext(), outboundRequest,
                            OtelRestletBridge.headerSetter());
        }
        serverSpan.end();

        String traceparent = outboundRequest.getHeaders()
                .getFirstValue("traceparent", true);
        assertNotNull(traceparent, "traceparent should be injected into outbound headers");
        assertTrue(traceparent.startsWith("00-"),
                "traceparent should start with version 00");

        // Verify the trace ID in the injected header matches the server span
        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertTrue(traceparent.contains(data.getTraceId()),
                "Injected traceparent should carry the server span's trace ID");
        assertTrue(traceparent.contains(data.getSpanId()),
                "Injected traceparent should carry the server span's span ID");
    }

    @Test
    void roundTripShouldPreserveTraceAcrossExtractAndInject() {
        // 1. Simulate inbound request with traceparent
        Request inboundRequest = new Request(Method.GET, "http://localhost/test");
        inboundRequest.getHeaders().add("traceparent",
                "00-abcdef0123456789abcdef0123456789-fedcba9876543210-01");

        Context extracted = sdk.getPropagators().getTextMapPropagator()
                .extract(OtelRestletBridge.currentContext(), inboundRequest,
                        OtelRestletBridge.headerGetter());

        // 2. Create a server span parented to the extracted context
        Span serverSpan = sdk.getTracer("test")
                .spanBuilder("server.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(nonNull(extracted))
                .startSpan();

        // 3. Inside the server span scope, inject context into an outbound request
        Request outboundRequest = new Request(Method.POST, "http://api.example.com/action");
        try (var scope = serverSpan.makeCurrent()) {
            sdk.getPropagators().getTextMapPropagator()
                    .inject(OtelRestletBridge.currentContext(), outboundRequest,
                            OtelRestletBridge.headerSetter());
        }
        serverSpan.end();

        // 4. The outbound request should carry the same trace ID
        String outboundTraceparent = outboundRequest.getHeaders()
                .getFirstValue("traceparent", true);
        assertNotNull(outboundTraceparent);
        assertTrue(outboundTraceparent.contains("abcdef0123456789abcdef0123456789"),
                "Outbound should carry the original trace ID across the hop");

        // The outbound span ID should be the server span's ID (the parent of downstream)
        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertTrue(outboundTraceparent.contains(data.getSpanId()),
                "Outbound traceparent should reference the server span as parent");
    }
}
