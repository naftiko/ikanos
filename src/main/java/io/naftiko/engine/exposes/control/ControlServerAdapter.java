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

import io.naftiko.Capability;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.telemetry.TelemetryBootstrap;
import io.naftiko.spec.ObservabilitySpec;
import io.naftiko.spec.ObservabilityTracesLocalSpec;
import io.naftiko.spec.exposes.ControlManagementSpec;
import io.naftiko.spec.exposes.ControlServerSpec;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

/**
 * Server adapter for the control port — management plane for health, metrics, traces, and runtime
 * diagnostics. Endpoints are engine-provided; capability authors declare the adapter but do not
 * define routes.
 *
 * <p>Each {@link org.restlet.resource.ServerResource} subclass is instantiated per request by
 * Restlet's routing layer and receives shared state via {@link Context} attributes.</p>
 */
public class ControlServerAdapter extends ServerAdapter {

    private final Router router;
    private final TraceRingBuffer traceRingBuffer;

    public ControlServerAdapter(Capability capability, ControlServerSpec serverSpec) {
        super(capability, serverSpec);

        ControlManagementSpec management = serverSpec.getManagement();
        ObservabilitySpec observability = serverSpec.getObservability();

        // Trace ring buffer — shared between the SpanProcessor and the TracesResource
        int bufferSize = resolveTracesBufferSize(observability);
        this.traceRingBuffer = new TraceRingBuffer(bufferSize);

        // Shared context for per-request ServerResource instances
        Context context = new Context();
        context.getAttributes().put("capability", capability);
        context.getAttributes().put("traceRingBuffer", traceRingBuffer);

        this.router = new Router(context);

        // Management endpoints
        if (management.isHealth()) {
            router.attach("/health/live", HealthLiveResource.class);
            router.attach("/health/ready", HealthReadyResource.class);
        }

        if (management.isInfo()) {
            router.attach("/status", StatusResource.class);
        }

        // Observability endpoints — metrics and traces live under observability
        if (isMetricsEnabled(observability)) {
            router.attach("/metrics", MetricsResource.class);
        }

        if (isTracesEnabled(observability)) {
            router.attach("/traces", TracesResource.class);
            router.attach("/traces/{traceId}", TracesResource.class);
            // Wire the span processor so completed spans flow into the ring buffer
            TelemetryBootstrap.get().registerSpanProcessor(
                    new TraceCapturingSpanProcessor(traceRingBuffer));
        }

        Restlet chain = buildServerChain(this.router);
        initServer(serverSpec.getAddress(), serverSpec.getPort(), chain);
    }

    boolean isMetricsEnabled(ObservabilitySpec observability) {
        if (observability == null) {
            return true; // default: enabled
        }
        if (!observability.isEnabled()) {
            return false;
        }
        if (observability.getMetrics() != null && observability.getMetrics().getLocal() != null) {
            return observability.getMetrics().getLocal().isEnabled();
        }
        return true; // default: enabled
    }

    boolean isTracesEnabled(ObservabilitySpec observability) {
        if (observability == null) {
            return true; // default: enabled
        }
        if (!observability.isEnabled()) {
            return false;
        }
        if (observability.getTraces() != null && observability.getTraces().getLocal() != null) {
            return observability.getTraces().getLocal().isEnabled();
        }
        return true; // default: enabled
    }

    int resolveTracesBufferSize(ObservabilitySpec observability) {
        if (observability != null && observability.getTraces() != null
                && observability.getTraces().getLocal() != null) {
            return observability.getTraces().getLocal().getBufferSize();
        }
        return new ObservabilityTracesLocalSpec().getBufferSize(); // default: 100
    }

    public ControlServerSpec getControlServerSpec() {
        return (ControlServerSpec) getSpec();
    }

    public Router getRouter() {
        return router;
    }

    public TraceRingBuffer getTraceRingBuffer() {
        return traceRingBuffer;
    }
}
