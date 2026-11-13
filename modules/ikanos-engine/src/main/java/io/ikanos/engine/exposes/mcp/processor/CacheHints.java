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
import io.ikanos.engine.consumes.Freshness;
import io.ikanos.engine.exposes.CacheScopes;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;

/**
 * The cache hints of one MCP result (MCP 2026-07-28, SEP-2549): {@code ttlMs} and
 * {@code cacheScope}.
 *
 * <p>The adapter's values apply to every list and read; a resource's own values override them on
 * {@code resources/read} of that resource, field by field. A {@code cacheScope} of {@code auto} is
 * then resolved by {@link CacheScopes}: {@code private} when the adapter authenticates its callers
 * or identifies them through {@code trust}, {@code public} otherwise. Ikanos lists and resource reads do not otherwise vary by caller:
 * resource inputs come from the URI, and upstream credentials from the operator's binds.</p>
 *
 * @param ttlMs freshness in milliseconds, zero or more
 * @param cacheScope {@code public} or {@code private}
 */
public record CacheHints(int ttlMs, String cacheScope) {

    /** Default TTL: the client fetches again every time. */
    public static final int DEFAULT_TTL_MS = 0;

    /** Default scope: the safe one, never shared across callers. */
    public static final String DEFAULT_SCOPE = CacheScopes.PRIVATE;

    public CacheHints {
        // MCP 2026-07-28 (SEP-2549): ttlMs is an integer of zero or more, cacheScope is public
        // or private. The engine does not validate the schema at load time, so both are checked
        // here too, before an invalid value can reach the wire.
        if (ttlMs < 0) {
            throw new IllegalArgumentException("MCP ttlMs must be zero or more, got " + ttlMs);
        }
        Objects.requireNonNull(cacheScope, "cacheScope");
        // auto is resolved by CacheScopes before a CacheHints is built, so it is refused here too
        if (!CacheScopes.PUBLIC.equals(cacheScope) && !CacheScopes.PRIVATE.equals(cacheScope)) {
            throw new IllegalArgumentException(
                    "MCP cacheScope must be public or private (auto is resolved first), got '"
                            + cacheScope + "'");
        }
    }

    /** The adapter's hints, used for lists and for resources that set none. */
    public static CacheHints forServer(McpServerSpec server) {
        return forResource(server, null);
    }

    /** The hints for reads of {@code resource}: its own values, else the adapter's. */
    public static CacheHints forResource(McpServerSpec server, McpServerResourceSpec resource) {
        Integer ttlMs = resource != null && resource.getTtlMs() != null
                ? resource.getTtlMs() : server.getTtlMs();
        return new CacheHints(ttlMs != null ? ttlMs : DEFAULT_TTL_MS,
                scopeDecision(server, resource).scope());
    }

    /**
     * The hints for one read of {@code resource}, given what its upstream said. When the resource
     * opts in with {@code ttlFromUpstream}, the upstream's freshness replaces the TTL and an
     * upstream {@code private} answer makes the scope private; otherwise the upstream is ignored.
     */
    public static CacheHints forRead(McpServerSpec server, McpServerResourceSpec resource,
            Freshness upstream) {
        CacheHints hints = forResource(server, resource);
        if (resource == null || !resource.isTtlFromUpstream() || upstream == null) {
            return hints;
        }
        int ttlMs = upstream.ttlMs() != null
                ? (int) Math.min(upstream.ttlMs(), Integer.MAX_VALUE) : hints.ttlMs();
        String scope = upstream.privateOnly() ? CacheScopes.PRIVATE : hints.cacheScope();
        return new CacheHints(ttlMs, scope);
    }

    /**
     * The scope for reads of {@code resource} (or for lists when it is {@code null}), and why.
     */
    public static CacheScopes.Decision scopeDecision(McpServerSpec server,
            McpServerResourceSpec resource) {
        String declared = resource != null && resource.getCacheScope() != null
                ? resource.getCacheScope() : server.getCacheScope();
        return CacheScopes.resolve(declared != null ? declared : DEFAULT_SCOPE,
                callerDependence(server));
    }

    /** Why results of this adapter can depend on the caller, or {@code null}. */
    static String callerDependence(McpServerSpec server) {
        if (server.getAuthentication() != null) {
            return "the adapter authenticates its callers, so a shared cache must not reuse a"
                    + " result for another caller";
        }
        McpTrustSpec trust = server.getTrust();
        if (trust != null && !McpTrustSpec.MODE_NONE.equals(trust.getMode())) {
            return "callers are identified (trust: " + trust.getMode() + "), so they are"
                    + " authenticated upstream of the adapter, and a shared cache must not reuse"
                    + " a result for another caller";
        }
        return null;
    }

    /** Write both hints on {@code result}, replacing any already there. */
    public void writeTo(ObjectNode result) {
        result.put("ttlMs", ttlMs);
        result.put("cacheScope", cacheScope);
    }
}
