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
import static io.ikanos.engine.observability.OtelNullSafety.stringKey;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Map;

/**
 * Everything {@link TelemetryBootstrap} needs from the OpenTelemetry SDK, kept in one class.
 *
 * <p>The SDK ({@code opentelemetry-sdk*}) is an optional dependency of the engine: an application
 * that embeds Ikanos without it must still run, in noop mode. {@code TelemetryBootstrap} therefore
 * only refers to this class through a field typed {@code SdkTelemetry} that stays {@code null} in
 * noop mode, and only calls it once the SDK is known to be present. No SDK type appears in
 * {@code TelemetryBootstrap}'s fields, method signatures or lambdas, so the JVM never has to load
 * one on the noop path (#800).</p>
 */
final class SdkTelemetry {

    private final OpenTelemetry openTelemetry;
    private final PullMetricReader metricReader;
    private final DelegatingSpanProcessor spanProcessorSlot;
    // The JVM metrics' recorder, or null when they aren't recorded (#737).
    private final AutoCloseable runtimeMetrics;

    private SdkTelemetry(OpenTelemetry openTelemetry, PullMetricReader metricReader,
            DelegatingSpanProcessor spanProcessorSlot, AutoCloseable runtimeMetrics) {
        this.openTelemetry = openTelemetry;
        this.metricReader = metricReader;
        this.spanProcessorSlot = spanProcessorSlot;
        this.runtimeMetrics = runtimeMetrics;
    }

    /**
     * Builds an auto-configured SDK with a {@link PullMetricReader} (Prometheus scraping via the
     * Control Port) and an empty span-processor slot (trace capture, wired later), and starts the
     * JVM metrics on it when {@link RuntimeMetrics} is available.
     */
    static SdkTelemetry autoConfigure(String serviceName, Map<String, String> properties) {
        PullMetricReader pullReader = new PullMetricReader();
        DelegatingSpanProcessor slot = new DelegatingSpanProcessor();
        OpenTelemetry otel = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> properties)
                .addResourceCustomizer((resource, config) ->
                        resource.merge(Resource.create(
                                nonNull(Attributes.of(stringKey("service.name"),
                                        nonNull(serviceName))))))
                .addMeterProviderCustomizer((meterProviderBuilder, config) ->
                        meterProviderBuilder.registerMetricReader(pullReader))
                .addTracerProviderCustomizer((tracerProviderBuilder, config) ->
                        tracerProviderBuilder.addSpanProcessor(slot))
                .build()
                .getOpenTelemetrySdk();
        AutoCloseable runtimeMetrics = RuntimeMetrics.available() ? RuntimeMetrics.start(otel) : null;
        return new SdkTelemetry(otel, pullReader, slot, runtimeMetrics);
    }

    OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    boolean recordsRuntimeMetrics() {
        return runtimeMetrics != null;
    }

    /** Serializes all collected metrics in Prometheus exposition text format. */
    String collectPrometheusMetrics() {
        return PrometheusTextSerializer.serialize(metricReader.collectAllMetrics());
    }

    /** Plugs a span processor into the slot registered when the SDK was built. */
    void registerSpanProcessor(Object spanProcessor) {
        spanProcessorSlot.setDelegate((SpanProcessor) spanProcessor);
    }
}
