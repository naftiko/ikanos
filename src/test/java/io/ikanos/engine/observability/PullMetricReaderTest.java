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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;

/**
 * Unit tests for {@link PullMetricReader} — verifies pull-based metric collection for
 * the Prometheus scrape endpoint.
 */
@SuppressWarnings("null")
class PullMetricReaderTest {

    @Test
    void shouldReturnCumulativeAggregationTemporality() throws IOException {
        try (PullMetricReader reader = new PullMetricReader()) {
            assertEquals(AggregationTemporality.CUMULATIVE,
                    reader.getAggregationTemporality(InstrumentType.COUNTER));
            assertEquals(AggregationTemporality.CUMULATIVE,
                    reader.getAggregationTemporality(InstrumentType.HISTOGRAM));
        }
    }

    @Test
    void collectAllMetricsShouldReturnEmptyBeforeRegistration() throws IOException {
        try (PullMetricReader reader = new PullMetricReader()) {
            Collection<MetricData> data = reader.collectAllMetrics();
            assertNotNull(data);
            assertTrue(data.isEmpty());
        }
    }

    @Test
    void collectAllMetricsShouldReturnDataAfterRegistration() throws IOException {
        try (PullMetricReader reader = new PullMetricReader()) {
            SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                    .registerMetricReader(reader)
                    .build();
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setMeterProvider(meterProvider)
                    .build();

            LongCounter counter = sdk.getMeter("test").counterBuilder("test.counter").build();
            counter.add(42);

            Collection<MetricData> data = reader.collectAllMetrics();
            assertFalse(data.isEmpty(), "Should return metric data after recording");
            assertTrue(data.stream().anyMatch(m -> m.getName().equals("test.counter")));
        }
    }

    @Test
    void forceFlushShouldReturnSuccess() throws IOException {
        try (PullMetricReader reader = new PullMetricReader()) {
            CompletableResultCode result = reader.forceFlush();
            assertTrue(result.isSuccess());
        }
    }

    @Test
    void shutdownShouldReturnSuccess() throws IOException {
        try (PullMetricReader reader = new PullMetricReader()) {
            CompletableResultCode result = reader.shutdown();
            assertTrue(result.isSuccess());
        }
    }
}
