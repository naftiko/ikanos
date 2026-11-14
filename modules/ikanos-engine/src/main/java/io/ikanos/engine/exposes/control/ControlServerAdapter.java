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
package io.ikanos.engine.exposes.control;

import java.util.List;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.ikanos.spec.observability.ObservabilitySpec;
import io.ikanos.spec.observability.ObservabilityTracesLocalSpec;
import io.ikanos.spec.exposes.control.ControlManagementSpec;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.exposes.control.ScriptingManagementSpec;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.routing.Filter;
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

        // Scripting governance endpoint
        ScriptingManagementSpec scripting = management.getScripting();
        if (scripting != null) {
            context.getAttributes().put("scriptingSpec", scripting);
            router.attach("/scripting", ScriptingResource.class);
        }

        // Observability endpoints — metrics and traces live under observability
        if (isMetricsEnabled(observability)) {
            router.attach("/metrics", MetricsResource.class);
        }

        if (isTracesEnabled(observability)) {
            router.attach("/traces", TracesResource.class);
            router.attach("/traces/{traceId}", TracesResource.class);
            // Wire the span processor so completed spans flow into the ring buffer. It implements
            // the SDK SpanProcessor, so only build it when the optional SDK is active (#800);
            // TracesResource already answers when it is not.
            TelemetryBootstrap telemetry = TelemetryBootstrap.get();
            if (telemetry.isSdkActive()) {
                telemetry.registerSpanProcessor(new TraceCapturingSpanProcessor(traceRingBuffer));
            }
        }

        Restlet chain = buildServerChain(this.router);
        initServer(serverSpec.getAddress(), serverSpec.getPort(), chain);
    }

    /**
     * The control port has no {@code authorization} block, so it keeps requiring every scope listed
     * in {@code authentication.scopes} on every call, as all adapters did up to 1.0.0-beta9 (#999):
     * the management endpoints stay closed to a valid token without them.
     */
    @Override
    protected Restlet createOAuth2Restlet(OAuth2AuthenticationSpec oauth2, Restlet next) {
        List<String> scopes = oauth2.getScopes();
        if (scopes == null || scopes.isEmpty()) {
            return super.createOAuth2Restlet(oauth2, next);
        }
        return super.createOAuth2Restlet(oauth2, requireAllScopes(scopes, next));
    }

    /**
     * A filter that lets a call reach {@code next} only when its validated token carries every
     * scope; otherwise it marks the response with an {@link AccessControl.Denial}, which the OAuth
     * 2.1 restlet in front of it turns into a {@code 403 insufficient_scope} challenge.
     */
    static Restlet requireAllScopes(List<String> scopes, Restlet next) {
        AuthorizationSpec required = new AuthorizationSpec(scopes, null);
        return new Filter(next.getContext(), next) {
            @Override
            protected int beforeHandle(Request request, Response response) {
                return AccessControl.enforce(required, request, response) ? CONTINUE : STOP;
            }
        };
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
