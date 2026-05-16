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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.ikanos.spec.observability.ObservabilitySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

    static final String INSTRUMENTATION_NAME = "io.ikanos.engine";

    public static final AttributeKey<String> ATTR_ADAPTER_TYPE = AttributeKey.stringKey("ikanos.adapter.type");
    public static final AttributeKey<String> ATTR_CAPABILITY = AttributeKey.stringKey("ikanos.capability");
    public static final AttributeKey<String> ATTR_OPERATION_ID = AttributeKey.stringKey("ikanos.operation.id");
    public static final AttributeKey<String> ATTR_NAMESPACE = AttributeKey.stringKey("ikanos.namespace");
    public static final AttributeKey<Long> ATTR_STEP_INDEX = AttributeKey.longKey("ikanos.step.index");
    public static final AttributeKey<String> ATTR_STEP_CALL = AttributeKey.stringKey("ikanos.step.call");
    public static final AttributeKey<String> ATTR_STEP_MATCH = AttributeKey.stringKey("ikanos.step.match");
    public static final AttributeKey<String> ATTR_STEP_SCRIPT_FILE = AttributeKey.stringKey("ikanos.step.script.file");
    public static final AttributeKey<String> ATTR_STEP_SCRIPT_LANGUAGE = AttributeKey.stringKey("ikanos.step.script.language");
    public static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    public static final AttributeKey<String> ATTR_HTTP_URL = AttributeKey.stringKey("url.full");
    public static final AttributeKey<Long> ATTR_HTTP_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    public static final AttributeKey<String> ATTR_AGGREGATE_REF = AttributeKey.stringKey("ikanos.aggregate.ref");

    private static final TelemetryBootstrap NOOP = new TelemetryBootstrap(OpenTelemetry.noop());

    /**
     * Holds the current global instance. Replaced atomically by {@link #init(String, ObservabilitySpec)},
     * {@link #init(OpenTelemetry)}, and {@link #reset()}. Reads are lock-free via {@link #get()}.
     * {@code null} means "not initialized" — readers fall back to {@link #NOOP}.
     * (See SonarQube {@code java:S3077}.)
     */
    private static final AtomicReference<TelemetryBootstrap> INSTANCE = new AtomicReference<>();

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final EngineMetrics metrics;
    // When false, all span/metric recording is skipped with zero overhead (no string
    // concatenation, no Attributes allocation, no virtual dispatch on noop objects).
    private final boolean enabled;
    // Null when OTel is noop; holds a PullMetricReader when SDK is active.
    // Stored as Object to avoid loading SDK classes when the SDK is absent.
    private final Object metricReader;
    // Null when OTel is noop; set when SDK is active so that a SpanProcessor
    // (e.g. TraceCapturingSpanProcessor) can be wired after SDK initialization.
    private final DelegatingSpanProcessor delegatingSpanProcessor;

    private static final Logger logger = Logger.getLogger(TelemetryBootstrap.class.getName());

    private static final String AUTOCONFIGURE_CLASS =
            "io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk";

    TelemetryBootstrap(OpenTelemetry openTelemetry) {
        this(openTelemetry, null, null, false);
    }

    TelemetryBootstrap(OpenTelemetry openTelemetry, Object metricReader,
            DelegatingSpanProcessor delegatingSpanProcessor, boolean enabled) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.metrics = new EngineMetrics(meter, enabled);
        this.metricReader = metricReader;
        this.delegatingSpanProcessor = delegatingSpanProcessor;
        this.enabled = enabled;
    }

    /**
     * Initialize the global TelemetryBootstrap instance with auto-configured SDK.
     *
     * <p>If the OTel SDK is not on the classpath (e.g. when Naftiko is embedded as a library),
     * falls back to a no-op instance so all span calls become zero-cost.</p>
     */
    public static TelemetryBootstrap init(String serviceName) {
        return init(serviceName, null);
    }

    /**
     * Initialize with spec-driven observability configuration.
     *
     * <p>When {@code observabilitySpec} is non-null and {@code enabled} is {@code false},
     * telemetry is disabled entirely (no SDK init, no overhead). Otherwise the spec's
     * sampling rate, propagation format, and OTLP endpoint are applied as SDK property
     * overrides — values not set in the spec fall through to OTel env vars.</p>
     */
    public static TelemetryBootstrap init(String serviceName, ObservabilitySpec observabilitySpec) {
        if (observabilitySpec != null && !observabilitySpec.isEnabled()) {
            logger.info("Observability disabled via spec — telemetry off");
            INSTANCE.set(NOOP);
            return NOOP;
        }
        TelemetryBootstrap newInstance;
        try {
            Class.forName(AUTOCONFIGURE_CLASS);
            newInstance = buildAutoConfigured(serviceName, observabilitySpec);
        } catch (ClassNotFoundException e) {
            logger.info("OpenTelemetry SDK not on classpath — telemetry disabled");
            newInstance = NOOP;
        } catch (LinkageError | RuntimeException e) {
            logger.warning("OpenTelemetry SDK initialization failed \u2014 telemetry disabled: "
                    + e.getMessage());
            newInstance = NOOP;
        }
        INSTANCE.set(newInstance);
        return newInstance;
    }

    /**
     * Builds an auto-configured OpenTelemetry SDK instance with a {@link PullMetricReader}
     * registered for Prometheus scraping via the Control Port.
     */
    private static TelemetryBootstrap buildAutoConfigured(String serviceName,
            ObservabilitySpec observabilitySpec) {
        PullMetricReader pullReader = new PullMetricReader();
        DelegatingSpanProcessor spanProcessorSlot = new DelegatingSpanProcessor();
        Map<String, String> specProperties = buildSpecProperties(observabilitySpec);
        var builder = io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk.builder();
        builder.addPropertiesSupplier(() -> specProperties);
        OpenTelemetry otel = builder
                .addResourceCustomizer((resource, config) ->
                        resource.merge(io.opentelemetry.sdk.resources.Resource.create(
                                nonNull(Attributes.of(stringKey("service.name"),
                                        nonNull(serviceName))))))
                .addMeterProviderCustomizer((meterProviderBuilder, config) ->
                        meterProviderBuilder.registerMetricReader(pullReader))
                .addTracerProviderCustomizer((tracerProviderBuilder, config) ->
                        tracerProviderBuilder.addSpanProcessor(spanProcessorSlot))
                .build()
                .getOpenTelemetrySdk();
        return new TelemetryBootstrap(otel, pullReader, spanProcessorSlot, true);
    }

    /**
     * Converts spec-driven observability settings into OTel autoconfigure property overrides.
     */
    static Map<String, String> buildSpecProperties(ObservabilitySpec spec) {
        Map<String, String> props = new HashMap<>();
        if (spec == null) {
            // No observability spec — default to none to avoid spurious connection errors
            // to localhost:4317 (e.g. inside Docker). OTel env vars still take precedence.
            props.put("otel.traces.exporter", "none");
            props.put("otel.metrics.exporter", "none");
            props.put("otel.logs.exporter", "none");
            return props;
        }
        if (spec.getTraces() != null) {
            double sampling = spec.getTraces().getSampling();
            if (sampling < 1.0) {
                props.put("otel.traces.sampler", "traceidratio");
                props.put("otel.traces.sampler.arg", String.valueOf(sampling));
            }
            String propagation = spec.getTraces().getPropagation();
            if (propagation != null) {
                String propagators = "w3c".equals(propagation)
                        ? "tracecontext,baggage"
                        : "b3multi";
                props.put("otel.propagators", propagators);
            }
        }
        boolean hasOtlpEndpoint = spec.getExporters() != null
                && spec.getExporters().getOtlp() != null
                && spec.getExporters().getOtlp().getEndpoint() != null
                && !spec.getExporters().getOtlp().getEndpoint().isBlank();
        if (hasOtlpEndpoint) {
            props.put("otel.exporter.otlp.endpoint", spec.getExporters().getOtlp().getEndpoint());
        } else {
            // No OTLP endpoint in spec — default to none to avoid spurious connection errors
            // to localhost:4317 (e.g. inside Docker). OTel env vars still take precedence.
            props.putIfAbsent("otel.traces.exporter", "none");
            props.putIfAbsent("otel.metrics.exporter", "none");
            props.putIfAbsent("otel.logs.exporter", "none");
        }
        return props;
    }

    /**
     * Initialize with a provided OpenTelemetry instance (for testing).
     */
    public static TelemetryBootstrap init(OpenTelemetry openTelemetry) {
        TelemetryBootstrap newInstance = new TelemetryBootstrap(openTelemetry, null, null, true);
        INSTANCE.set(newInstance);
        return newInstance;
    }

    /**
     * Get the global instance, or a no-op instance if not initialized.
     */
    public static TelemetryBootstrap get() {
        TelemetryBootstrap current = INSTANCE.get();
        return current != null ? current : NOOP;
    }

    /**
     * Reset the global instance. <strong>Test-only</strong> — do not call from production code.
     */
    public static void reset() {
        INSTANCE.set(null);
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public Tracer getTracer() {
        return tracer;
    }

    public EngineMetrics getMetrics() {
        return metrics;
    }

    /**
     * Returns {@code true} when telemetry is enabled (not noop mode).
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns {@code true} when the OTel SDK is active (not noop mode).
     */
    public boolean isSdkActive() {
        return delegatingSpanProcessor != null;
    }

    /**
     * Collect all OTel metrics and serialize them in Prometheus exposition text format.
     * Returns {@code null} when the OTel SDK is not active (noop mode).
     */
    public String collectPrometheusMetrics() {
        if (metricReader == null) {
            return null;
        }
        PullMetricReader reader = (PullMetricReader) metricReader;
        return PrometheusTextSerializer.serialize(reader.collectAllMetrics());
    }

    /**
     * Register a {@link io.opentelemetry.sdk.trace.SpanProcessor} to receive completed spans.
     *
     * <p>Used by the control adapter to wire the {@code TraceCapturingSpanProcessor} after
     * the OTel SDK has already been built. Has no effect in noop mode (SDK absent).</p>
     */
    public void registerSpanProcessor(Object spanProcessor) {
        if (delegatingSpanProcessor != null) {
            delegatingSpanProcessor.setDelegate(
                    (io.opentelemetry.sdk.trace.SpanProcessor) spanProcessor);
        }
    }

    /**
     * Start a SERVER span for an inbound adapter request.
     */
    public Span startServerSpan(String adapterType, String operationId) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder(adapterType + ".request")
            .setSpanKind(SpanKind.SERVER);
        setStringAttribute(builder, ATTR_ADAPTER_TYPE, adapterType);
        setStringAttribute(builder, ATTR_OPERATION_ID,
            operationId != null ? operationId : "unknown");
        return builder.startSpan();
    }

    /**
     * Start a span for an inbound adapter request with parent context and HTTP method.
     *
     * <p>When the extracted context carries a valid remote parent (i.e. the inbound request
     * contained a {@code traceparent} header), the span is INTERNAL because the true SERVER
     * span lives in the upstream caller.  Otherwise this is the entry point and the span
     * is SERVER.</p>
     */
    public Span startServerSpan(String adapterType, String operationId,
            io.opentelemetry.context.Context parentContext, String httpMethod,
            String capabilityName) {
        if (!enabled) return Span.getInvalid();
        boolean hasRemoteParent = parentContext != null
                && Span.fromContext(parentContext).getSpanContext().isValid()
                && Span.fromContext(parentContext).getSpanContext().isRemote();
        SpanKind kind = hasRemoteParent ? SpanKind.INTERNAL : SpanKind.SERVER;
        SpanBuilder builder = tracer.spanBuilder(adapterType + ".request")
                .setSpanKind(kind);
        setStringAttribute(builder, ATTR_ADAPTER_TYPE, adapterType);
        setStringAttribute(builder, ATTR_OPERATION_ID,
                operationId != null ? operationId : "unknown");
        if (parentContext != null) {
            builder.setParent(parentContext);
        }
        if (httpMethod != null) {
            setStringAttribute(builder, ATTR_HTTP_METHOD, httpMethod);
        }
        if (capabilityName != null) {
            setStringAttribute(builder, ATTR_CAPABILITY, capabilityName);
        }
        return builder.startSpan();
    }

    /**
     * Start an INTERNAL span for a call step.
     */
    public Span startStepCallSpan(int stepIndex, String call, String namespace) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder("step.call")
                .setSpanKind(SpanKind.INTERNAL);
        setLongAttribute(builder, ATTR_STEP_INDEX, stepIndex);
        setStringAttribute(builder, ATTR_STEP_CALL, call != null ? call : "unknown");
        if (namespace != null) {
            setStringAttribute(builder, ATTR_NAMESPACE, namespace);
        }
        return builder.startSpan();
    }

    /**
     * Start an INTERNAL span for a lookup step.
     */
    public Span startStepLookupSpan(int stepIndex, String match) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder("step.lookup")
            .setSpanKind(SpanKind.INTERNAL);
        setLongAttribute(builder, ATTR_STEP_INDEX, stepIndex);
        setStringAttribute(builder, ATTR_STEP_MATCH, match != null ? match : "unknown");
        return builder.startSpan();
    }

    /**
     * Start an INTERNAL span for a script step.
     */
    public Span startStepScriptSpan(int stepIndex, String file, String language) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder("step.script")
            .setSpanKind(SpanKind.INTERNAL);
        setLongAttribute(builder, ATTR_STEP_INDEX, stepIndex);
        setStringAttribute(builder, ATTR_STEP_SCRIPT_FILE, file != null ? file : "unknown");
        setStringAttribute(builder, ATTR_STEP_SCRIPT_LANGUAGE,
            language != null ? language : "unknown");
        return builder.startSpan();
    }

    /**
     * Start an INTERNAL span for an aggregate function execution.
     */
    public Span startAggregateFunctionSpan(String ref) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder("aggregate.function")
            .setSpanKind(SpanKind.INTERNAL);
        setStringAttribute(builder, ATTR_AGGREGATE_REF, ref != null ? ref : "unknown");
        return builder.startSpan();
    }

    /**
     * Start an INTERNAL span for MCP tool handling.
     *
     * <p>ToolHandler is not a network entry point — the SERVER span belongs
     * to McpServerResource.  This span captures the tool-dispatch logic.</p>
     */
    public Span startToolHandlerSpan(String toolName) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder("mcp.tool")
            .setSpanKind(SpanKind.INTERNAL);
        setStringAttribute(builder, ATTR_ADAPTER_TYPE, "mcp");
        setStringAttribute(builder, ATTR_OPERATION_ID, toolName != null ? toolName : "unknown");
        return builder.startSpan();
    }

    /**
     * Start a CLIENT span for an outbound HTTP call.
     */
    public Span startClientSpan(String method, String url, String namespace) {
        if (!enabled) return Span.getInvalid();
        SpanBuilder builder = tracer.spanBuilder("http.client." + (method != null ? method : "UNKNOWN"))
                .setSpanKind(SpanKind.CLIENT);
        setStringAttribute(builder, ATTR_HTTP_METHOD, method != null ? method : "UNKNOWN");
        setStringAttribute(builder, ATTR_HTTP_URL, url != null ? url : "unknown");
        if (namespace != null) {
            setStringAttribute(builder, ATTR_NAMESPACE, namespace);
        }
        return builder.startSpan();
    }

    private static void setStringAttribute(SpanBuilder builder, AttributeKey<String> key,
            String value) {
        builder.setAttribute(nonNullStringKey(key), nonNull(value));
    }

    private static void setLongAttribute(SpanBuilder builder, AttributeKey<Long> key, long value) {
        builder.setAttribute(nonNullLongKey(key), value);
    }

    /**
     * Record an error on a span.
     */
    public static void recordError(Span span, Throwable error) {
        if (span != null && error != null) {
            span.recordException(error);
            String message = error.getMessage();
            span.setStatus(StatusCode.ERROR,
                    nonNull(message != null ? message : error.getClass().getName()));
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
