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
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.naftiko.spec.ObservabilitySpec;
import java.util.HashMap;
import java.util.Map;
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
@SuppressWarnings("null") // OTel SDK AttributeKey constants lack @Nonnull — safe at this boundary
public class TelemetryBootstrap {

    static final String INSTRUMENTATION_NAME = "io.naftiko.engine";

    public static final AttributeKey<String> ATTR_ADAPTER_TYPE = AttributeKey.stringKey("naftiko.adapter.type");
    public static final AttributeKey<String> ATTR_CAPABILITY = AttributeKey.stringKey("naftiko.capability");
    public static final AttributeKey<String> ATTR_OPERATION_ID = AttributeKey.stringKey("naftiko.operation.id");
    public static final AttributeKey<String> ATTR_NAMESPACE = AttributeKey.stringKey("naftiko.namespace");
    public static final AttributeKey<Long> ATTR_STEP_INDEX = AttributeKey.longKey("naftiko.step.index");
    public static final AttributeKey<String> ATTR_STEP_CALL = AttributeKey.stringKey("naftiko.step.call");
    public static final AttributeKey<String> ATTR_STEP_MATCH = AttributeKey.stringKey("naftiko.step.match");
    public static final AttributeKey<String> ATTR_STEP_SCRIPT_FILE = AttributeKey.stringKey("naftiko.step.script.file");
    public static final AttributeKey<String> ATTR_STEP_SCRIPT_LANGUAGE = AttributeKey.stringKey("naftiko.step.script.language");
    public static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    public static final AttributeKey<String> ATTR_HTTP_URL = AttributeKey.stringKey("url.full");
    public static final AttributeKey<Long> ATTR_HTTP_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    public static final AttributeKey<String> ATTR_AGGREGATE_REF = AttributeKey.stringKey("naftiko.aggregate.ref");

    private static final TelemetryBootstrap NOOP = new TelemetryBootstrap(OpenTelemetry.noop());

    private static volatile TelemetryBootstrap instance;

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final EngineMetrics metrics;
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
        this(openTelemetry, null, null);
    }

    TelemetryBootstrap(OpenTelemetry openTelemetry, Object metricReader,
            DelegatingSpanProcessor delegatingSpanProcessor) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.metrics = new EngineMetrics(meter);
        this.metricReader = metricReader;
        this.delegatingSpanProcessor = delegatingSpanProcessor;
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
            instance = NOOP;
            return instance;
        }
        try {
            Class.forName(AUTOCONFIGURE_CLASS);
            instance = buildAutoConfigured(serviceName, observabilitySpec);
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
     * Builds an auto-configured OpenTelemetry SDK instance with a {@link PullMetricReader}
     * registered for Prometheus scraping via the Control Port.
     */
    private static TelemetryBootstrap buildAutoConfigured(String serviceName,
            ObservabilitySpec observabilitySpec) {
        PullMetricReader pullReader = new PullMetricReader();
        DelegatingSpanProcessor spanProcessorSlot = new DelegatingSpanProcessor();
        Map<String, String> specProperties = buildSpecProperties(observabilitySpec);
        var builder = io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk.builder();
        if (!specProperties.isEmpty()) {
            builder.addPropertiesSupplier(() -> specProperties);
        }
        OpenTelemetry otel = builder
                .addResourceCustomizer((resource, config) ->
                        resource.merge(io.opentelemetry.sdk.resources.Resource.create(
                                Attributes.of(AttributeKey.stringKey("service.name"), serviceName))))
                .addMeterProviderCustomizer((meterProviderBuilder, config) ->
                        meterProviderBuilder.registerMetricReader(pullReader))
                .addTracerProviderCustomizer((tracerProviderBuilder, config) ->
                        tracerProviderBuilder.addSpanProcessor(spanProcessorSlot))
                .build()
                .getOpenTelemetrySdk();
        return new TelemetryBootstrap(otel, pullReader, spanProcessorSlot);
    }

    /**
     * Converts spec-driven observability settings into OTel autoconfigure property overrides.
     */
    static Map<String, String> buildSpecProperties(ObservabilitySpec spec) {
        Map<String, String> props = new HashMap<>();
        if (spec == null) {
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
        if (spec.getExporters() != null && spec.getExporters().getOtlp() != null) {
            String endpoint = spec.getExporters().getOtlp().getEndpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                props.put("otel.exporter.otlp.endpoint", endpoint);
            }
        }
        return props;
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
     * Reset the global instance. <strong>Test-only</strong> — do not call from production code.
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

    public EngineMetrics getMetrics() {
        return metrics;
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
        return tracer.spanBuilder(adapterType + ".request")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(ATTR_ADAPTER_TYPE, adapterType)
                .setAttribute(ATTR_OPERATION_ID, operationId != null ? operationId : "unknown")
                .startSpan();
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
        boolean hasRemoteParent = parentContext != null
                && Span.fromContext(parentContext).getSpanContext().isValid()
                && Span.fromContext(parentContext).getSpanContext().isRemote();
        SpanKind kind = hasRemoteParent ? SpanKind.INTERNAL : SpanKind.SERVER;
        SpanBuilder builder = tracer.spanBuilder(adapterType + ".request")
                .setSpanKind(kind)
                .setAttribute(ATTR_ADAPTER_TYPE, adapterType)
                .setAttribute(ATTR_OPERATION_ID, operationId != null ? operationId : "unknown");
        if (parentContext != null) {
            builder.setParent(parentContext);
        }
        if (httpMethod != null) {
            builder.setAttribute(ATTR_HTTP_METHOD, httpMethod);
        }
        if (capabilityName != null) {
            builder.setAttribute(ATTR_CAPABILITY, capabilityName);
        }
        return builder.startSpan();
    }

    /**
     * Start an INTERNAL span for a call step.
     */
    public Span startStepCallSpan(int stepIndex, String call, String namespace) {
        SpanBuilder builder = tracer.spanBuilder("step.call")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_STEP_INDEX, stepIndex)
                .setAttribute(ATTR_STEP_CALL, call != null ? call : "unknown");
        if (namespace != null) {
            builder.setAttribute(ATTR_NAMESPACE, namespace);
        }
        return builder.startSpan();
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
     * Start an INTERNAL span for a script step.
     */
    public Span startStepScriptSpan(int stepIndex, String file, String language) {
        return tracer.spanBuilder("step.script")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_STEP_INDEX, stepIndex)
                .setAttribute(ATTR_STEP_SCRIPT_FILE, file != null ? file : "unknown")
                .setAttribute(ATTR_STEP_SCRIPT_LANGUAGE, language != null ? language : "unknown")
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
     * Start an INTERNAL span for MCP tool handling.
     *
     * <p>ToolHandler is not a network entry point — the SERVER span belongs
     * to McpServerResource.  This span captures the tool-dispatch logic.</p>
     */
    public Span startToolHandlerSpan(String toolName) {
        return tracer.spanBuilder("mcp.tool")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(ATTR_ADAPTER_TYPE, "mcp")
                .setAttribute(ATTR_OPERATION_ID, toolName != null ? toolName : "unknown")
                .startSpan();
    }

    /**
     * Start a CLIENT span for an outbound HTTP call.
     */
    public Span startClientSpan(String method, String url, String namespace) {
        SpanBuilder builder = tracer.spanBuilder("http.client." + (method != null ? method : "UNKNOWN"))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(ATTR_HTTP_METHOD, method != null ? method : "UNKNOWN")
                .setAttribute(ATTR_HTTP_URL, url != null ? url : "unknown");
        if (namespace != null) {
            builder.setAttribute(ATTR_NAMESPACE, namespace);
        }
        return builder.startSpan();
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
