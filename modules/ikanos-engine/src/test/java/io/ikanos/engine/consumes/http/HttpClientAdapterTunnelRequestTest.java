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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ikanos.Capability;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;

/**
 * Sends a request through an HTTP adapter that declares a {@code tunnel}, end to end: the
 * request must reach the upstream through the tunnel's transport (#940).
 */
class HttpClientAdapterTunnelRequestTest {

    /** A tunnel that dials the local upstream and counts its connections. */
    static final class LoopbackTunnel implements Tunnel {

        private final int upstreamPort;
        final AtomicInteger connections = new AtomicInteger();

        LoopbackTunnel(int upstreamPort) {
            this.upstreamPort = upstreamPort;
        }

        @Override
        public String type() {
            return "ziti";
        }

        @Override
        public AsynchronousSocketChannel connect(String host, int port) throws IOException {
            connections.incrementAndGet();
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
            try {
                channel.connect(new InetSocketAddress("localhost", upstreamPort)).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            }
            return channel;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void requestShouldReachTheUpstreamThroughTheTunnel() throws Exception {
        int upstreamPort = findFreePort();
        Component upstream = createUpstream(upstreamPort);
        upstream.start();
        LoopbackTunnel tunnel = new LoopbackTunnel(upstreamPort);
        HttpClientAdapter adapter = new HttpClientAdapter(mockCapability(),
                tunneledSpec(upstreamPort), Map.of("ziti", tunnel));
        adapter.start();
        try {
            ConsumedResult result = adapter.prepare("list", new HashMap<>()).invoke();

            assertEquals(200, result.status(), "#940: the tunneled request should be sent");
            assertTrue(result.text().contains("through-the-tunnel"), result.text());
            assertTrue(tunnel.connections.get() > 0,
                    "#940: the request should go through the tunnel's transport");
        } finally {
            adapter.stop();
            upstream.stop();
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void largeBodiesShouldCrossTheTunnelInBothDirections() throws Exception {
        int upstreamPort = findFreePort();
        Component upstream = createUpstream(upstreamPort);
        upstream.start();
        LoopbackTunnel tunnel = new LoopbackTunnel(upstreamPort);
        HttpClientAdapter adapter = new HttpClientAdapter(mockCapability(),
                tunneledSpec(upstreamPort), Map.of("ziti", tunnel));
        adapter.start();
        try {
            String pad = "x".repeat(100_000);
            ConsumedResult result = adapter.prepare("echo", new HashMap<>(Map.of("pad", pad)))
                    .invoke();

            assertEquals(200, result.status());
            String text = result.text();
            assertTrue(text.startsWith("received=" + ("{\"pad\":\"" + pad + "\"}").length()),
                    "#940: the whole request body should reach the upstream: "
                            + text.substring(0, Math.min(80, text.length())));
            assertEquals("received=".length() + String.valueOf(pad.length() + 10).length()
                    + 1 + RESPONSE_PADDING, text.length(),
                    "#940: the whole response body should come back");
            assertTrue(tunnel.connections.get() > 0);
        } finally {
            adapter.stop();
            upstream.stop();
        }
    }

    private static final int RESPONSE_PADDING = 200_000;

    private static HttpClientSpec tunneledSpec(int port) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: private-api
                      baseUri: "http://localhost:%d"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: GET
                        echo:
                          path: "/echo"
                          operations:
                            echo:
                              method: POST
                              body:
                                type: json
                                data: '{"pad":"{{pad}}"}'
                """.formatted(VersionHelper.getSchemaVersion(), port);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        HttpClientSpec http = (HttpClientSpec) spec.getCapability().getConsumes().get(0);
        http.setTunnel(new ZitiTunnelConfigSpec("private-api", "/etc/ziti/app.json"));
        return http;
    }

    private static Component createUpstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/things", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"via\":\"through-the-tunnel\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                router.attach("/echo", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        int received;
                        try {
                            received = request.getEntity().getText().length();
                        } catch (IOException e) {
                            received = -1;
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("received=" + received + " "
                                + "y".repeat(RESPONSE_PADDING), MediaType.TEXT_PLAIN);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static Capability mockCapability() {
        Capability capability = mock(Capability.class);
        when(capability.getBindings()).thenReturn(Map.of());
        return capability;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
