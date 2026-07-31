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
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Extracts W3C trace context ({@code traceparent}, {@code tracestate}, {@code baggage}) from
 * the {@code params._meta} object of an inbound MCP JSON-RPC request.
 *
 * <p>The MCP {@code 2026-07-28} revision documents these three keys as reserved, non-prefixed
 * {@code _meta} entries carrying OpenTelemetry trace context. Because MCP is transport-agnostic
 * (stdio has no HTTP headers to carry a {@code traceparent}), the message-level {@code _meta}
 * object — not an HTTP header — is the canonical, transport-independent carrier for this
 * propagation convention. This getter is intended to be used from the shared
 * {@code ProtocolDispatcher} so both the Streamable HTTP and stdio transports extract trace
 * context the same way.</p>
 *
 * <p>Mirrors {@link RestletHeaderGetter}, which remains the HTTP-header-based carrier used by
 * the REST and Skill adapters.</p>
 */
public class McpMetaTraceContextGetter implements TextMapGetter<JsonNode> {

    public static final McpMetaTraceContextGetter INSTANCE = new McpMetaTraceContextGetter();

    private static final String PARAMS = "params";
    private static final String META = "_meta";

    @Override
    public Iterable<String> keys(@Nullable JsonNode request) {
        JsonNode meta = metaNode(request);
        if (meta == null || !meta.isObject()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        meta.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Override
    @Nullable
    public String get(@Nullable JsonNode request, @Nonnull String key) {
        JsonNode meta = metaNode(request);
        if (meta == null || key == null) {
            return null;
        }
        JsonNode value = meta.get(key);
        return (value == null || value.isMissingNode() || value.isNull()) ? null : value.asText();
    }

    /**
     * Resolves the {@code params._meta} node of the request, or {@code null} when absent.
     */
    @Nullable
    private static JsonNode metaNode(@Nullable JsonNode request) {
        if (request == null) {
            return null;
        }
        JsonNode meta = request.path(PARAMS).path(META);
        return meta.isMissingNode() ? null : meta;
    }
}
