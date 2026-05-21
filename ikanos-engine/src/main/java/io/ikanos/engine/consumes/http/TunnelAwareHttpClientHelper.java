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
package io.ikanos.engine.consumes.http;

import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelRouteTable;
import io.ikanos.engine.consumes.tunnel.TunnelTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.restlet.Client;

/**
 * Restlet {@link org.restlet.engine.connector.HttpClientHelper} subclass that installs a
 * Jetty request listener which, on every outgoing request, looks up the host in a {@link
 * TunnelRouteTable} and attaches a {@link TunnelTransport} when the host is tunneled.
 *
 * <p>The route table is passed via Restlet {@link org.restlet.Context} attributes under the
 * key {@link TunnelRouteTable#CONTEXT_ATTRIBUTE}; {@link io.ikanos.Capability} populates that
 * attribute before instantiating the underlying Restlet {@code Client}.
 *
 * <p>This class is referenced by class name (string) from
 * {@code org.restlet.Client(Context, List&lt;Protocol&gt;, String helperClass)}, which is how
 * Restlet swaps in custom helpers without touching the default helper lookup. The name MUST
 * stay stable.
 */
public class TunnelAwareHttpClientHelper
        extends org.restlet.engine.connector.HttpClientHelper {

    /**
     * Restlet calls this constructor reflectively when it resolves the helper class name.
     *
     * @param client the Restlet client
     */
    public TunnelAwareHttpClientHelper(Client client) {
        super(client);
    }

    @Override
    protected HttpClient createHttpClient() {
        HttpClient httpClient = super.createHttpClient();
        TunnelRouteTable routes = readRouteTable();
        if (routes == null || routes.isEmpty()) {
            return httpClient;
        }
        httpClient
                .getRequestListeners()
                .addQueuedListener(request -> applyTunnelTransport(request, routes));
        return httpClient;
    }

    /**
     * Package-private helper for unit tests. Reads the route table from the Restlet context
     * attribute populated by the engine bootstrap; returns {@code null} when the helper is
     * instantiated outside the engine (in which case no tunnels are configured anyway).
     */
    TunnelRouteTable readRouteTable() {
        if (getContext() == null) {
            return null;
        }
        Object attr = getContext().getAttributes().get(TunnelRouteTable.CONTEXT_ATTRIBUTE);
        if (attr instanceof TunnelRouteTable table) {
            return table;
        }
        return null;
    }

    /**
     * Package-private helper for unit tests. Attaches a {@link TunnelTransport} to the
     * outgoing {@code request} when the request's host matches a {@link Tunnel} registered in
     * {@code routes}; otherwise leaves the request unchanged (default Jetty TCP/IP transport).
     */
    static void applyTunnelTransport(Request request, TunnelRouteTable routes) {
        Tunnel tunnel = routes.lookup(request.getHost());
        if (tunnel == null) {
            return;
        }
        request.transport(new TunnelTransport(tunnel, request.getHost(), request.getPort()));
    }
}
