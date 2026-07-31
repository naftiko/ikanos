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

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.context.Context;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.restlet.Request;

/**
 * Bridge between the OpenTelemetry SDK and the MCP JSON-RPC message model.
 *
 * <p>Extracts W3C trace context ({@code traceparent}, {@code tracestate}, {@code baggage}) from
 * the {@code params._meta} object of an inbound JSON-RPC request via
 * {@link McpMetaTraceContextGetter}. This is the transport-agnostic counterpart to
 * {@link OtelRestletBridge}: the MCP {@code 2026-07-28} revision documents these keys as the
 * canonical, message-level carrier for trace context propagation, so the same extraction applies
 * whether the request arrived over Streamable HTTP or stdio (which has no HTTP headers at
 * all).</p>
 *
 * <p>Falls back to {@link Context#current()} when no trace context is present in {@code _meta}.
 * </p>
 */
public final class McpMetaTraceContextBridge {

    private McpMetaTraceContextBridge() {
        // Utility class — no instances.
    }

    /**
     * Extracts a W3C trace {@link Context} from the {@code params._meta} object of the inbound
     * JSON-RPC {@code request} using the current {@code TextMapPropagator}. Falls back to
     * {@link Context#current()} when no {@code traceparent} entry is present.
     *
     * <p>Used by transports with no HTTP layer (stdio).</p>
     */
    @Nonnull
    public static Context extractContext(JsonNode request) {
        return extractContext(request, OtelRestletBridge.currentContext());
    }

    /**
     * Extracts a W3C trace {@link Context}, preferring the {@code params._meta} carrier of the
     * JSON-RPC {@code request} and falling back to the W3C {@code traceparent} HTTP header of
     * {@code httpRequest} when {@code _meta} carries no trace context.
     *
     * <p>The MCP {@code 2026-07-28} revision documents {@code _meta} as the canonical,
     * transport-independent carrier, but the HTTP header remains a valid carrier for clients
     * that have not yet adopted the {@code _meta} convention — so it is kept as a fallback
     * rather than dropped. Used by the Streamable HTTP transport ({@link
     * io.ikanos.engine.exposes.mcp.McpServerResource}), which has both carriers available.</p>
     */
    @Nonnull
    public static Context extractContext(JsonNode request, @Nullable Request httpRequest) {
        return extractContext(request, OtelRestletBridge.extractContext(httpRequest));
    }

    @Nonnull
    private static Context extractContext(JsonNode request, @Nonnull Context fallbackContext) {
        return OtelNullSafety.nonNull(TelemetryBootstrap.get().getOpenTelemetry()
                .getPropagators().getTextMapPropagator()
                .extract(fallbackContext, request, headerGetter()));
    }

    /**
     * Returns the MCP {@code _meta} text-map getter singleton typed as {@code @Nonnull}.
     */
    @Nonnull
    public static McpMetaTraceContextGetter headerGetter() {
        return OtelNullSafety.nonNull(McpMetaTraceContextGetter.INSTANCE);
    }
}
