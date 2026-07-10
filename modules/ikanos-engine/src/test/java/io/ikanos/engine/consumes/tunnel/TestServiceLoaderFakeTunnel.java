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
 * Test-only {@link Tunnel} ServiceLoader provider so {@link TunnelBootstrap} can be exercised
 * end-to-end (including ServiceLoader discovery) without depending on the
 * {@code ikanos-tunnel-ziti} runtime module from engine unit tests.
 *
 * <p>The {@code META-INF/services/io.ikanos.engine.consumes.tunnel.Tunnel} descriptor under
 * {@code src/test/resources} wires this class; it never appears in the production jar.
 */
public class TestServiceLoaderFakeTunnel implements Tunnel {

    @Override
    public String type() {
        return "ziti";
    }

    @Override
    public AsynchronousSocketChannel connect(String host, int port) throws IOException {
        throw new IOException("test fake tunnel — not dialable");
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
