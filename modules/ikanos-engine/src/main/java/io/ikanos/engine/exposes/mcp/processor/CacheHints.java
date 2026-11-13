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
package io.ikanos.engine.exposes.mcp.processor;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

/**
 * The cache hints of one MCP result (MCP 2026-07-28, SEP-2549): {@code ttlMs} and
 * {@code cacheScope}.
 *
 * <p>The adapter's values apply to every list and read; a resource's own values override them on
 * {@code resources/read} of that resource, field by field.</p>
 *
 * @param ttlMs freshness in milliseconds, zero or more
 * @param cacheScope {@code public} or {@code private}
 */
public record CacheHints(int ttlMs, String cacheScope) {

    /** Default TTL: the client fetches again every time. */
    public static final int DEFAULT_TTL_MS = 0;

    /** Default scope: the safe one, never shared across callers. */
    public static final String DEFAULT_SCOPE = "private";

    public CacheHints {
        // MCP 2026-07-28 (SEP-2549): ttlMs is an integer of zero or more, cacheScope is public
        // or private. The engine does not validate the schema at load time, so both are checked
        // here too, before an invalid value can reach the wire.
        if (ttlMs < 0) {
            throw new IllegalArgumentException("MCP ttlMs must be zero or more, got " + ttlMs);
        }
        Objects.requireNonNull(cacheScope, "cacheScope");
        if (!"public".equals(cacheScope) && !"private".equals(cacheScope)) {
            throw new IllegalArgumentException(
                    "MCP cacheScope must be public or private, got '" + cacheScope + "'");
        }
    }

    /** The adapter's hints, used for lists and for resources that set none. */
    public static CacheHints forServer(McpServerSpec server) {
        return of(server.getTtlMs(), server.getCacheScope(),
                new CacheHints(DEFAULT_TTL_MS, DEFAULT_SCOPE));
    }

    /** The hints for reads of {@code resource}: its own values, else the adapter's. */
    public static CacheHints forResource(McpServerSpec server, McpServerResourceSpec resource) {
        CacheHints serverHints = forServer(server);
        return resource == null ? serverHints
                : of(resource.getTtlMs(), resource.getCacheScope(), serverHints);
    }

    static CacheHints of(Integer ttlMs, String cacheScope, CacheHints fallback) {
        return new CacheHints(ttlMs != null ? ttlMs : fallback.ttlMs(),
                cacheScope != null ? cacheScope : fallback.cacheScope());
    }

    /** Write both hints on {@code result}, replacing any already there. */
    public void writeTo(ObjectNode result) {
        result.put("ttlMs", ttlMs);
        result.put("cacheScope", cacheScope);
    }
}
