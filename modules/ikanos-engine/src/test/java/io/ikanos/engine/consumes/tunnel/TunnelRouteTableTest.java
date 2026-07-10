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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import org.junit.jupiter.api.Test;

class TunnelRouteTableTest {

    @Test
    void lookupShouldReturnNullWhenHostNotRegistered() {
        TunnelRouteTable table = new TunnelRouteTable();
        assertNull(table.lookup("crm.internal"));
        assertTrue(table.isEmpty());
        assertEquals(0, table.size());
    }

    @Test
    void lookupShouldReturnTunnelWhenHostRegistered() {
        TunnelRouteTable table = new TunnelRouteTable();
        Tunnel tunnel = new FakeTunnel();
        table.register("crm.internal", tunnel);

        assertSame(tunnel, table.lookup("crm.internal"));
        assertFalse(table.isEmpty());
        assertEquals(1, table.size());
    }

    @Test
    void lookupShouldBeCaseInsensitiveWhenHostRegisteredInMixedCase() {
        TunnelRouteTable table = new TunnelRouteTable();
        Tunnel tunnel = new FakeTunnel();
        table.register("CRM.Internal", tunnel);

        assertSame(tunnel, table.lookup("crm.internal"));
        assertSame(tunnel, table.lookup("CRM.INTERNAL"));
        assertSame(tunnel, table.lookup("crm.INTERNAL"));
    }

    @Test
    void registerShouldRejectNullHostWhenInvoked() {
        TunnelRouteTable table = new TunnelRouteTable();
        Tunnel tunnel = new FakeTunnel();
        assertThrows(NullPointerException.class, () -> table.register(null, tunnel));
    }

    @Test
    void registerShouldRejectNullTunnelWhenInvoked() {
        TunnelRouteTable table = new TunnelRouteTable();
        assertThrows(NullPointerException.class, () -> table.register("crm.internal", null));
    }

    @Test
    void lookupShouldHandleNullHostWhenInvoked() {
        TunnelRouteTable table = new TunnelRouteTable();
        // Null host = direct route, must not blow up.
        assertNull(table.lookup(null));
    }

    /** Minimal in-process Tunnel used purely as a sentinel reference. */
    private static final class FakeTunnel implements Tunnel {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public AsynchronousSocketChannel connect(String host, int port) throws IOException {
            throw new IOException("fake tunnel — not dialable");
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
