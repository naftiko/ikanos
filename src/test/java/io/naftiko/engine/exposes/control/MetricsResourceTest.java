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
package io.naftiko.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;

import io.naftiko.engine.telemetry.TelemetryBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;

/**
 * Unit tests for {@link MetricsResource} — verifies Prometheus text output when the SDK is
 * active, and 503 when OTel is not active.
 */
class MetricsResourceTest {

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
    }

    @Test
    void getMetricsShouldReturn503WhenOTelIsNoop() throws Exception {
        // Default — no SDK initialized, noop mode
        MetricsResource resource = new MetricsResource();

        Representation result = resource.getMetrics();

        assertNotNull(result);
        assertEquals(MediaType.APPLICATION_JSON, result.getMediaType());
        assertTrue(result.getText().contains("OpenTelemetry is not active"));
    }

    @Test
    void getMetricsShouldReturnPrometheusTextWhenSdkActive() throws Exception {
        // Use init(serviceName) which sets up the PullMetricReader for Prometheus scraping
        TelemetryBootstrap.init("test-service");

        // Record a metric so there's something to serialize
        TelemetryBootstrap.get().getMetrics().recordRequest("rest", "/test GET", "200", 0.1);

        MetricsResource resource = new MetricsResource();
        Representation result = resource.getMetrics();

        assertNotNull(result);
        assertEquals(MediaType.TEXT_PLAIN, result.getMediaType());
        String text = result.getText();
        assertTrue(text.contains("naftiko_request_total"),
                "Should contain request total counter");
        assertTrue(text.contains("naftiko_request_duration"),
                "Should contain request duration histogram");
    }
}
