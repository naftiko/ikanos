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

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;

/**
 * SPI for reverse-tunnel transports that route consumed HTTP traffic through an overlay
 * network instead of the public internet.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} at bootstrap, matched
 * to a {@code ConsumesHttp.tunnel} block by {@link #type()}, and pooled per
 * {@code (type, identity)} so that two {@code ConsumesHttp} entries sharing the same identity
 * also share one underlying context.
 *
 * <h2>Design notes</h2>
 *
 * <p>The dial returns an {@link AsynchronousSocketChannel} rather than a plain
 * {@link java.net.Socket} because the only viable JVM-side OpenZiti integration produces
 * async channels; the engine then bridges that channel to Jetty's selector-based I/O via a
 * {@code MemoryEndPointPipe} pump. See blueprint §6.3 (revised by the §6.4 ADR after Phase 2
 * implementation surfaced that {@code SocketChannel} bridging was not viable with the
 * available OpenZiti SDK surface).
 *
 * <h2>Routing</h2>
 *
 * <p>Host-to-tunnel routing happens via the {@link TunnelRouteTable} in the engine: the
 * adapter inspects every outgoing request's {@code Host} header and, if a registered tunnel
 * host matches, attaches a {@code TunnelTransport} to the Jetty request. The tunnel itself
 * does not participate in routing decisions; it is only asked to dial.
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations MUST be safe for concurrent use by multiple HTTP client threads. A single
 * {@code Tunnel} instance is shared across all {@code ConsumesHttp} entries that use the same
 * identity.
 */
public interface Tunnel extends AutoCloseable {

    /**
     * Discriminator matching the {@code tunnel.type} value in {@code ConsumesHttp.tunnel}.
     *
     * <p>The first {@code Tunnel} implementation whose {@code type()} matches the configured
     * type is selected by the engine. Values are lowercase, kebab-case identifiers
     * (e.g. {@code "ziti"}).
     *
     * @return the tunnel type identifier; never {@code null}
     */
    String type();

    /**
     * Dial through the overlay and return a connected {@link AsynchronousSocketChannel}
     * bound to the configured private service.
     *
     * <p>Implementations MUST NOT call {@link java.net.InetAddress#getByName(String)} or any
     * other system DNS resolver. The {@code host} parameter is the logical hostname declared
     * in {@code ConsumesHttp.baseUri} (e.g. {@code "crm.internal"}) and is, by design,
     * unresolvable from the public internet.
     *
     * <p>The returned channel is wrapped in application-layer TLS by Jetty when the consumed
     * {@code baseUri} uses HTTPS, providing end-to-end encryption on top of the overlay's
     * transport security.
     *
     * @param host the target hostname from {@code baseUri}; non-null
     * @param port the target port from {@code baseUri}; positive
     * @return a connected async channel routed through the tunnel overlay
     * @throws IOException if the dial fails (tunnel down, service unauthorized, …)
     */
    AsynchronousSocketChannel connect(String host, int port) throws IOException;

    /**
     * Readiness signal surfaced by the control-port {@code /health/ready} endpoint.
     *
     * <p>Returns {@code true} when the tunnel overlay is connected and the configured service
     * is reachable. The engine blocks bootstrap for a configurable timeout (default 30s)
     * waiting for this to flip to {@code true} on every required tunnel.
     *
     * @return {@code true} if the tunnel is active and dials are expected to succeed
     */
    boolean isReady();
}
