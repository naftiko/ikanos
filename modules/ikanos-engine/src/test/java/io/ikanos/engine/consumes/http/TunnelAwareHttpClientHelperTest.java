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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelRouteTable;
import io.ikanos.engine.consumes.tunnel.TunnelTransport;
import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Client;
import org.restlet.Context;

class TunnelAwareHttpClientHelperTest {

    private HttpClient jettyClient;

    @BeforeEach
    void setUp() throws Exception {
        // We need a Jetty HttpClient just to build Requests for inspection. Don't start it.
        jettyClient = new HttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (jettyClient != null && jettyClient.isStarted()) {
            jettyClient.stop();
        }
    }

    @Test
    void readRouteTableShouldReturnNullWhenContextMissing() {
        Client restletClient = new Client((Context) null, java.util.List.of());
        TunnelAwareHttpClientHelper helper = new TunnelAwareHttpClientHelper(restletClient);
        assertNull(helper.readRouteTable());
    }

    @Test
    void readRouteTableShouldReturnTableWhenContextAttributeSet() {
        Context context = new Context();
        TunnelRouteTable table = new TunnelRouteTable();
        context.getAttributes().put(TunnelRouteTable.CONTEXT_ATTRIBUTE, table);
        Client restletClient = new Client(context, java.util.List.of());

        TunnelAwareHttpClientHelper helper = new TunnelAwareHttpClientHelper(restletClient);
        assertSame(table, helper.readRouteTable());
    }

    @Test
    void readRouteTableShouldReturnNullWhenAttributeWrongType() {
        Context context = new Context();
        context.getAttributes().put(TunnelRouteTable.CONTEXT_ATTRIBUTE, "not a table");
        Client restletClient = new Client(context, java.util.List.of());

        TunnelAwareHttpClientHelper helper = new TunnelAwareHttpClientHelper(restletClient);
        assertNull(helper.readRouteTable());
    }

    @Test
    void applyTunnelTransportShouldAttachTransportWhenHostRegistered() {
        TunnelRouteTable table = new TunnelRouteTable();
        Tunnel tunnel = new FakeTunnel();
        table.register("crm.internal", tunnel);

        Request request = jettyClient.newRequest("http://crm.internal:8080/users");
        TunnelAwareHttpClientHelper.applyTunnelTransport(request, table);

        assertNotNull(request.getTransport());
        // Attached transport must be a TunnelTransport routed through the tunnel above.
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                TunnelTransport.class, request.getTransport());
    }

    @Test
    void applyTunnelTransportShouldLeaveRequestUnchangedWhenHostNotRegistered() {
        TunnelRouteTable table = new TunnelRouteTable();
        table.register("crm.internal", new FakeTunnel());

        Request request = jettyClient.newRequest("http://public.example.com:80/items");
        // Capture pre-state transport (default = null, falls back to ClientConnector path).
        Object preTransport = request.getTransport();
        TunnelAwareHttpClientHelper.applyTunnelTransport(request, table);

        // Post-state should be identical — no tunnel for this host.
        org.junit.jupiter.api.Assertions.assertEquals(preTransport, request.getTransport());
    }

    private static final class FakeTunnel implements Tunnel {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public AsynchronousSocketChannel connect(String host, int port) throws IOException {
            throw new IOException("fake tunnel — not dialable in unit tests");
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
