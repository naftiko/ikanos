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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.restlet.Application;
import org.restlet.Client;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;

/**
 * Runs Restlet's Netty HTTP client over a {@link TunnelSocketChannel}, through a tunnel that dials
 * a local upstream: the requests target a host that no DNS resolves, so they only arrive through
 * the tunnel.
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TunnelSocketChannelTest {

    /** The host the requests target: only the tunnel knows how to reach it. */
    private static final String TUNNELED_HOST = "private-api.internal";

    /** More than one read of the channel (16 KiB) and more than one socket write. */
    private static final int LARGE_BODY_SIZE = 1_000_000;

    private int upstreamPort;
    private Component upstream;
    private Client client;

    @BeforeEach
    void startUpstream() throws Exception {
        upstreamPort = findFreePort();
        upstream = createUpstream(upstreamPort);
        upstream.start();
    }

    @AfterEach
    void stopClientAndUpstream() throws Exception {
        if (client != null) {
            client.stop();
        }
        upstream.stop();
    }

    @Test
    void getShouldReachTheUpstreamWhenItsHostIsTunneled() throws Exception {
        FakeTunnel tunnel = new FakeTunnel(this::dialUpstream);
        client = startClient(tunnel, 10_000);

        Response response = client.handle(new Request(Method.GET, uri("/hello")));

        assertEquals(Status.SUCCESS_OK.getCode(), response.getStatus().getCode());
        assertEquals("hello through the tunnel", response.getEntity().getText());
        assertEquals(1, tunnel.dials.get());
    }

    @Test
    void bodiesShouldCrossTheTunnelWhenLargerThanOneReadAndOneWrite() throws Exception {
        FakeTunnel tunnel = new FakeTunnel(this::dialUpstream);
        client = startClient(tunnel, 10_000);
        String body = "x".repeat(LARGE_BODY_SIZE - 1) + "!";
        Request request = new Request(Method.POST, uri("/echo"));
        request.setEntity(body, MediaType.TEXT_PLAIN);

        Response response = client.handle(request);

        assertEquals(Status.SUCCESS_OK.getCode(), response.getStatus().getCode());
        String echoed = response.getEntity().getText();
        assertEquals(body.length(), echoed.length(), "the whole body should go and come back");
        assertEquals(body, echoed);
    }

    @Test
    void requestShouldFailWithAConnectionErrorWhenTheDialFails() throws Exception {
        FakeTunnel tunnel = new FakeTunnel(() -> {
            throw new IOException("service not authorized");
        });
        client = startClient(tunnel, 10_000);

        Response response = client.handle(new Request(Method.GET, uri("/hello")));

        assertEquals(Status.CONNECTOR_ERROR_CONNECTION.getCode(),
                response.getStatus().getCode());
        Throwable failure = response.getStatus().getThrowable();
        assertNotNull(failure, "the status should carry the dial failure");
        assertTrue(failure.getMessage().contains("service not authorized"),
                failure.getMessage());
    }

    @Test
    void requestShouldFailAtOnceWhenTheDialThrowsAnError() throws Exception {
        FakeTunnel tunnel = new FakeTunnel(() -> {
            throw new NoClassDefFoundError("io/example/MissingDialer");
        });
        client = startClient(tunnel, 20_000);

        long start = System.nanoTime();
        Response response = client.handle(new Request(Method.GET, uri("/hello")));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.CONNECTOR_ERROR_CONNECTION.getCode(),
                response.getStatus().getCode());
        assertTrue(elapsedMillis < 10_000,
                "an Error should fail the connect, not leave it to the 20 s timeout: "
                        + elapsedMillis + " ms");
        Throwable failure = response.getStatus().getThrowable();
        assertNotNull(failure);
        assertInstanceOf(NoClassDefFoundError.class, failure.getCause());
    }

    @Test
    void requestShouldFailWithAConnectionErrorWhenTheDialIsSlowerThanTheConnectTimeout()
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTunnel tunnel = new FakeTunnel(() -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return dialUpstream();
        });
        client = startClient(tunnel, 300);
        try {
            long start = System.nanoTime();
            Response response = client.handle(new Request(Method.GET, uri("/hello")));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(Status.CONNECTOR_ERROR_CONNECTION.getCode(),
                    response.getStatus().getCode());
            assertTrue(elapsedMillis < 10_000,
                    "the connect should time out, not wait for the dial: " + elapsedMillis
                            + " ms");
        } finally {
            release.countDown();
        }
    }

    @Test
    void secondRequestShouldReuseTheTunneledConnectionWhenSentToTheSameOrigin()
            throws Exception {
        FakeTunnel tunnel = new FakeTunnel(this::dialUpstream);
        client = startClient(tunnel, 10_000);

        Response first = client.handle(new Request(Method.GET, uri("/hello")));
        assertEquals("hello through the tunnel", first.getEntity().getText());
        Response second = client.handle(new Request(Method.GET, uri("/hello")));
        assertEquals("hello through the tunnel", second.getEntity().getText());

        assertEquals(1, tunnel.dials.get(),
                "the second request should reuse the kept-alive connection, not dial again");
    }

    /** A tunnel whose dial is given by the test, and that counts its dials. */
    private static final class FakeTunnel implements Tunnel {

        private final Dialer dialer;
        final AtomicInteger dials = new AtomicInteger();

        FakeTunnel(Dialer dialer) {
            this.dialer = dialer;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public AsynchronousSocketChannel connect(String host, int port) throws IOException {
            dials.incrementAndGet();
            return dialer.dial();
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

    /** Dials a connection for {@link FakeTunnel}. */
    @FunctionalInterface
    private interface Dialer {
        AsynchronousSocketChannel dial() throws IOException;
    }

    /** Opens a loopback connection to the upstream. */
    private AsynchronousSocketChannel dialUpstream() throws IOException {
        AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
        try {
            channel.connect(new InetSocketAddress("localhost", upstreamPort)).get();
            return channel;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.close();
            throw new IOException(e);
        } catch (ExecutionException e) {
            channel.close();
            throw new IOException(e.getCause());
        }
    }

    /** Starts a client that sends the requests to {@link #TUNNELED_HOST} through the tunnel. */
    private static Client startClient(Tunnel tunnel, long connectTimeoutMillis)
            throws Exception {
        Context context = new Context();
        context.getParameters().add("connectTimeout", Long.toString(connectTimeoutMillis));
        TunnelRouteTable routes = new TunnelRouteTable();
        routes.register(TUNNELED_HOST, tunnel);
        TunnelClientTransport.install(context, routes);
        Client result = new Client(context, Protocol.HTTP);
        result.start();
        return result;
    }

    private static String uri(String path) {
        return "http://" + TUNNELED_HOST + path;
    }

    private static Component createUpstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/hello", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("hello through the tunnel", MediaType.TEXT_PLAIN);
                    }
                });
                router.attach("/echo", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            response.setStatus(Status.SUCCESS_OK);
                            response.setEntity(request.getEntity().getText(),
                                    MediaType.TEXT_PLAIN);
                        } catch (IOException e) {
                            response.setStatus(Status.SERVER_ERROR_INTERNAL, e);
                        }
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
