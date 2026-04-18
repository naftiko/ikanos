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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * Centralized registry for OTel metrics recorded by the Naftiko engine.
 *
 * <p>Instruments are created once during {@link TelemetryBootstrap} initialization and shared
 * across the engine. When the OTel SDK is absent (noop mode), all recording calls are zero-cost
 * no-ops.</p>
 */
@SuppressWarnings("null")
public class EngineMetrics {

    private final LongCounter requestTotal;
    private final DoubleHistogram requestDuration;
    private final LongCounter requestErrors;
    private final DoubleHistogram stepDuration;
    private final LongCounter httpClientTotal;
    private final DoubleHistogram httpClientDuration;
    private final LongUpDownCounter capabilityActive;

    EngineMetrics(Meter meter) {
        this.requestTotal = meter.counterBuilder("naftiko.request.total")
                .setDescription("Total number of requests handled by the engine")
                .build();

        this.requestDuration = meter.histogramBuilder("naftiko.request.duration")
                .setDescription("Duration of request handling in seconds")
                .setUnit("s")
                .build();

        this.requestErrors = meter.counterBuilder("naftiko.request.errors")
                .setDescription("Total number of request errors")
                .build();

        this.stepDuration = meter.histogramBuilder("naftiko.step.duration")
                .setDescription("Duration of step execution in seconds")
                .setUnit("s")
                .build();

        this.httpClientTotal = meter.counterBuilder("naftiko.http.client.total")
                .setDescription("Total number of outbound HTTP client calls")
                .build();

        this.httpClientDuration = meter.histogramBuilder("naftiko.http.client.duration")
                .setDescription("Duration of outbound HTTP client calls in seconds")
                .setUnit("s")
                .build();

        this.capabilityActive = meter.upDownCounterBuilder("naftiko.capability.active")
                .setDescription("Number of active capabilities")
                .build();
    }

    /**
     * Record a completed server adapter request.
     */
    public void recordRequest(String adapter, String operation, String status, double durationSec) {
        Attributes attrs = Attributes.of(
                TelemetryBootstrap.ATTR_ADAPTER_TYPE, adapter,
                TelemetryBootstrap.ATTR_OPERATION_ID, operation,
                io.opentelemetry.api.common.AttributeKey.stringKey("status"), status);
        requestTotal.add(1, attrs);
        requestDuration.record(durationSec, attrs);
    }

    /**
     * Record a request error.
     */
    public void recordRequestError(String adapter, String operation, String errorType) {
        Attributes attrs = Attributes.of(
                TelemetryBootstrap.ATTR_ADAPTER_TYPE, adapter,
                TelemetryBootstrap.ATTR_OPERATION_ID, operation,
                io.opentelemetry.api.common.AttributeKey.stringKey("error.type"), errorType);
        requestErrors.add(1, attrs);
    }

    /**
     * Record a completed step execution.
     */
    public void recordStep(String stepType, String namespace, double durationSec) {
        Attributes attrs = Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("step.type"), stepType,
                TelemetryBootstrap.ATTR_NAMESPACE, namespace != null ? namespace : "unknown");
        stepDuration.record(durationSec, attrs);
    }

    /**
     * Record a completed outbound HTTP client call.
     */
    public void recordHttpClient(String method, String host, int statusCode,
            double durationSec) {
        Attributes attrs = Attributes.of(
                TelemetryBootstrap.ATTR_HTTP_METHOD, method,
                io.opentelemetry.api.common.AttributeKey.stringKey("server.address"), host,
                TelemetryBootstrap.ATTR_HTTP_STATUS_CODE, (long) statusCode);
        httpClientTotal.add(1, attrs);
        httpClientDuration.record(durationSec, attrs);
    }

    /**
     * Increment active capability count (call on start).
     */
    public void capabilityStarted(String capabilityName) {
        Attributes attrs = Attributes.of(
                TelemetryBootstrap.ATTR_CAPABILITY, capabilityName);
        capabilityActive.add(1, attrs);
    }

    /**
     * Decrement active capability count (call on stop).
     */
    public void capabilityStopped(String capabilityName) {
        Attributes attrs = Attributes.of(
                TelemetryBootstrap.ATTR_CAPABILITY, capabilityName);
        capabilityActive.add(-1, attrs);
    }
}
