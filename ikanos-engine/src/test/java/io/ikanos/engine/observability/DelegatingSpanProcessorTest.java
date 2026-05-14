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

import static org.junit.jupiter.api.Assertions.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

/**
 * Unit tests for {@link DelegatingSpanProcessor}.
 */
class DelegatingSpanProcessorTest {

    private final InMemorySpanExporter exporter = Objects.requireNonNull(InMemorySpanExporter.create());

    @AfterEach
    void tearDown() {
        exporter.reset();
    }

    @Test
    void onEndShouldBeNoOpWhenDelegateIsNull() {
        try (DelegatingSpanProcessor processor = new DelegatingSpanProcessor()) {
            assertNull(processor.getDelegate());

            // Build an SDK with this processor and emit a span
            SdkTracerProvider tracerProvider = Objects.requireNonNull(
                    SdkTracerProvider.builder()
                            .addSpanProcessor(processor)
                            .build());
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();

            Span span = sdk.getTracer("test").spanBuilder("test-span").startSpan();
            span.end();

            // No delegate → span completes normally but delegate receives nothing
            assertNull(processor.getDelegate());
        }
    }

    @Test
    void onEndShouldForwardToDelegateWhenSet() {
        try (DelegatingSpanProcessor processor = new DelegatingSpanProcessor()) {
            processor.setDelegate(spanProcessor());

            SdkTracerProvider tracerProvider = Objects.requireNonNull(
                    SdkTracerProvider.builder()
                            .addSpanProcessor(processor)
                            .build());
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();

            Span span = sdk.getTracer("test").spanBuilder("forwarded-span").startSpan();
            span.end();

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            assertEquals("forwarded-span", spans.get(0).getName());
        }
    }

    @Test
    void setDelegateShouldWireLateProcessor() {
        try (DelegatingSpanProcessor processor = new DelegatingSpanProcessor()) {
            SdkTracerProvider tracerProvider = Objects.requireNonNull(
                    SdkTracerProvider.builder()
                            .addSpanProcessor(processor)
                            .build());
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();

            // Emit a span before delegate is set — it won't be captured
            Span earlySpan = sdk.getTracer("test").spanBuilder("early-span").startSpan();
            earlySpan.end();
            assertTrue(exporter.getFinishedSpanItems().isEmpty());

            // Now set the delegate
            processor.setDelegate(spanProcessor());

            // Emit a span after delegate is set — it should be captured
            Span lateSpan = sdk.getTracer("test").spanBuilder("late-span").startSpan();
            lateSpan.end();

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            assertEquals("late-span", spans.get(0).getName());
        }
    }

    @Nonnull
    private io.opentelemetry.sdk.trace.SpanProcessor spanProcessor() {
        return Objects.requireNonNull(SimpleSpanProcessor.create(spanExporter()));
    }

    @Nonnull
    private io.opentelemetry.sdk.trace.export.SpanExporter spanExporter() {
        return Objects.requireNonNull(exporter);
    }

    @Test
    void isEndRequiredShouldAlwaysReturnTrue() {
        try (DelegatingSpanProcessor processor = new DelegatingSpanProcessor()) {
            assertTrue(processor.isEndRequired(),
                    "isEndRequired must be true even without delegate to avoid missing spans");
        }
    }

    @Test
    void shutdownShouldSucceedWithoutDelegate() {
        try (DelegatingSpanProcessor processor = new DelegatingSpanProcessor()) {
            assertTrue(processor.shutdown().isSuccess());
        }
    }

    @Test
    void forceFlushShouldSucceedWithoutDelegate() {
        try (DelegatingSpanProcessor processor = new DelegatingSpanProcessor()) {
            assertTrue(processor.forceFlush().isSuccess());
        }
    }
}
