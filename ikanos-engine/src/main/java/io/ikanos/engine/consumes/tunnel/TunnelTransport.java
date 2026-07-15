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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MemoryEndPointPipe;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;

/**
 * Jetty {@link Transport} implementation that routes a single HTTP request through a
 * {@link Tunnel} (overlay network) instead of the public internet.
 *
 * <h2>Mechanism</h2>
 *
 * <ol>
 *   <li>Jetty calls {@link #connect(SocketAddress, Map)} once per outgoing request that has
 *       this transport attached via {@code Request.transport(TunnelTransport)}.</li>
 *   <li>We dial the tunnel and obtain an {@link AsynchronousSocketChannel} pointing at the
 *       private service.</li>
 *   <li>We create a {@link MemoryEndPointPipe} — an in-memory bidirectional pair of
 *       {@link EndPoint}s. We hand Jetty the local endpoint as if it were a regular socket
 *       endpoint; from Jetty's point of view, the connection is established.</li>
 *   <li>We start two byte pumps:
 *       <ul>
 *         <li>Jetty's remote endpoint → Ziti async channel (request bytes outbound)</li>
 *         <li>Ziti async channel → Jetty's remote endpoint (response bytes inbound)</li>
 *       </ul></li>
 * </ol>
 *
 * <p>This bridges Jetty's selector-based NIO I/O model and OpenZiti's
 * {@link java.nio.channels.AsynchronousChannelGroup}-based async I/O model. The cost is an
 * extra in-memory copy per chunk; for the API-aggregation workloads ikanos targets this is
 * negligible.
 *
 * <h2>Equality</h2>
 *
 * <p>Jetty interns {@code Origin}s (which include the {@code Transport}) so two transport
 * instances for the same host must compare equal to share a connection pool. We define
 * equality by the {@link Tunnel} reference and the {@code (host, port)} tuple, which gives
 * one pool per {@code (tunnel, host, port)} triple.
 */
public final class TunnelTransport implements Transport {

    /** Buffer size for the byte pumps in both directions. 8&nbsp;KiB matches Jetty defaults. */
    private static final int PUMP_BUFFER_BYTES = 8 * 1024;

    private final Tunnel tunnel;
    private final String host;
    private final int port;
    private final SocketAddress syntheticAddress;

    /**
     * @param tunnel the tunnel to dial through; non-null
     * @param host the target hostname (passed verbatim to {@link Tunnel#connect(String, int)});
     *     non-null
     * @param port the target port; positive
     */
    public TunnelTransport(Tunnel tunnel, String host, int port) {
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0) {
            throw new IllegalArgumentException("port must be positive: " + port);
        }
        this.port = port;
        this.syntheticAddress = new TunnelSocketAddress(tunnel.type(), host, port);
    }

    @Override
    public boolean requiresDomainNameResolution() {
        // The host is logical (e.g. "crm.internal"); system DNS would fail. We resolve it
        // by routing through the tunnel.
        return false;
    }

    @Override
    public SocketAddress getSocketAddress() {
        return syntheticAddress;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void connect(SocketAddress socketAddress, Map<String, Object> context) {
        Promise<Connection> promise =
                (Promise<Connection>) context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
        try {
            // 1) Dial through the tunnel.
            AsynchronousSocketChannel zitiChannel = tunnel.connect(host, port);

            // 2) Get the Jetty-side scheduler and create the in-memory pipe.
            ClientConnector clientConnector =
                    (ClientConnector) context.get(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY);
            MemoryEndPointPipe pipe = new MemoryEndPointPipe(
                    clientConnector.getScheduler(), r -> r.run(), syntheticAddress);

            EndPoint localEndPoint = pipe.getLocalEndPoint();
            EndPoint remoteEndPoint = pipe.getRemoteEndPoint();
            localEndPoint.setIdleTimeout(clientConnector.getIdleTimeout().toMillis());

            // 3) Build the Jetty Connection on the local endpoint.
            Transport outerTransport = (Transport) context.get(Transport.class.getName());
            Connection connection = outerTransport.newConnection(localEndPoint, context);
            localEndPoint.setConnection(connection);

            // 4) Start the byte pumps between the remote endpoint and the Ziti channel.
            startPumps(remoteEndPoint, zitiChannel);

            // 5) Open the endpoint / connection, then succeed the promise.
            localEndPoint.onOpen();
            connection.onOpen();
            promise.succeeded(connection);
        } catch (Throwable x) {
            promise.failed(x);
        }
    }

    /**
     * Wires the in-memory pipe to the async Ziti channel via two iterating callbacks.
     *
     * <p>Package-private for testing.
     */
    static void startPumps(EndPoint remoteEndPoint, AsynchronousSocketChannel zitiChannel) {
        // Jetty's remote endpoint → Ziti channel (request bytes).
        EndPointToChannelPump outbound = new EndPointToChannelPump(remoteEndPoint, zitiChannel);
        remoteEndPoint.fillInterested(Callback.from(outbound::iterate));

        // Ziti channel → Jetty's remote endpoint (response bytes).
        new ChannelToEndPointPump(zitiChannel, remoteEndPoint).start();
    }

    /**
     * Hash combines the {@link System#identityHashCode(Object)} of the {@link Tunnel}
     * instance with the {@code (host, port)} tuple.
     *
     * <p><b>Tunnel equality is reference identity ({@code ==}), not logical equality.</b>
     * This is intentional and matches the {@code (tunnel, host, port)} pool-key contract
     * documented at the class level. It is safe because {@link TunnelBootstrap} guarantees
     * a single started {@link Tunnel} instance per {@code tunnel.type} per {@link
     * io.ikanos.Capability}, shared by all {@link
     * io.ikanos.engine.consumes.http.HttpClientAdapter} instances within that capability.
     *
     * <p>If two {@link TunnelTransport} instances are ever constructed for the same
     * {@code (host, port)} with two different {@link Tunnel} instances of the same type
     * (for example in a test, or in a hypothetical future multi-identity scenario), they
     * will land in <i>different</i> Jetty connection pools — by design, since the two
     * tunnels are physically distinct.
     */
    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(tunnel), host, port);
    }

    /**
     * Equality compares the {@link Tunnel} by <b>reference identity</b> ({@code ==}), not by
     * {@link Object#equals(Object)} or by {@link Tunnel#type()}. See {@link #hashCode()} for
     * the rationale and the {@link TunnelBootstrap} single-instance-per-type guarantee that
     * makes this correct.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TunnelTransport that)) {
            return false;
        }
        return this.tunnel == that.tunnel
                && this.port == that.port
                && this.host.equals(that.host);
    }

    @Override
    public String toString() {
        return "TunnelTransport[" + tunnel.type() + "://" + host + ":" + port + "]";
    }

    /** Reads from the Jetty endpoint and writes to the Ziti channel. */
    private static final class EndPointToChannelPump extends IteratingCallback {

        private final EndPoint endPoint;
        private final AsynchronousSocketChannel channel;
        private final ByteBuffer buffer = BufferUtil.allocate(PUMP_BUFFER_BYTES);

        EndPointToChannelPump(EndPoint endPoint, AsynchronousSocketChannel channel) {
            this.endPoint = endPoint;
            this.channel = channel;
        }

        @Override
        protected Action process() throws Throwable {
            BufferUtil.clearToFill(buffer);
            int filled = endPoint.fill(buffer);
            BufferUtil.flipToFlush(buffer, 0);
            if (filled < 0) {
                // EOS on the Jetty side: the local endpoint has signalled that the application
                // has finished writing the request body. The pump finishes successfully, which
                // dispatches to onCompleteSuccess() and closes the tunnel write side
                // (channel.close()). We deliberately do NOT close the EndPoint itself here:
                // (1) Jetty's MemoryEndPointPipe drives the EndPoint lifecycle and closes the
                //     local side on its own EOS detection.
                // (2) The opposite direction (ChannelToEndPointPump) is still expected to
                //     deliver the response on the same EndPoint; closing it now would abort
                //     the response. ChannelToEndPointPump.completed(result < 0) calls
                //     endPoint.shutdownOutput()/close() once the tunnel side reports EOS,
                //     which is the correct moment to tear down the EndPoint.
                return Action.SUCCEEDED;
            }
            if (filled == 0) {
                endPoint.fillInterested(Callback.from(this::iterate));
                return Action.IDLE;
            }
            channel.write(buffer, this, new CompletionHandler<Integer, EndPointToChannelPump>() {
                @Override
                public void completed(Integer result, EndPointToChannelPump cb) {
                    cb.succeeded();
                }

                @Override
                public void failed(Throwable cause, EndPointToChannelPump cb) {
                    cb.failed(cause);
                }
            });
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess() {
            closeQuietly(channel);
        }

        @Override
        protected void onCompleteFailure(Throwable cause) {
            closeQuietly(channel);
            endPoint.close(cause);
        }
    }

    /** Reads from the Ziti channel and writes to the Jetty endpoint. */
    private static final class ChannelToEndPointPump
            implements CompletionHandler<Integer, ByteBuffer> {

        private final AsynchronousSocketChannel channel;
        private final EndPoint endPoint;

        ChannelToEndPointPump(AsynchronousSocketChannel channel, EndPoint endPoint) {
            this.channel = channel;
            this.endPoint = endPoint;
        }

        void start() {
            ByteBuffer buf = ByteBuffer.allocate(PUMP_BUFFER_BYTES);
            channel.read(buf, buf, this);
        }

        @Override
        public void completed(Integer result, ByteBuffer buffer) {
            if (result < 0) {
                endPoint.shutdownOutput();
                closeQuietly(channel);
                return;
            }
            buffer.flip();
            endPoint.write(
                    Callback.from(
                            () -> {
                                ByteBuffer next = ByteBuffer.allocate(PUMP_BUFFER_BYTES);
                                channel.read(next, next, this);
                            },
                            cause -> {
                                closeQuietly(channel);
                                endPoint.close(cause);
                            }),
                    buffer);
        }

        @Override
        public void failed(Throwable cause, ByteBuffer buffer) {
            closeQuietly(channel);
            endPoint.close(cause);
        }
    }

    private static void closeQuietly(AsynchronousSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Synthetic {@link SocketAddress} used as Jetty's {@code remoteSocketAddress} for tunnelled
     * connections. Its only purpose is to be unique per {@code (type, host, port)} triple so
     * Jetty's connection pools partition correctly; it is never used to actually connect
     * anywhere.
     */
    private static final class TunnelSocketAddress extends SocketAddress {

        private static final long serialVersionUID = 1L;

        private final String type;
        private final String host;
        private final int port;

        TunnelSocketAddress(String type, String host, int port) {
            this.type = type;
            this.host = host;
            this.port = port;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, host, port);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TunnelSocketAddress that)) {
                return false;
            }
            return port == that.port
                    && type.equals(that.type)
                    && host.equals(that.host);
        }

        @Override
        public String toString() {
            return type + "://" + host + ":" + port;
        }
    }
}
