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

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import javax.annotation.Nonnull;
import org.restlet.Request;

/**
 * Shared bridge between the OpenTelemetry SDK and the Restlet request/response model.
 *
 * <p>Concentrates the {@code @Nonnull} narrowing of three SDK values that every adapter
 * (REST, MCP, Skill, HTTP client) needs when extracting or injecting W3C trace context:</p>
 * <ul>
 *   <li>{@link Context#current()} — current OTel context, guaranteed non-null</li>
 *   <li>{@link RestletHeaderGetter#INSTANCE} — text-map getter for inbound headers</li>
 *   <li>{@link RestletHeaderSetter#INSTANCE} — text-map setter for outbound headers</li>
 * </ul>
 *
 * <p>Without this class, each adapter previously needed either
 * {@code @SuppressWarnings("null")} or local {@code Objects.requireNonNull} ceremony at every
 * propagator call site. The intent is now declared once.</p>
 */
public final class OtelRestletBridge {

    private OtelRestletBridge() {
        // Utility class — no instances.
    }

    /**
     * Extracts a W3C trace {@link Context} from the inbound {@link Request} headers using the
     * current {@code TextMapPropagator}. Falls back to {@link Context#current()} when no
     * {@code traceparent} header is present.
     */
    @Nonnull
    public static Context extractContext(Request request) {
        return OtelNullSafety.nonNull(TelemetryBootstrap.get().getOpenTelemetry()
                .getPropagators().getTextMapPropagator()
                .extract(currentContext(), request, headerGetter()));
    }

    /**
     * Injects the current W3C trace {@link Context} into the outbound {@link Request} headers
     * using the current {@code TextMapPropagator}.
     */
    public static void injectContext(Request request) {
        TelemetryBootstrap.get().getOpenTelemetry().getPropagators().getTextMapPropagator()
                .inject(currentContext(), request, headerSetter());
    }

    /**
     * Returns the current OpenTelemetry {@link Context} typed as {@code @Nonnull}.
     */
    @Nonnull
    public static Context currentContext() {
        return OtelNullSafety.nonNull(Context.current());
    }

    /**
     * Returns the Restlet text-map getter singleton typed as {@code @Nonnull}.
     */
    @Nonnull
    public static TextMapGetter<Request> headerGetter() {
        return OtelNullSafety.nonNull(RestletHeaderGetter.INSTANCE);
    }

    /**
     * Returns the Restlet text-map setter singleton typed as {@code @Nonnull}.
     */
    @Nonnull
    public static TextMapSetter<Request> headerSetter() {
        return OtelNullSafety.nonNull(RestletHeaderSetter.INSTANCE);
    }
}
