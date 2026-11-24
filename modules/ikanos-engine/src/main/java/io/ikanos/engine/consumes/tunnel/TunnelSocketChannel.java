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

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A Netty channel over a connection that a {@link Tunnel} dials, so Restlet's Netty HTTP client
 * runs its usual pipeline (TLS, HTTP/1.1, HTTP/2) over the tunnel instead of TCP.
 *
 * <p>Restlet routes a request to it through {@link TunnelClientTransport}. The channel ignores the
 * address it is asked to connect to: it dials the host and port it was created for with
 * {@link Tunnel#connect(String, int)}, which may block, so the dial runs on a virtual thread and
 * its outcome is reported on the event loop.</p>
 *
 * <p>It follows the semantics of Netty's own socket channels: one read at a time, one write in
 * flight at a time, and every event fired on the channel's event loop, never from the thread
 * that completes a read or a write. The design follows {@code ZitiSocketChannel} of
 * {@code org.restlet.ext.openziti}.</p>
 */
final class TunnelSocketChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    /** The size of the buffer of a read. */
    private static final int READ_BUFFER_SIZE = 16 * 1024;

    private final Tunnel tunnel;
    private final String host;
    private final int port;
    private final ChannelConfig config;

    /** The dialed connection; null until connected. */
    private volatile AsynchronousSocketChannel socket;

    /** Whether the channel was closed. */
    private volatile boolean closed;

    /** Whether the connection is established. */
    private volatile boolean active;

    /** Whether a read is pending on the socket. Only used on the event loop. */
    private boolean readPending;

    /** Whether a write is in flight on the socket. Only used on the event loop. */
    private boolean writeInFlight;

    TunnelSocketChannel(Tunnel tunnel, String host, int port) {
        super(null);
        this.tunnel = tunnel;
        this.host = host;
        this.port = port;
        this.config = new DefaultChannelConfig(this);
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public boolean isOpen() {
        AsynchronousSocketChannel s = socket;
        return !closed && (s == null || s.isOpen());
    }

    @Override
    public boolean isActive() {
        return active && isOpen();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        AsynchronousSocketChannel s = socket;
        try {
            return s == null ? null : s.getLocalAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected SocketAddress remoteAddress0() {
        AsynchronousSocketChannel s = socket;
        try {
            return s == null ? null : s.getRemoteAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("A tunnel connection can't be bound");
    }

    @Override
    protected void doDisconnect() throws IOException {
        doClose();
    }

    @Override
    protected void doClose() throws IOException {
        closed = true;
        active = false;
        AsynchronousSocketChannel s = socket;
        if (s != null) {
            s.close();
        }
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            return msg;
        }
        throw new UnsupportedOperationException(
                "Unsupported message type: " + msg.getClass().getName());
    }

    /** Runs a completion on the event loop; returns false if the loop refused it. */
    private boolean onEventLoop(Runnable task) {
        try {
            eventLoop().execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // The event loop is shutting down, with the channel closed.
            return false;
        }
    }

    @Override
    protected void doBeginRead() {
        if (readPending || !isActive()) {
            return;
        }
        readPending = true;
        ByteBuf buffer = config.getAllocator().heapBuffer(READ_BUFFER_SIZE);
        ByteBuffer target = buffer.nioBuffer(buffer.writerIndex(), buffer.writableBytes());
        try {
            socket.read(target, null, new ReadHandler(buffer));
        } catch (RuntimeException e) {
            readPending = false;
            buffer.release();
            throw e;
        }
    }

    /** Completes a read on the event loop. */
    private final class ReadHandler implements CompletionHandler<Integer, Void> {

        private final ByteBuf buffer;

        ReadHandler(ByteBuf buffer) {
            this.buffer = buffer;
        }

        @Override
        public void completed(Integer read, Void attachment) {
            if (!onEventLoop(() -> readCompleted(read))) {
                buffer.release();
            }
        }

        @Override
        public void failed(Throwable cause, Void attachment) {
            if (!onEventLoop(() -> readFailed(cause))) {
                buffer.release();
            }
        }

        private void readCompleted(int read) {
            readPending = false;
            if (read < 0) {
                buffer.release();
                pipeline().fireChannelReadComplete();
                unsafe().close(voidPromise());
                return;
            }
            buffer.writerIndex(buffer.writerIndex() + read);
            pipeline().fireChannelRead(buffer);
            pipeline().fireChannelReadComplete();
            if (config.isAutoRead()) {
                read();
            }
        }

        private void readFailed(Throwable cause) {
            readPending = false;
            buffer.release();
            if (isOpen()) {
                pipeline().fireExceptionCaught(cause);
                unsafe().close(voidPromise());
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        if (writeInFlight) {
            // Resumed by the completion of the write in flight.
            return;
        }
        Object msg;
        while ((msg = in.current()) != null) {
            ByteBuf buffer = (ByteBuf) msg;
            if (!buffer.isReadable()) {
                in.remove();
                continue;
            }
            // Retained until the write completes: closing the channel meanwhile releases the
            // pending messages while the socket may still read this one.
            writeInFlight = true;
            buffer.retain();
            try {
                socket.write(buffer.nioBuffer(), null, new WriteHandler(buffer));
            } catch (RuntimeException e) {
                writeInFlight = false;
                buffer.release();
                throw e;
            }
            return;
        }
    }

    /** Completes a write on the event loop. */
    private final class WriteHandler implements CompletionHandler<Integer, Void> {

        private final ByteBuf buffer;

        WriteHandler(ByteBuf buffer) {
            this.buffer = buffer;
        }

        @Override
        public void completed(Integer written, Void attachment) {
            buffer.release();
            onEventLoop(() -> writeCompleted(written));
        }

        @Override
        public void failed(Throwable cause, Void attachment) {
            buffer.release();
            onEventLoop(() -> writeFailed(cause));
        }

        private void writeCompleted(int written) {
            writeInFlight = false;
            ChannelOutboundBuffer in = unsafe().outboundBuffer();
            if (in != null) {
                in.removeBytes(written);
                ((TunnelUnsafe) unsafe()).resumeWrites();
            }
        }

        private void writeFailed(Throwable cause) {
            writeInFlight = false;
            if (isOpen()) {
                pipeline().fireExceptionCaught(cause);
            }
            unsafe().close(voidPromise());
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new TunnelUnsafe();
    }

    /** Dials the tunnel off the event loop and completes the connect on it. */
    private final class TunnelUnsafe extends AbstractUnsafe {

        /** Writes the flushed messages left, after a write completed. */
        void resumeWrites() {
            flush0();
        }

        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
                ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            int timeoutMillis = config.getConnectTimeoutMillis();
            ScheduledFuture<?> timeout = timeoutMillis > 0
                    ? eventLoop().schedule(() -> {
                        if (promise.tryFailure(new ConnectTimeoutException(
                                "Tunnel connection timed out after " + timeoutMillis + " ms: "
                                        + host + ":" + port))) {
                            close(voidPromise());
                        }
                    }, timeoutMillis, TimeUnit.MILLISECONDS)
                    : null;
            Thread.ofVirtual().name("ikanos-tunnel-dial").start(() -> {
                try {
                    AsynchronousSocketChannel dialed = tunnel.connect(host, port);
                    if (!onEventLoop(() -> connected(dialed, promise, timeout))) {
                        closeQuietly(dialed);
                    }
                } catch (IOException | RuntimeException e) {
                    onEventLoop(() -> connectFailed(promise, timeout, e));
                }
            });
        }

        private void connected(AsynchronousSocketChannel dialed, ChannelPromise promise,
                ScheduledFuture<?> timeout) {
            if (timeout != null) {
                timeout.cancel(false);
            }
            socket = dialed;
            if (closed) {
                closeQuietly(dialed);
                return;
            }
            active = true;
            if (promise.trySuccess()) {
                pipeline().fireChannelActive();
            } else {
                // Timed out or cancelled meanwhile.
                close(voidPromise());
            }
        }

        private void connectFailed(ChannelPromise promise, ScheduledFuture<?> timeout,
                Throwable cause) {
            if (timeout != null) {
                timeout.cancel(false);
            }
            Throwable failure = cause;
            if (cause instanceof IOException && !(cause instanceof ClosedChannelException)) {
                ConnectException exception = new ConnectException(
                        "Tunnel dial failed for " + host + ":" + port + ": " + cause.getMessage());
                exception.initCause(cause);
                failure = exception;
            }
            promise.tryFailure(failure);
            closeIfClosed();
        }
    }

    private static void closeQuietly(AsynchronousSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // best effort
        }
    }
}
