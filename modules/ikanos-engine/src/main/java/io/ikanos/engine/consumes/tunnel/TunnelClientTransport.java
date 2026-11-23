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

import io.netty.resolver.NoopAddressResolverGroup;
import java.util.Objects;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.engine.netty.NettyClientTransport;

/**
 * Routes the requests of a Restlet client whose host is registered in a {@link TunnelRouteTable}
 * through that host's {@link Tunnel}, and the others over TCP.
 *
 * <p>It is Restlet's {@link NettyClientTransport} connector SPI: set on a client's context under
 * {@link NettyClientTransport#ATTRIBUTE} ({@link #install}), the Netty connector asks it for a
 * route to each origin. A tunneled origin gets a {@link TunnelSocketChannel}, over which the
 * connector runs TLS, HTTP/1.1 and HTTP/2 as over TCP. The host name is never resolved with the
 * system DNS: the tunnel dials it as given.</p>
 *
 * <p>This replaces the Jetty transport Ikanos attached to each request on Restlet 3.0.0-m1, and
 * applies to every consumed adapter that borrows a Restlet {@link Client}.</p>
 */
public final class TunnelClientTransport implements NettyClientTransport {

    private final TunnelRouteTable routes;

    /**
     * @param routes the hosts to tunnel, and their tunnels
     */
    public TunnelClientTransport(TunnelRouteTable routes) {
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    /** @return the route table */
    public TunnelRouteTable routes() {
        return routes;
    }

    @Override
    public Route route(String host, int port) {
        Tunnel tunnel = routes.lookup(host);
        if (tunnel == null) {
            return null;
        }
        return new Route(() -> new TunnelSocketChannel(tunnel, host, port),
                NoopAddressResolverGroup.INSTANCE);
    }

    /**
     * Sets a transport routing {@code routes} on a client context, before the client starts.
     *
     * @param context the client's context
     * @param routes the hosts to tunnel
     * @return the transport
     */
    public static TunnelClientTransport install(Context context, TunnelRouteTable routes) {
        TunnelClientTransport transport = new TunnelClientTransport(routes);
        context.getAttributes().put(NettyClientTransport.ATTRIBUTE, transport);
        context.getAttributes().put(TunnelRouteTable.CONTEXT_ATTRIBUTE, routes);
        return transport;
    }
}
