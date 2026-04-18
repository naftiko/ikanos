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
import io.naftiko.spec.exposes.ControlEndpointsSpec;
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

        ControlEndpointsSpec endpoints = serverSpec.getEndpoints();

        // Trace ring buffer — shared between the SpanProcessor and the TracesResource
        int bufferSize = endpoints.getTraces().getBufferSize();
        this.traceRingBuffer = new TraceRingBuffer(bufferSize);

        // Shared context for per-request ServerResource instances
        Context context = new Context();
        context.getAttributes().put("capability", capability);
        context.getAttributes().put("traceRingBuffer", traceRingBuffer);

        this.router = new Router(context);

        // Operations endpoints (enabled by default)
        if (endpoints.isHealth()) {
            router.attach("/health/live", HealthLiveResource.class);
            router.attach("/health/ready", HealthReadyResource.class);
        }

        if (endpoints.isMetrics()) {
            router.attach("/metrics", MetricsResource.class);
        }

        if (endpoints.getTraces().isEnabled()) {
            router.attach("/traces", TracesResource.class);
            router.attach("/traces/{traceId}", TracesResource.class);
        }

        // Development endpoints (disabled by default)
        if (endpoints.isInfo()) {
            router.attach("/status", StatusResource.class);
        }

        Restlet chain = buildServerChain(this.router);
        initServer(serverSpec.getAddress(), serverSpec.getPort(), chain);
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
