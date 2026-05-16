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
import static io.ikanos.engine.observability.OtelNullSafety.nonNullLongKey;
import static io.ikanos.engine.observability.OtelNullSafety.nonNullStringKey;
import static io.ikanos.engine.observability.OtelNullSafety.stringKey;

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
 *
 * <p>Null-safety with the OTel SDK is delegated to {@link OtelNullSafety} — see that class for
 * the rationale.</p>
 */
public class EngineMetrics {

    private final boolean enabled;
    private final LongCounter requestTotal;
    private final DoubleHistogram requestDuration;
    private final LongCounter requestErrors;
    private final DoubleHistogram stepDuration;
    private final LongCounter httpClientTotal;
    private final DoubleHistogram httpClientDuration;
    private final LongUpDownCounter capabilityActive;

    EngineMetrics(Meter meter, boolean enabled) {
        this.enabled = enabled;
        this.requestTotal = meter.counterBuilder("ikanos.request.total")
                .setDescription("Total number of requests handled by the engine")
                .build();

        this.requestDuration = meter.histogramBuilder("ikanos.request.duration.seconds")
                .setDescription("Duration of request handling in seconds")
                .setUnit("s")
                .build();

        this.requestErrors = meter.counterBuilder("ikanos.request.errors")
                .setDescription("Total number of request errors")
                .build();

        this.stepDuration = meter.histogramBuilder("ikanos.step.duration.seconds")
                .setDescription("Duration of step execution in seconds")
                .setUnit("s")
                .build();

        this.httpClientTotal = meter.counterBuilder("ikanos.http.client.total")
                .setDescription("Total number of outbound HTTP client calls")
                .build();

        this.httpClientDuration = meter.histogramBuilder("ikanos.http.client.duration.seconds")
                .setDescription("Duration of outbound HTTP client calls in seconds")
                .setUnit("s")
                .build();

        this.capabilityActive = meter.upDownCounterBuilder("ikanos.capability.active")
                .setDescription("Number of active capabilities")
                .build();
    }

    /**
     * Record a completed server adapter request.
     */
    public void recordRequest(String adapter, String operation, String status, double durationSec) {
        if (!enabled) return;
        Attributes attrs = nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_ADAPTER_TYPE), nonNull(adapter),
                nonNullStringKey(TelemetryBootstrap.ATTR_OPERATION_ID), nonNull(operation),
                stringKey("status"), nonNull(status)));
        requestTotal.add(1, attrs);
        requestDuration.record(durationSec, attrs);
    }

    /**
     * Record a request error.
     */
    public void recordRequestError(String adapter, String operation, String errorType) {
        if (!enabled) return;
        Attributes attrs = nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_ADAPTER_TYPE), nonNull(adapter),
                nonNullStringKey(TelemetryBootstrap.ATTR_OPERATION_ID), nonNull(operation),
                stringKey("error.type"), nonNull(errorType)));
        requestErrors.add(1, attrs);
    }

    /**
     * Record a completed step execution.
     */
    public void recordStep(String stepType, String namespace, double durationSec) {
        if (!enabled) return;
        Attributes attrs = nonNull(Attributes.of(
                stringKey("step.type"), nonNull(stepType),
                nonNullStringKey(TelemetryBootstrap.ATTR_NAMESPACE),
                nonNull(namespace != null ? namespace : "unknown")));
        stepDuration.record(durationSec, attrs);
    }

    /**
     * Record a completed outbound HTTP client call.
     *
     * <p>When {@code statusCode} is {@code 0} (transport failure — no HTTP response received),
     * the {@code http.response.status_code} attribute is omitted and an {@code error.type}
     * attribute is set to {@code "transport"} instead. This avoids polluting metrics with a
     * non-HTTP status value.</p>
     */
    public void recordHttpClient(String method, String host, int statusCode,
            double durationSec) {
        if (!enabled) return;
        var builder = Attributes.builder()
                .put(nonNullStringKey(TelemetryBootstrap.ATTR_HTTP_METHOD), nonNull(method))
                .put(stringKey("server.address"), nonNull(host));
        if (statusCode > 0) {
            builder.put(nonNullLongKey(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE),
                    (long) statusCode);
        } else {
            builder.put(stringKey("error.type"), nonNull("transport"));
        }
        Attributes attrs = nonNull(builder.build());
        httpClientTotal.add(1, attrs);
        httpClientDuration.record(durationSec, attrs);
    }

    /**
     * Increment active capability count (call on start).
     */
    public void capabilityStarted(String capabilityName) {
        if (!enabled) return;
        Attributes attrs = nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_CAPABILITY),
                nonNull(capabilityName)));
        capabilityActive.add(1, attrs);
    }

    /**
     * Decrement active capability count (call on stop).
     */
    public void capabilityStopped(String capabilityName) {
        if (!enabled) return;
        Attributes attrs = nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_CAPABILITY),
                nonNull(capabilityName)));
        capabilityActive.add(-1, attrs);
    }
}
