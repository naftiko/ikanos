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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import javax.annotation.Nonnull;

/**
 * Shared fixtures and accessors for OpenTelemetry-based tests.
 *
 * <p>Centralizes the boilerplate previously copy-pasted into every test class:
 * tracer / meter provider construction, in-memory exporters, W3C propagators,
 * and {@code @Nonnull} accessors for SDK attributes. Tests should call these
 * helpers rather than reproducing the same wiring locally.</p>
 */
public final class OtelTestFixtures {

    private OtelTestFixtures() {
        // Utility class — no instances.
    }

    /**
     * Builds an {@link SdkTracerProvider} that exports spans to the given in-memory exporter
     * via a {@link SimpleSpanProcessor}.
     */
    @Nonnull
    public static SdkTracerProvider tracerProvider(InMemorySpanExporter exporter) {
        return OtelNullSafety.nonNull(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build());
    }

    /**
     * Builds an {@link SdkMeterProvider} that exposes metrics via the given reader.
     */
    @Nonnull
    public static SdkMeterProvider meterProvider(MetricReader reader) {
        return OtelNullSafety.nonNull(SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build());
    }

    /**
     * Convenience overload accepting an {@link InMemoryMetricReader}.
     */
    @Nonnull
    public static SdkMeterProvider meterProvider(InMemoryMetricReader reader) {
        return meterProvider((MetricReader) reader);
    }

    /**
     * Returns {@link ContextPropagators} configured with W3C trace-context propagation.
     */
    @Nonnull
    public static ContextPropagators w3cPropagators() {
        return OtelNullSafety.nonNull(ContextPropagators.create(
                OtelNullSafety.nonNull(W3CTraceContextPropagator.getInstance())));
    }

    /**
     * Reads a string attribute through a {@code @Nonnull}-narrowed key. Returns {@code null}
     * when the attribute is absent.
     */
    public static String stringAttribute(Attributes attributes, AttributeKey<String> key) {
        return attributes.get(OtelNullSafety.nonNullStringKey(key));
    }

    /**
     * Reads a long attribute through a {@code @Nonnull}-narrowed key. Returns {@code null}
     * when the attribute is absent.
     */
    public static Long longAttribute(Attributes attributes, AttributeKey<Long> key) {
        return attributes.get(OtelNullSafety.nonNullLongKey(key));
    }

    /**
     * Builds an {@link Attributes} bundle containing a single string entry.
     */
    @Nonnull
    public static Attributes attributes(AttributeKey<String> key, String value) {
        return OtelNullSafety.nonNull(Attributes.of(
                OtelNullSafety.nonNullStringKey(key), OtelNullSafety.nonNull(value)));
    }

    /**
     * Returns {@code AttributeKey.stringKey(name)} typed as {@code @Nonnull}.
     */
    @Nonnull
    public static AttributeKey<String> stringKey(String name) {
        return OtelNullSafety.stringKey(OtelNullSafety.nonNull(name));
    }
}
