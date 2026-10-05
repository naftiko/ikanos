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
package io.ikanos.engine.consumes;

import java.util.concurrent.atomic.AtomicBoolean;
import io.ikanos.engine.observability.OtelNullSafety;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * A prepared call to one consumed operation: the request is built, nothing is sent yet.
 *
 * <p>Produced by {@link ClientAdapter#prepare(String, java.util.Map)}. Preparation and invocation
 * stay separate so that templating and parameter errors surface before any network call.</p>
 *
 * <p>{@link #invoke()} is a template method: it opens the OpenTelemetry client span, runs the
 * protocol-specific {@link #doInvoke()} inside it, annotates the span from the result, records
 * metrics, and closes the span. A new adapter therefore cannot forget to trace. Trace-context
 * propagation onto the wire is protocol specific and belongs in {@link #doInvoke()}.</p>
 */
public abstract class ConsumedInvocation {

    /** Span naming inputs supplied by each protocol. */
    public record ClientSpanDescriptor(String operation, String target) {
    }

    private final AtomicBoolean invoked = new AtomicBoolean();

    /** @return the namespace of the adapter that prepared this invocation */
    public abstract String namespace();

    /** @return the output declarations of the target operation */
    public abstract ConsumedOperationView operation();

    /**
     * Execute the call exactly once.
     *
     * @return the result; never {@code null}
     * @throws IllegalStateException if called more than once
     * @throws Exception if the protocol call fails
     */
    public final ConsumedResult invoke() throws Exception {
        if (!invoked.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Consumed invocation already executed for namespace '" + namespace() + "'");
        }

        TelemetryBootstrap telemetry = TelemetryBootstrap.get();
        ClientSpanDescriptor descriptor = spanDescriptor();
        Span span = telemetry.startClientSpan(descriptor.operation(), descriptor.target(),
                namespace());
        long startNanos = System.nanoTime();
        ConsumedResult result = null;
        try (Scope scope = span.makeCurrent()) {
            result = doInvoke();
            if (result == null) {
                throw new IllegalStateException(
                        "Client adapter for namespace '" + namespace() + "' returned no result");
            }
            annotateSpan(span, result);
            return result;
        } catch (Exception e) {
            TelemetryBootstrap.recordError(span, e);
            throw e;
        } finally {
            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            recordMetrics(telemetry, result, durationSec);
            TelemetryBootstrap.endSpan(span);
        }
    }

    /**
     * Protocol-specific execution, called once inside the client span.
     *
     * @return the result; must not be {@code null}
     * @throws Exception if the call fails
     */
    protected abstract ConsumedResult doInvoke() throws Exception;

    /** @return the span naming inputs for this call */
    protected abstract ClientSpanDescriptor spanDescriptor();

    /**
     * Record the outcome on the client span. The default sets the HTTP-equivalent status code and
     * marks the span as failed on {@link Outcome#UPSTREAM_ERROR}.
     *
     * @param span   the current client span
     * @param result the result of {@link #doInvoke()}
     */
    protected void annotateSpan(Span span, ConsumedResult result) {
        span.setAttribute(
                OtelNullSafety.nonNullLongKey(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE),
                result.status());
        if (result.outcome() == Outcome.UPSTREAM_ERROR) {
            span.setStatus(StatusCode.ERROR, "status " + result.status());
        }
    }

    /**
     * Record client metrics. Called in all cases, including failure. The default records nothing;
     * protocols with a dedicated metric override it.
     *
     * @param telemetry   the telemetry bootstrap
     * @param result      the result, or {@code null} when the call failed
     * @param durationSec the call duration in seconds
     */
    protected void recordMetrics(TelemetryBootstrap telemetry, ConsumedResult result,
            double durationSec) {
        // No protocol-neutral client metric yet.
    }
}
