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
package io.naftiko.engine.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link TelemetryBootstrap} — SDK initialization, no-op fallback,
 * span factory methods, and error recording.
 */
@SuppressWarnings("null") // OTel SDK types lack @Nonnull annotations
public class TelemetryBootstrapTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    private TelemetryBootstrap initWithInMemoryExporter() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        return TelemetryBootstrap.init(sdk);
    }

    // ── Initialization ──

    @Test
    void getShouldReturnNoOpWhenNotInitialized() {
        TelemetryBootstrap bootstrap = TelemetryBootstrap.get();
        assertNotNull(bootstrap);
        assertNotNull(bootstrap.getTracer());
        assertNotNull(bootstrap.getOpenTelemetry());
    }

    @Test
    void noOpInstanceShouldProduceInvalidSpans() {
        TelemetryBootstrap bootstrap = TelemetryBootstrap.get();
        Span span = bootstrap.startServerSpan("rest", "test-op", null, null, null);
        assertNotNull(span);
        assertFalse(span.getSpanContext().isValid(),
                "No-op tracer should produce spans with invalid context");
        span.end();
    }

    @Test
    void initWithOpenTelemetryShouldSetGlobalInstance() {
        TelemetryBootstrap bootstrap = initWithInMemoryExporter();
        assertSame(bootstrap, TelemetryBootstrap.get());
    }

    @Test
    void initWithServiceNameShouldSetGlobalInstance() {
        TelemetryBootstrap bootstrap = TelemetryBootstrap.init("test-service");
        assertNotNull(bootstrap);
        assertSame(bootstrap, TelemetryBootstrap.get());
    }

    @Test
    void resetShouldClearGlobalInstance() {
        initWithInMemoryExporter();
        TelemetryBootstrap.reset();
        // After reset, get() should return a new no-op instance
        TelemetryBootstrap after = TelemetryBootstrap.get();
        Span span = after.startServerSpan("rest", "test-op", null, null, null);
        assertFalse(span.getSpanContext().isValid());
        span.end();
    }

    // ── Server span factory ──

    @Test
    void startServerSpanShouldCreateSpanWithCorrectAttributes() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startServerSpan("rest", "GET /orders",
                null, null, "my-capability");
        span.end();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData data = spans.get(0);
        assertEquals("rest.request", data.getName());
        assertEquals(SpanKind.SERVER, data.getKind());
        assertEquals("rest", data.getAttributes().get(TelemetryBootstrap.ATTR_ADAPTER_TYPE));
        assertEquals("GET /orders",
                data.getAttributes().get(TelemetryBootstrap.ATTR_OPERATION_ID));
        assertEquals("my-capability",
                data.getAttributes().get(TelemetryBootstrap.ATTR_CAPABILITY));
    }

    @Test
    void startServerSpanShouldDefaultOperationIdWhenNull() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startServerSpan("mcp", null, null, null, null);
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("unknown", data.getAttributes().get(TelemetryBootstrap.ATTR_OPERATION_ID));
        assertNull(data.getAttributes().get(TelemetryBootstrap.ATTR_CAPABILITY));
    }

    // ── Step call span factory ──

    @Test
    void startStepCallSpanShouldCreateInternalSpanWithAttributes() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startStepCallSpan(0, "weather-api.get-forecast",
                "weather-ns");
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("step.call", data.getName());
        assertEquals(SpanKind.INTERNAL, data.getKind());
        assertEquals(0L, data.getAttributes().get(TelemetryBootstrap.ATTR_STEP_INDEX));
        assertEquals("weather-api.get-forecast",
                data.getAttributes().get(TelemetryBootstrap.ATTR_STEP_CALL));
        assertEquals("weather-ns",
                data.getAttributes().get(TelemetryBootstrap.ATTR_NAMESPACE));
    }

    // ── Step lookup span factory ──

    @Test
    void startStepLookupSpanShouldCreateInternalSpanWithAttributes() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startStepLookupSpan(1, "vessel-name");
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("step.lookup", data.getName());
        assertEquals(SpanKind.INTERNAL, data.getKind());
        assertEquals(1L, data.getAttributes().get(TelemetryBootstrap.ATTR_STEP_INDEX));
        assertEquals("vessel-name", data.getAttributes().get(TelemetryBootstrap.ATTR_STEP_MATCH));
    }

    // ── Aggregate function span factory ──

    @Test
    void startAggregateFunctionSpanShouldCreateInternalSpanWithRef() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startAggregateFunctionSpan("forecast.get-forecast");
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("aggregate.function", data.getName());
        assertEquals(SpanKind.INTERNAL, data.getKind());
        assertEquals("forecast.get-forecast",
                data.getAttributes().get(TelemetryBootstrap.ATTR_AGGREGATE_REF));
    }

    @Test
    void startAggregateFunctionSpanShouldDefaultRefWhenNull() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startAggregateFunctionSpan(null);
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("unknown",
                data.getAttributes().get(TelemetryBootstrap.ATTR_AGGREGATE_REF));
    }

    // ── Client span factory ──

    @Test
    void startClientSpanShouldCreateClientSpanWithAttributes() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get()
                .startClientSpan("GET", "http://api.example.com/forecast", "weather-api");
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("http.client.GET", data.getName());
        assertEquals(SpanKind.CLIENT, data.getKind());
        assertEquals("GET", data.getAttributes().get(TelemetryBootstrap.ATTR_HTTP_METHOD));
        assertEquals("http://api.example.com/forecast",
                data.getAttributes().get(TelemetryBootstrap.ATTR_HTTP_URL));
        assertEquals("weather-api",
                data.getAttributes().get(TelemetryBootstrap.ATTR_NAMESPACE));
    }

    @Test
    void startClientSpanShouldDefaultMethodWhenNull() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startClientSpan(null, null, null);
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("http.client.UNKNOWN", data.getName());
        assertEquals("UNKNOWN", data.getAttributes().get(TelemetryBootstrap.ATTR_HTTP_METHOD));
        assertEquals("unknown", data.getAttributes().get(TelemetryBootstrap.ATTR_HTTP_URL));
        assertNull(data.getAttributes().get(TelemetryBootstrap.ATTR_NAMESPACE));
    }

    // ── Error recording ──

    @Test
    void recordErrorShouldSetStatusAndRecordException() {
        initWithInMemoryExporter();

        Span span = TelemetryBootstrap.get().startServerSpan("rest", "test-op",
                null, null, null);
        TelemetryBootstrap.recordError(span, new RuntimeException("test failure"));
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
        assertEquals("test failure", data.getStatus().getDescription());
        assertFalse(data.getEvents().isEmpty(), "Should have recorded exception event");
    }

    @Test
    void recordErrorShouldHandleNullSpanSafely() {
        assertDoesNotThrow(() -> TelemetryBootstrap.recordError(null, new RuntimeException()));
    }

    @Test
    void recordErrorShouldHandleNullErrorSafely() {
        initWithInMemoryExporter();
        Span span = TelemetryBootstrap.get().startServerSpan("rest", "test-op",
                null, null, null);
        assertDoesNotThrow(() -> TelemetryBootstrap.recordError(span, null));
        span.end();
    }

    // ── endSpan ──

    @Test
    void endSpanShouldHandleNullSafely() {
        assertDoesNotThrow(() -> TelemetryBootstrap.endSpan(null));
    }

    // ── Span hierarchy ──

    @Test
    void childSpansShouldBeParentedCorrectly() {
        initWithInMemoryExporter();

        Span serverSpan = TelemetryBootstrap.get().startServerSpan("mcp", "query-database",
                null, null, null);
        try (var scope = serverSpan.makeCurrent()) {
            Span stepSpan = TelemetryBootstrap.get().startStepCallSpan(0, "db-api.query", null);
            try (var stepScope = stepSpan.makeCurrent()) {
                Span clientSpan = TelemetryBootstrap.get()
                        .startClientSpan("POST", "http://localhost:8080/query", null);
                clientSpan.end();
            }
            stepSpan.end();
        }
        serverSpan.end();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(3, spans.size());

        // Spans are finished in reverse order: client, step, server
        SpanData clientData = spans.get(0);
        SpanData stepData = spans.get(1);
        SpanData serverData = spans.get(2);

        assertEquals("http.client.POST", clientData.getName());
        assertEquals("step.call", stepData.getName());
        assertEquals("mcp.request", serverData.getName());

        // Verify parent-child: client → step → server
        assertEquals(stepData.getSpanId(), clientData.getParentSpanId());
        assertEquals(serverData.getSpanId(), stepData.getParentSpanId());

        // All share the same trace
        assertEquals(serverData.getTraceId(), stepData.getTraceId());
        assertEquals(serverData.getTraceId(), clientData.getTraceId());
    }

    // ── Metrics ──

    @Test
    void getMetricsShouldReturnNonNullEvenWhenNoop() {
        TelemetryBootstrap bootstrap = TelemetryBootstrap.get();
        assertNotNull(bootstrap.getMetrics(), "Metrics should be available even in noop mode");
    }

    @Test
    void collectPrometheusMetricsShouldReturnNullWhenNoop() {
        TelemetryBootstrap bootstrap = TelemetryBootstrap.get();
        assertNull(bootstrap.collectPrometheusMetrics(),
                "Prometheus metrics should be null in noop mode");
    }

    @Test
    void collectPrometheusMetricsShouldReturnNullWhenInitWithPlainSdk() {
        // init(OpenTelemetry) does not create a PullMetricReader
        initWithInMemoryExporter();
        assertNull(TelemetryBootstrap.get().collectPrometheusMetrics(),
                "Prometheus metrics should be null when init'd with plain SDK");
    }

    @Test
    void initWithServiceNameShouldEnablePrometheusMetrics() {
        TelemetryBootstrap bootstrap = TelemetryBootstrap.init("test-service");
        // Record a metric to ensure there's data
        bootstrap.getMetrics().recordRequest("rest", "/test GET", "200", 0.1);
        String text = bootstrap.collectPrometheusMetrics();
        assertNotNull(text, "Prometheus metrics should be available after init(serviceName)");
        assertTrue(text.contains("naftiko_request_total"),
                "Should contain serialized metrics");
    }
}
