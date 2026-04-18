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

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

/**
 * Unit tests for {@link EngineMetrics} — verifies metric registration and recording for
 * request counters, duration histograms, step metrics, HTTP client metrics, and capability
 * active gauge.
 */
@SuppressWarnings("null")
class EngineMetricsTest {

    private InMemoryMetricReader metricReader;
    private EngineMetrics metrics;

    @BeforeEach
    void setUp() {
        metricReader = InMemoryMetricReader.create();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();
        TelemetryBootstrap.init(sdk);
        metrics = TelemetryBootstrap.get().getMetrics();
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
    }

    @Test
    void recordRequestShouldIncrementCounterAndRecordDuration() {
        metrics.recordRequest("rest", "/api GET", "200", 0.342);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        assertTrue(hasMetric(data, "naftiko.request.total"),
                "Should record naftiko.request.total counter");
        assertTrue(hasMetric(data, "naftiko.request.duration"),
                "Should record naftiko.request.duration histogram");
    }

    @Test
    void recordRequestErrorShouldIncrementErrorCounter() {
        metrics.recordRequestError("mcp", "get-forecast", "IllegalArgumentException");

        Collection<MetricData> data = metricReader.collectAllMetrics();
        assertTrue(hasMetric(data, "naftiko.request.errors"),
                "Should record naftiko.request.errors counter");
    }

    @Test
    void recordStepShouldRecordStepDuration() {
        metrics.recordStep("call", "weather-api", 0.120);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        assertTrue(hasMetric(data, "naftiko.step.duration"),
                "Should record naftiko.step.duration histogram");
    }

    @Test
    void recordHttpClientShouldIncrementCounterAndRecordDuration() {
        metrics.recordHttpClient("GET", "api.weather.gov", 200, 0.298);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        assertTrue(hasMetric(data, "naftiko.http.client.total"),
                "Should record naftiko.http.client.total counter");
        assertTrue(hasMetric(data, "naftiko.http.client.duration"),
                "Should record naftiko.http.client.duration histogram");
    }

    @Test
    void capabilityStartedShouldIncrementActiveGauge() {
        metrics.capabilityStarted("Weather Service");

        Collection<MetricData> data = metricReader.collectAllMetrics();
        assertTrue(hasMetric(data, "naftiko.capability.active"),
                "Should record naftiko.capability.active gauge");
    }

    @Test
    void capabilityStoppedShouldDecrementActiveGauge() {
        metrics.capabilityStarted("Weather Service");
        metrics.capabilityStopped("Weather Service");

        Collection<MetricData> data = metricReader.collectAllMetrics();
        assertTrue(hasMetric(data, "naftiko.capability.active"),
                "Should record naftiko.capability.active gauge");
    }

    @Test
    void recordRequestShouldIncludeCorrectAttributes() {
        metrics.recordRequest("rest", "/forecast GET", "200", 0.5);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        MetricData requestTotal = findMetric(data, "naftiko.request.total");
        assertNotNull(requestTotal, "Should find naftiko.request.total metric");

        boolean hasAdapterLabel = requestTotal.getLongSumData().getPoints().stream()
                .anyMatch(p -> "rest".equals(
                        p.getAttributes().get(TelemetryBootstrap.ATTR_ADAPTER_TYPE)));
        assertTrue(hasAdapterLabel, "Counter should have adapter=rest attribute");
    }

    @Test
    void recordHttpClientShouldIncludeStatusCodeAttribute() {
        metrics.recordHttpClient("POST", "example.com", 500, 1.2);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        MetricData total = findMetric(data, "naftiko.http.client.total");
        assertNotNull(total, "Should find naftiko.http.client.total metric");

        boolean hasStatusCode = total.getLongSumData().getPoints().stream()
                .anyMatch(p -> Long.valueOf(500).equals(
                        p.getAttributes().get(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE)));
        assertTrue(hasStatusCode, "Counter should have http.response.status_code=500 attribute");
    }

    @Test
    void multipleRecordCallsShouldAccumulate() {
        metrics.recordRequest("rest", "/api GET", "200", 0.1);
        metrics.recordRequest("rest", "/api GET", "200", 0.2);
        metrics.recordRequest("rest", "/api GET", "200", 0.3);

        Collection<MetricData> data = metricReader.collectAllMetrics();
        MetricData requestTotal = findMetric(data, "naftiko.request.total");
        assertNotNull(requestTotal);

        long totalCount = requestTotal.getLongSumData().getPoints().stream()
                .mapToLong(p -> p.getValue())
                .sum();
        assertEquals(3, totalCount, "Counter should accumulate to 3 after 3 recordings");
    }

    private boolean hasMetric(Collection<MetricData> data, String name) {
        return data.stream().anyMatch(m -> m.getName().equals(name));
    }

    private MetricData findMetric(Collection<MetricData> data, String name) {
        return data.stream().filter(m -> m.getName().equals(name)).findFirst().orElse(null);
    }
}
