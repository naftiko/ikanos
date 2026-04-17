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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.logging.Logger;

/**
 * Bootstraps the OpenTelemetry SDK for the Naftiko engine.
 *
 * <p>Initialized once in {@code Capability} during startup. Uses OTel autoconfigure
 * ({@code OTEL_EXPORTER_*} env vars) for zero-config in Docker/K8s, with a fallback
 * to sensible defaults.</p>
 *
 * <p>Provides a singleton {@link Tracer} for span creation across the engine.</p>
 */
public class TelemetryBootstrap {

    static final String INSTRUMENTATION_NAME = "io.naftiko.engine";

    public static final AttributeKey<String> ATTR_ADAPTER_TYPE = AttributeKey.stringKey("naftiko.adapter.type");
    public static final AttributeKey<String> ATTR_CAPABILITY = AttributeKey.stringKey("naftiko.capability");
    public static final AttributeKey<String> ATTR_OPERATION_ID = AttributeKey.stringKey("naftiko.operation.id");
    public static final AttributeKey<String> ATTR_NAMESPACE = AttributeKey.stringKey("naftiko.namespace");
    public static final AttributeKey<Long> ATTR_STEP_INDEX = AttributeKey.longKey("naftiko.step.index");
    public static final AttributeKey<String> ATTR_STEP_CALL = AttributeKey.stringKey("naftiko.step.call");
    public static final AttributeKey<String> ATTR_STEP_MATCH = AttributeKey.stringKey("naftiko.step.match");
    public static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey("http.method");
    public static final AttributeKey<String> ATTR_HTTP_URL = AttributeKey.stringKey("http.url");
    public static final AttributeKey<Long> ATTR_HTTP_STATUS_CODE = AttributeKey.longKey("http.status_code");
    public static final AttributeKey<String> ATTR_AGGREGATE_REF = AttributeKey.stringKey("naftiko.aggregate.ref");

    private static final TelemetryBootstrap NOOP = new TelemetryBootstrap(OpenTelemetry.noop());

    private static volatile TelemetryBootstrap instance;

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    private static final Logger logger = Logger.getLogger(TelemetryBootstrap.class.getName());

    private static final String AUTOCONFIGURE_CLASS =
            "io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk";

    TelemetryBootstrap(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    /**
     * Initialize the global TelemetryBootstrap instance with auto-configured SDK.
     *
     * <p>If the OTel SDK is not on the classpath (e.g. when Naftiko is embedded as a library),
     * falls back to a no-op instance so all span calls become zero-cost.</p>
     */
    public static TelemetryBootstrap init(String serviceName) {
        try {
            Class.forName(AUTOCONFIGURE_CLASS);
            instance = new TelemetryBootstrap(buildAutoConfigured(serviceName));
        } catch (ClassNotFoundException e) {
            logger.info("OpenTelemetry SDK not on classpath — telemetry disabled");
            instance = NOOP;
        } catch (LinkageError | RuntimeException e) {
            logger.warning("OpenTelemetry SDK initialization failed \u2014 telemetry disabled: "
                    + e.getMessage());
            instance = NOOP;
        }
        return instance;
    }

    /**
     * Builds an auto-configured OpenTelemetry SDK instance.
     * Extracted into a separate method so the class reference to AutoConfiguredOpenTelemetrySdk
     * is only resolved when the class is confirmed present on the classpath.
     */
    private static OpenTelemetry buildAutoConfigured(String serviceName) {
        var builder = io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk.builder();
        return builder
                .addResourceCustomizer((resource, config) ->
                        resource.merge(io.opentelemetry.sdk.resources.Resource.create(
                                Attributes.of(AttributeKey.stringKey("service.name"), serviceName))))
                .build()
                .getOpenTelemetrySdk();
    }

    /**
     * Initialize with a provided OpenTelemetry instance (for testing).
     */
    public static TelemetryBootstrap init(OpenTelemetry openTelemetry) {
        instance = new TelemetryBootstrap(openTelemetry);
        return instance;
    }

    /**
     * Get the global instance, or a no-op instance if not initialized.
     */
    public static TelemetryBootstrap get() {
        TelemetryBootstrap current = instance;
        return current != null ? current : NOOP;
    }

    /**
     * Reset the global instance (for testing).
     */
    public static void reset() {
        instance = null;
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public Tracer getTracer() {
        return tracer;
    }

    /**
     * Start a SERVER span for an inbound adapter request.
     */
    public Span startServerSpan(String adapterType, String operationId) {
        return tracer.spanBuilder(adapterType + ".request")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(ATTR_ADAPTER_TYPE, adapterType)
                .setAttribute(ATTR_OPERATION_ID, operationId != null ? operationId : "unknown")
                .startSpan();
    }

    /**
     * Start an INTERNAL span for a call step.
     */
    public Span startStepCallSpan(int stepIndex, String call) {
        return tracer.spanBuilder("step.call")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_STEP_INDEX, stepIndex)
                .setAttribute(ATTR_STEP_CALL, call != null ? call : "unknown")
                .startSpan();
    }

    /**
     * Start an INTERNAL span for a lookup step.
     */
    public Span startStepLookupSpan(int stepIndex, String match) {
        return tracer.spanBuilder("step.lookup")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_STEP_INDEX, stepIndex)
                .setAttribute(ATTR_STEP_MATCH, match != null ? match : "unknown")
                .startSpan();
    }

    /**
     * Start an INTERNAL span for an aggregate function execution.
     */
    public Span startAggregateFunctionSpan(String ref) {
        return tracer.spanBuilder("aggregate.function")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_AGGREGATE_REF, ref != null ? ref : "unknown")
                .startSpan();
    }

    /**
     * Start a CLIENT span for an outbound HTTP call.
     */
    public Span startClientSpan(String method, String url) {
        return tracer.spanBuilder("http.client." + (method != null ? method : "UNKNOWN"))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(ATTR_HTTP_METHOD, method != null ? method : "UNKNOWN")
                .setAttribute(ATTR_HTTP_URL, url != null ? url : "unknown")
                .startSpan();
    }

    /**
     * Record an error on a span.
     */
    public static void recordError(Span span, Throwable error) {
        if (span != null && error != null) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getMessage());
        }
    }

    /**
     * End a span safely.
     */
    public static void endSpan(Span span) {
        if (span != null) {
            span.end();
        }
    }
}
