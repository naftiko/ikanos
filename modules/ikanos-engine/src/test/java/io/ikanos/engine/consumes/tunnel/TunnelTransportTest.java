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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import org.junit.jupiter.api.Test;

class TunnelTransportTest {

    @Test
    void constructorShouldRejectNullTunnel() {
        assertThrows(
                NullPointerException.class,
                () -> new TunnelTransport(null, "crm.internal", 8080));
    }

    @Test
    void constructorShouldRejectNullHost() {
        assertThrows(
                NullPointerException.class,
                () -> new TunnelTransport(new FakeTunnel(), null, 8080));
    }

    @Test
    void constructorShouldRejectNonPositivePort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TunnelTransport(new FakeTunnel(), "crm.internal", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TunnelTransport(new FakeTunnel(), "crm.internal", -1));
    }

    @Test
    void requiresDomainNameResolutionShouldReturnFalseAlways() {
        TunnelTransport transport = new TunnelTransport(new FakeTunnel(), "crm.internal", 8080);
        assertFalse(transport.requiresDomainNameResolution());
    }

    @Test
    void getSocketAddressShouldReturnSyntheticAddressTaggedByTunnelType() {
        TunnelTransport transport = new TunnelTransport(new FakeTunnel(), "crm.internal", 8080);
        assertNotNull(transport.getSocketAddress());
        assertTrue(transport.getSocketAddress().toString().contains("crm.internal"));
        assertTrue(transport.getSocketAddress().toString().contains("fake"));
        assertTrue(transport.getSocketAddress().toString().contains("8080"));
    }

    @Test
    void equalsShouldPartitionPoolsByTunnelHostAndPort() {
        Tunnel tunnelA = new FakeTunnel();
        Tunnel tunnelB = new FakeTunnel();

        TunnelTransport sameA = new TunnelTransport(tunnelA, "crm.internal", 8080);
        TunnelTransport sameAClone = new TunnelTransport(tunnelA, "crm.internal", 8080);
        TunnelTransport differentTunnel = new TunnelTransport(tunnelB, "crm.internal", 8080);
        TunnelTransport differentHost = new TunnelTransport(tunnelA, "erp.internal", 8080);
        TunnelTransport differentPort = new TunnelTransport(tunnelA, "crm.internal", 9090);

        assertEquals(sameA, sameAClone);
        assertEquals(sameA.hashCode(), sameAClone.hashCode());

        assertNotEquals(sameA, differentTunnel);
        assertNotEquals(sameA, differentHost);
        assertNotEquals(sameA, differentPort);
        assertNotEquals(sameA, "not a TunnelTransport");
    }

    @Test
    void toStringShouldIncludeTypeHostAndPort() {
        TunnelTransport transport = new TunnelTransport(new FakeTunnel(), "crm.internal", 8080);
        assertEquals("TunnelTransport[fake://crm.internal:8080]", transport.toString());
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
