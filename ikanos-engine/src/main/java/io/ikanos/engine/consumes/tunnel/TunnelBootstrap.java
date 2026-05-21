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
package io.ikanos.engine.consumes.tunnel;

import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.tunnel.TunnelConfigSpec;
import io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Bootstraps {@link Tunnel} implementations declared by a capability's {@code consumes:} list.
 *
 * <p>Discovery is by {@link ServiceLoader} — implementations live in separate Maven modules
 * (e.g. {@code ikanos-tunnel-ziti}) so the engine has zero compile-time dependency on any
 * specific overlay-network SDK.
 *
 * <p>Pooling is by {@code tunnel.type}: at most one started {@link Tunnel} instance per type
 * is active per {@link io.ikanos.Capability}, shared across all {@code ConsumesHttp} entries
 * that reference it. This matches the §6.5 design in the blueprint.
 */
public final class TunnelBootstrap {

    private TunnelBootstrap() {
        // utility
    }

    /**
     * Scans {@code clientSpecs} for HTTP clients with a {@code tunnel:} block, instantiates a
     * {@link Tunnel} per distinct {@code tunnel.type} via the JVM {@link ServiceLoader}, and
     * waits up to {@code readyTimeout} for each tunnel's {@link Tunnel#isReady()} to flip to
     * {@code true}.
     *
     * <p>If no tunnels are declared, returns an empty map immediately. If a declared
     * {@code tunnel.type} has no {@link ServiceLoader} provider on the classpath, an
     * {@link IllegalStateException} is thrown — the operator forgot to add the
     * {@code ikanos-tunnel-&lt;type&gt;} module to the deployment.
     *
     * @param clientSpecs the list of {@code consumes:} client specs from the capability spec
     * @param readyTimeout maximum time to wait per tunnel for {@code isReady()} to become
     *     {@code true}; if zero or negative, no readiness gate is applied
     * @return immutable map of {@code tunnel.type → started Tunnel instance}
     * @throws IllegalStateException if a required {@code tunnel.type} has no SPI provider, or
     *     if a tunnel does not become ready within {@code readyTimeout}
     */
    public static Map<String, Tunnel> discoverAndStart(
            List<ClientSpec> clientSpecs, Duration readyTimeout) {
        return discoverAndStart(clientSpecs, readyTimeout, Map.of());
    }

    /**
     * Variant that resolves Mustache-templated identity values via {@code bindings}. The
     * resolved identity is exposed to {@link Tunnel} implementations via a system property
     * named {@code ikanos.tunnel.<type>.identity} (set BEFORE {@link ServiceLoader}
     * instantiates the provider). This indirection keeps the {@link Tunnel} SPI minimal —
     * no-arg constructor required for {@link ServiceLoader} — while still letting providers
     * read per-deployment configuration.
     *
     * <p><b>Bindings shape.</b> {@code bindings} is the same nested map structure consumed
     * by {@link Resolver#resolveMustacheTemplate(String, Map)} elsewhere in the engine:
     * a top-level namespace map whose values are themselves maps of {@code key → value}.
     * For example, an identity template {@code "{{secrets.IDENT_PATH}}"} requires:
     * <pre>{@code
     * Map.of("secrets", Map.of("IDENT_PATH", "/etc/ziti/app.json"))
     * }</pre>
     * A flat map (e.g. {@code Map.of("IDENT_PATH", "/etc/ziti/app.json")}) will not satisfy
     * a {@code "{{secrets.IDENT_PATH}}"} template. To catch this early, {@link
     * #resolveIdentity} fails fast when the raw identity contains a Mustache expression but
     * resolution leaves any unresolved {@code {{...}}} marker in the output — see {@link
     * #resolveIdentity} for the exact condition.
     *
     * <p>The per-type identity system property is cleared immediately after {@link
     * #loadTunnel(String)} returns. The context is constructed and cached by the SPI
     * provider at that point, so the property is no longer needed; clearing it bounds the
     * JVM-global side-effect to the {@link ServiceLoader} call and avoids races when two
     * {@link io.ikanos.Capability} instances coexist in the same JVM (parallel unit tests
     * today, multi-capability deployments in a future server mode).
     *
     * <p>For Phase 2 only one identity per {@code tunnel.type} is supported; duplicate
     * {@code (type, identity)} pairs are accepted, but mixing two different identities for
     * the same {@code type} in a single capability throws {@link IllegalStateException}.
     *
     * @param clientSpecs the list of {@code consumes:} client specs
     * @param readyTimeout maximum time to wait per tunnel for readiness
     * @param bindings nested binding map for Mustache resolution of {@code tunnel.identity}
     *     (shape: {@code namespace → key → value}); may be {@code null} or empty when no
     *     Mustache substitution is needed
     * @return immutable map of {@code tunnel.type → Tunnel}
     */
    public static Map<String, Tunnel> discoverAndStart(
            List<ClientSpec> clientSpecs,
            Duration readyTimeout,
            Map<String, Object> bindings) {
        Map<String, Tunnel> tunnels = new HashMap<>();
        Map<String, String> identitiesByType = new HashMap<>();
        if (clientSpecs == null) {
            return Map.of();
        }
        for (ClientSpec clientSpec : clientSpecs) {
            if (!(clientSpec instanceof HttpClientSpec http)) {
                continue;
            }
            TunnelConfigSpec tunnelConfig = http.getTunnel();
            if (tunnelConfig == null) {
                continue;
            }
            String type = tunnelConfig.getType();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException(
                        "tunnel.type is required for ConsumesHttp tunnel block");
            }
            String identity = resolveIdentity(tunnelConfig, bindings);
            String previous = identitiesByType.put(type, identity);
            if (previous != null && !previous.equals(identity)) {
                throw new IllegalStateException(
                        "Multiple distinct identities configured for tunnel type '"
                                + type
                                + "'; Phase 2 supports a single identity per type.");
            }
            if (tunnels.containsKey(type)) {
                continue;
            }
            String identityPropertyKey = "ikanos.tunnel." + type + ".identity";
            boolean identityPropertySet = false;
            if (identity != null && !identity.isBlank()) {
                System.setProperty(identityPropertyKey, identity);
                identityPropertySet = true;
            }
            try {
                Tunnel tunnel = loadTunnel(type);
                waitReady(tunnel, readyTimeout);
                tunnels.put(type, tunnel);
            } finally {
                // Bound the JVM-global side-effect to the ServiceLoader call: by the time
                // loadTunnel returns, the SPI provider has constructed and cached its
                // context, so the property is no longer needed. Clearing it here prevents
                // a second capability in the same JVM from observing a stale value.
                if (identityPropertySet) {
                    System.clearProperty(identityPropertyKey);
                }
            }
        }
        return Map.copyOf(tunnels);
    }

    /**
     * Package-private for testing. Returns the resolved identity string, or {@code null} when
     * no identity is configured for this tunnel type.
     *
     * <p>If the raw identity contains a Mustache expression ({@code {{...}}}) and the
     * resolved value still contains an unresolved {@code {{...}}} marker, an
     * {@link IllegalStateException} is thrown. This catches the common mistake of passing a
     * flat map (e.g. {@code Map.of("IDENT", "...")}) when the template references a
     * namespaced key (e.g. {@code "{{secrets.IDENT}}"}) — without this guard, the unresolved
     * template would silently propagate to the SPI provider as a literal file path.
     */
    static String resolveIdentity(TunnelConfigSpec spec, Map<String, Object> bindings) {
        if (spec instanceof ZitiTunnelConfigSpec ziti) {
            String raw = ziti.getIdentity();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            if (bindings == null || bindings.isEmpty()) {
                return raw;
            }
            String resolved = Resolver.resolveMustacheTemplate(raw, bindings);
            // Fail fast when the template was non-empty but resolution produced no output —
            // this happens when the caller passes a flat binding map (or one missing the
            // referenced namespace), because Resolver substitutes missing variables with the
            // empty string. Without this guard, the SPI provider would silently start with
            // an empty identity path.
            boolean mustacheTemplate = raw.contains("{{");
            if (mustacheTemplate
                    && (resolved == null || resolved.isBlank() || resolved.contains("{{"))) {
                throw new IllegalStateException(
                        "tunnel.identity Mustache expression '" + raw
                                + "' could not be resolved against the provided bindings."
                                + " Expected nested map shape (namespace -> key -> value),"
                                + " e.g. {\"secrets\": {\"IDENT\": \"/etc/ziti/app.json\"}}.");
            }
            return resolved;
        }
        return null;
    }

    /**
     * Package-private for testing. Locates the first {@link ServiceLoader} provider whose
     * {@link Tunnel#type()} matches {@code type}, or throws.
     */
    static Tunnel loadTunnel(String type) {
        for (Tunnel candidate : ServiceLoader.load(Tunnel.class)) {
            if (type.equals(candidate.type())) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No Tunnel SPI provider found for type='"
                        + type
                        + "'. Add the ikanos-tunnel-"
                        + type
                        + " module (or compatible provider) to the classpath.");
    }

    /**
     * Package-private for testing. Polls {@link Tunnel#isReady()} every 100&nbsp;ms until it
     * returns {@code true} or {@code timeout} elapses. A non-positive timeout skips the wait.
     */
    static void waitReady(Tunnel tunnel, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!tunnel.isReady()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                        "Tunnel '"
                                + tunnel.type()
                                + "' did not become ready within "
                                + timeout);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for tunnel '" + tunnel.type() + "'", ie);
            }
        }
    }
}
