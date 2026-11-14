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
    private final LongUpDownCounter requestInProgress;
    private final LongCounter requestRejected;
    private final LongCounter toolSearchTotal;
    private final DoubleHistogram toolSearchDuration;
    private final LongCounter toolSearchCalls;

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

        this.requestInProgress = meter.upDownCounterBuilder("ikanos.request.in_progress")
                .setDescription("Number of requests a server adapter is working on")
                .build();

        this.requestRejected = meter.counterBuilder("ikanos.request.rejected.total")
                .setDescription("Requests refused with a 503 because the adapter was working on "
                        + "its maximum number of requests")
                .build();

        this.toolSearchTotal = meter.counterBuilder("ikanos.mcp.tool_search.total")
                .setDescription("MCP tool searches, by outcome: hit (at least one tool), miss "
                        + "(no tool) or error (input error or search unavailable)")
                .build();

        this.toolSearchDuration = meter.histogramBuilder("ikanos.mcp.tool_search.duration.seconds")
                .setDescription("Duration of MCP tool searches in seconds")
                .setUnit("s")
                .build();

        this.toolSearchCalls = meter.counterBuilder("ikanos.mcp.tool_search.calls.total")
                .setDescription("Tools called through the call meta-tool of deferred mode; "
                        + "compared with ikanos.mcp.tool_search.total, the share of searches "
                        + "that led to a call")
                .build();
    }

    /**
     * Record one MCP tool search.
     *
     * @param namespace the MCP endpoint namespace
     * @param outcome {@code hit}, {@code miss} or {@code error}
     * @param durationSec the search duration in seconds
     */
    public void recordToolSearch(String namespace, String outcome, double durationSec) {
        if (!enabled) return;
        Attributes attrs = nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_NAMESPACE), nonNull(namespace),
                stringKey("outcome"), nonNull(outcome)));
        toolSearchTotal.add(1, attrs);
        toolSearchDuration.record(durationSec, attrs);
    }

    /**
     * Record one tool call made through the call meta-tool of deferred mode.
     *
     * @param namespace the MCP endpoint namespace
     */
    public void recordToolSearchCall(String namespace) {
        if (!enabled) return;
        toolSearchCalls.add(1, nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_NAMESPACE), nonNull(namespace))));
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
     * Record that a server adapter started ({@code +1}) or finished ({@code -1}) working on a
     * request.
     */
    public void requestInProgress(String adapter, String namespace, long delta) {
        if (!enabled) return;
        requestInProgress.add(delta, adapterAttributes(adapter, namespace));
    }

    /**
     * Record a request refused because the adapter was working on its maximum number of requests.
     */
    public void recordRequestRejected(String adapter, String namespace) {
        if (!enabled) return;
        requestRejected.add(1, adapterAttributes(adapter, namespace));
    }

    private static Attributes adapterAttributes(String adapter, String namespace) {
        return nonNull(Attributes.of(
                nonNullStringKey(TelemetryBootstrap.ATTR_ADAPTER_TYPE), nonNull(adapter),
                nonNullStringKey(TelemetryBootstrap.ATTR_NAMESPACE),
                nonNull(namespace != null ? namespace : "unknown")));
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
