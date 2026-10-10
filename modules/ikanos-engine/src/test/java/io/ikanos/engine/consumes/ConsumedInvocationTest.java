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
package io.ikanos.engine.consumes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Contract of the {@link ConsumedInvocation} template method that every consumed adapter relies
 * on: single execution, a non-null result, and the default span and metric hooks.
 */
class ConsumedInvocationTest {

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();

    @BeforeEach
    void setUp() {
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(spans))
                .setMeterProvider(OtelTestFixtures.meterProvider(metrics))
                .build());
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        spans.reset();
    }

    /** Minimal invocation returning a fixed result; {@code null} simulates a broken adapter. */
    private static class StubInvocation extends ConsumedInvocation {
        private final ConsumedResult result;
        private final Exception failure;
        int doInvokeCalls;

        StubInvocation(ConsumedResult result) {
            this(result, null);
        }

        StubInvocation(ConsumedResult result, Exception failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public String namespace() {
            return "stub";
        }

        @Override
        public ConsumedOperationView operation() {
            return ConsumedOperationView.NONE;
        }

        @Override
        protected ConsumedResult doInvoke() throws Exception {
            doInvokeCalls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        protected ClientSpanDescriptor spanDescriptor() {
            return new ClientSpanDescriptor("stub", "stub://target");
        }
    }

    /** Same stub, but observing the {@code recordMetrics} hook. */
    private static class ObservingInvocation extends StubInvocation {
        final List<ConsumedResult> metricResults = new ArrayList<>();

        ObservingInvocation(ConsumedResult result, Exception failure) {
            super(result, failure);
        }

        @Override
        protected void recordMetrics(TelemetryBootstrap telemetry, ConsumedResult result,
                double durationSec) {
            metricResults.add(result);
        }
    }

    private static ConsumedResult resultWithStatus(int status) {
        return new ConsumedResult() {
            @Override
            public Outcome outcome() {
                return Outcome.fromHttpStatus(status);
            }

            @Override
            public int status() {
                return status;
            }

            @Override
            public String reason() {
                return null;
            }

            @Override
            public boolean hasBody() {
                return false;
            }

            @Override
            public String mediaType() {
                return null;
            }

            @Override
            public String text() {
                return null;
            }

            @Override
            public JsonNode document() {
                return null;
            }

            @Override
            public byte[] bytes(long maxBytes) {
                return null;
            }

            @Override
            public ConsumedOperationView operation() {
                return ConsumedOperationView.NONE;
            }
        };
    }

    private SpanData onlySpan() {
        List<SpanData> finished = spans.getFinishedSpanItems();
        assertEquals(1, finished.size());
        return finished.get(0);
    }

    @Test
    void invokeShouldReturnTheResultOfDoInvoke() throws Exception {
        ConsumedResult expected = resultWithStatus(200);

        assertSame(expected, new StubInvocation(expected).invoke());
    }

    @Test
    void invokeShouldThrowWhenCalledTwice() throws Exception {
        StubInvocation invocation = new StubInvocation(resultWithStatus(200));
        invocation.invoke();

        IllegalStateException error =
                assertThrows(IllegalStateException.class, invocation::invoke);

        assertTrue(error.getMessage().contains("already executed for namespace 'stub'"),
                error.getMessage());
        assertEquals(1, invocation.doInvokeCalls);
    }

    @Test
    void invokeShouldThrowAndFailSpanWhenDoInvokeReturnsNull() {
        StubInvocation invocation = new StubInvocation(null);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, invocation::invoke);

        assertTrue(error.getMessage().contains("namespace 'stub' returned no result"),
                error.getMessage());
        assertEquals(StatusCode.ERROR, onlySpan().getStatus().getStatusCode());
    }

    @Test
    void defaultAnnotateSpanShouldRecordStatusCodeAndFailSpanOnUpstreamError()
            throws Exception {
        new StubInvocation(resultWithStatus(503)).invoke();

        SpanData span = onlySpan();
        assertEquals(503L, OtelTestFixtures.longAttribute(span.getAttributes(),
                TelemetryBootstrap.ATTR_HTTP_STATUS_CODE));
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("status 503", span.getStatus().getDescription());
    }

    @Test
    void defaultAnnotateSpanShouldNotFailSpanOnClientError() throws Exception {
        new StubInvocation(resultWithStatus(404)).invoke();

        SpanData span = onlySpan();
        assertEquals(404L, OtelTestFixtures.longAttribute(span.getAttributes(),
                TelemetryBootstrap.ATTR_HTTP_STATUS_CODE));
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    void defaultRecordMetricsShouldRecordNothing() throws Exception {
        new StubInvocation(resultWithStatus(200)).invoke();

        assertTrue(metrics.collectAllMetrics().isEmpty(),
                "the default hook must not emit a client metric");
    }

    @Test
    void recordMetricsShouldBeCalledWithNullResultWhenDoInvokeThrows() {
        ObservingInvocation invocation =
                new ObservingInvocation(null, new IOException("connection refused"));

        assertThrows(IOException.class, invocation::invoke);

        assertEquals(1, invocation.metricResults.size());
        assertNull(invocation.metricResults.get(0));
        assertEquals(StatusCode.ERROR, onlySpan().getStatus().getStatusCode());
    }
}
