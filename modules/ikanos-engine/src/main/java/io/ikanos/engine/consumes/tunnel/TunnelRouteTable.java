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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping consumed-API hosts to the {@link Tunnel} that dials them.
 *
 * <p>A single {@code TunnelRouteTable} is built once during {@link io.ikanos.Capability}
 * bootstrap and passed to {@link io.ikanos.engine.consumes.http.HttpClientAdapter}, which
 * stores it in the underlying Restlet/Jetty client. At request time, a Jetty request listener
 * (installed by {@code TunnelAwareHttpClientHelper}) consults this table on every outgoing
 * request and, when the host matches a registered entry, attaches a tunnel-routed
 * {@link org.eclipse.jetty.io.Transport} to the request.
 *
 * <p>Lookups are case-insensitive on the host string.
 *
 * <h2>Thread-safety</h2>
 *
 * Instances are populated synchronously during bootstrap and may be queried concurrently
 * afterwards. The underlying map is a {@link ConcurrentHashMap}.
 */
public final class TunnelRouteTable {

    /**
     * Well-known key under which the engine stores the active table in the Restlet
     * {@link org.restlet.Context} attributes so the helper subclass can read it.
     */
    public static final String CONTEXT_ATTRIBUTE = "io.ikanos.engine.tunnel.routes";

    private final Map<String, Tunnel> byHost = new ConcurrentHashMap<>();

    /**
     * Register a tunnel for the given host. Existing mappings for {@code host} are replaced.
     *
     * @param host the hostname from a {@code ConsumesHttp.baseUri} (case-insensitive); non-null
     * @param tunnel the tunnel that dials this host; non-null
     */
    public void register(String host, Tunnel tunnel) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(tunnel, "tunnel");
        byHost.put(normalize(host), tunnel);
    }

    /**
     * @param host hostname to look up; may be null
     * @return the tunnel registered for {@code host}, or {@code null} if the host is not
     *         tunneled and traffic should go via the default transport
     */
    public Tunnel lookup(String host) {
        if (host == null) {
            return null;
        }
        return byHost.get(normalize(host));
    }

    /**
     * @return {@code true} when at least one tunnel is registered
     */
    public boolean isEmpty() {
        return byHost.isEmpty();
    }

    /**
     * @return the number of registered host → tunnel mappings
     */
    public int size() {
        return byHost.size();
    }

    private static String normalize(String host) {
        return host.toLowerCase(Locale.ROOT);
    }
}
