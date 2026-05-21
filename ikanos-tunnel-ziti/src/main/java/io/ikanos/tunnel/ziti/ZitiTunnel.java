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
package io.ikanos.tunnel.ziti;

import io.ikanos.engine.consumes.tunnel.Tunnel;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.openziti.Ziti;
import org.openziti.ZitiContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Tunnel} implementation backed by the OpenZiti SDK ({@code org.openziti:ziti:0.33.1}).
 *
 * <p>This class is wired through {@link java.util.ServiceLoader} via the descriptor file
 * {@code META-INF/services/io.ikanos.engine.consumes.tunnel.Tunnel}. It MUST therefore expose
 * a public no-arg constructor — per-deployment configuration is read from system properties
 * set by {@code TunnelBootstrap}.
 *
 * <h2>Configuration</h2>
 *
 * <p>System property {@code ikanos.tunnel.ziti.identity} — REQUIRED. Filesystem path to a
 * Ziti identity file (JSON or PFX). Set by {@code TunnelBootstrap.discoverAndStart} from the
 * Mustache-resolved value of {@code consumes.http.tunnel.identity} in the capability YAML.
 *
 * <h2>Threading and lifecycle</h2>
 *
 * <p>The {@link ZitiContext} is created lazily on first {@link #connect(String, int)} or
 * {@link #isReady()} call (to keep the no-arg constructor side-effect-free for
 * {@link java.util.ServiceLoader} discovery). It is single-instance per JVM/per capability —
 * tunnel pooling at the engine level guarantees only one provider per type per capability.
 */
public class ZitiTunnel implements Tunnel {

    /**
     * System-property key the engine uses to pass the resolved Ziti identity file path. See
     * {@code io.ikanos.engine.consumes.tunnel.TunnelBootstrap}.
     */
    public static final String IDENTITY_PROPERTY = "ikanos.tunnel.ziti.identity";

    private static final Logger LOG = LoggerFactory.getLogger(ZitiTunnel.class);
    private static final String TYPE = "ziti";
    private static final long DIAL_TIMEOUT_SECONDS = 10L;

    private final Object contextLock = new Object();
    private volatile ZitiContext context;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ZitiTunnel() {
        // lazy init — see contextRef
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public AsynchronousSocketChannel connect(String host, int port) throws IOException {
        ZitiContext ctx = ensureContext();
        AsynchronousSocketChannel channel = ctx.open();
        try {
            // The OpenZiti SDK's AsynchronousSocketChannel maps an unresolved host:port to a
            // configured intercept service on the Ziti controller. No DNS lookup is performed
            // for the host portion — that's the whole point of the overlay.
            channel
                    .connect(InetSocketAddress.createUnresolved(host, port))
                    .get(DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return channel;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeQuietly(channel);
            throw new IOException("Interrupted dialing Ziti for " + host + ":" + port, ie);
        } catch (TimeoutException te) {
            closeQuietly(channel);
            throw new IOException(
                    "Timed out dialing Ziti for " + host + ":" + port + " after "
                            + DIAL_TIMEOUT_SECONDS + "s",
                    te);
        } catch (java.util.concurrent.ExecutionException ee) {
            closeQuietly(channel);
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw new IOException("Ziti dial failed for " + host + ":" + port, cause);
        }
    }

    @Override
    public boolean isReady() {
        try {
            ZitiContext ctx = ensureContext();
            return ctx.getStatus() == ZitiContext.Status.Active.INSTANCE;
        } catch (IOException ex) {
            LOG.debug("Ziti context not ready: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        ZitiContext ctx;
        synchronized (contextLock) {
            ctx = this.context;
            this.context = null;
        }
        if (ctx != null) {
            try {
                ctx.destroy();
            } catch (RuntimeException ex) {
                LOG.warn("Error destroying Ziti context", ex);
            }
        }
    }

    /**
     * Package-private for testing. Lazily creates the {@link ZitiContext} from the identity
     * file referenced by {@link #IDENTITY_PROPERTY}.
     */
    ZitiContext ensureContext() throws IOException {
        ZitiContext local = this.context;
        if (local != null) {
            return local;
        }
        synchronized (contextLock) {
            if (this.context != null) {
                return this.context;
            }
            String identityPath = System.getProperty(IDENTITY_PROPERTY);
            if (identityPath == null || identityPath.isBlank()) {
                throw new IOException(
                        "System property '" + IDENTITY_PROPERTY
                                + "' is not set. The Ikanos engine sets this from"
                                + " consumes.http.tunnel.identity before loading the Ziti tunnel.");
            }
            File identityFile = new File(identityPath);
            if (!identityFile.isFile()) {
                throw new IOException(
                        "Ziti identity file not found: " + identityFile.getAbsolutePath());
            }
            try {
                this.context = Ziti.newContext(identityFile, new char[0]);
                LOG.info("Ziti tunnel initialised with identity {}", identityFile.getName());
                return this.context;
            } catch (RuntimeException ex) {
                throw new IOException(
                        "Failed to initialise Ziti context from " + identityFile.getAbsolutePath(),
                        ex);
            }
        }
    }

    private static void closeQuietly(AsynchronousSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
