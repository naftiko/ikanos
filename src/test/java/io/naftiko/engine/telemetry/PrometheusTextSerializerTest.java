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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

/**
 * Unit tests for {@link PrometheusTextSerializer} — verifies correct Prometheus exposition
 * format output for counters, gauges, and histograms.
 */
@SuppressWarnings("null")
class PrometheusTextSerializerTest {

    private InMemoryMetricReader metricReader;
    private Meter meter;

    @BeforeEach
    void setUp() {
        metricReader = InMemoryMetricReader.create();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();
        meter = sdk.getMeter("test");
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
    }

    @Test
    void shouldSerializeCounterWithLabels() {
        LongCounter counter = meter.counterBuilder("naftiko.request.total")
                .setDescription("Total requests")
                .build();
        counter.add(5, Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("adapter"), "rest"));

        Collection<MetricData> data = metricReader.collectAllMetrics();
        String text = PrometheusTextSerializer.serialize(data);

        assertTrue(text.contains("# HELP naftiko_request_total Total requests"),
                "Should contain HELP line");
        assertTrue(text.contains("# TYPE naftiko_request_total counter"),
                "Should contain TYPE counter");
        assertTrue(text.contains("naftiko_request_total{adapter=\"rest\"} 5"),
                "Should contain sample with labels and value");
    }

    @Test
    void shouldSerializeGauge() {
        LongUpDownCounter gauge = meter.upDownCounterBuilder("naftiko.capability.active")
                .setDescription("Active capabilities")
                .build();
        gauge.add(1, Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("capability.name"),
                "weather"));

        Collection<MetricData> data = metricReader.collectAllMetrics();
        String text = PrometheusTextSerializer.serialize(data);

        assertTrue(text.contains("# TYPE naftiko_capability_active gauge"),
                "Should serialize up-down counter as gauge");
        assertTrue(text.contains("naftiko_capability_active{capability_name=\"weather\"} 1"),
                "Should contain sample with sanitized label name");
    }

    @Test
    void shouldSerializeHistogram() {
        DoubleHistogram histogram = meter.histogramBuilder("naftiko.request.duration")
                .setDescription("Request duration in seconds")
                .setUnit("s")
                .build();
        histogram.record(0.342, Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("adapter"), "rest"));

        Collection<MetricData> data = metricReader.collectAllMetrics();
        String text = PrometheusTextSerializer.serialize(data);

        assertTrue(text.contains("# TYPE naftiko_request_duration histogram"),
                "Should contain TYPE histogram");
        assertTrue(text.contains("naftiko_request_duration_bucket{"),
                "Should contain bucket lines");
        assertTrue(text.contains("le=\"+Inf\""),
                "Should contain +Inf bucket");
        assertTrue(text.contains("naftiko_request_duration_sum{"),
                "Should contain _sum line");
        assertTrue(text.contains("naftiko_request_duration_count{"),
                "Should contain _count line");
    }

    @Test
    void shouldSanitizeMetricNames() {
        LongCounter counter = meter.counterBuilder("naftiko.http.client-total")
                .build();
        counter.add(1);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        String text = PrometheusTextSerializer.serialize(data);

        assertTrue(text.contains("naftiko_http_client_total"),
                "Should replace dots and dashes with underscores");
    }

    @Test
    void shouldReturnEmptyStringForNoMetrics() {
        Collection<MetricData> data = metricReader.collectAllMetrics();
        String text = PrometheusTextSerializer.serialize(data);
        assertEquals("", text, "Should return empty string for empty metric collection");
    }

    @Test
    void shouldEscapeLabelValues() {
        LongCounter counter = meter.counterBuilder("test.metric")
                .build();
        counter.add(1, Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("path"),
                "/api \"quoted\" path"));

        Collection<MetricData> data = metricReader.collectAllMetrics();
        String text = PrometheusTextSerializer.serialize(data);

        assertTrue(text.contains("\\\"quoted\\\""),
                "Should escape double quotes in label values");
    }
}
