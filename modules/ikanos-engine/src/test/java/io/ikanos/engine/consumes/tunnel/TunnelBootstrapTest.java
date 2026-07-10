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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec;
import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TunnelBootstrapTest {

    @Test
    void discoverAndStartShouldReturnEmptyMapWhenNoTunnelsConfigured() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        Map<String, Tunnel> tunnels = TunnelBootstrap.discoverAndStart(
                List.<ClientSpec>of(spec), Duration.ofSeconds(1));
        assertTrue(tunnels.isEmpty());
    }

    @Test
    void discoverAndStartShouldReturnEmptyMapWhenClientSpecsNull() {
        Map<String, Tunnel> tunnels = TunnelBootstrap.discoverAndStart(null, Duration.ofSeconds(1));
        assertTrue(tunnels.isEmpty());
    }

    @Test
    void resolveIdentityShouldResolveMustacheTemplateWhenBindingsProvided() {
        ZitiTunnelConfigSpec spec = new ZitiTunnelConfigSpec("crm-api", "{{secrets.IDENT}}");
        String resolved = TunnelBootstrap.resolveIdentity(
                spec, Map.of("secrets", Map.of("IDENT", "/etc/ziti/app.json")));
        assertEquals("/etc/ziti/app.json", resolved);
    }

    @Test
    void resolveIdentityShouldReturnNullWhenIdentityBlank() {
        ZitiTunnelConfigSpec spec = new ZitiTunnelConfigSpec("crm-api", "  ");
        assertNull(TunnelBootstrap.resolveIdentity(spec, Map.of()));
    }

    @Test
    void resolveIdentityShouldReturnRawWhenBindingsEmpty() {
        ZitiTunnelConfigSpec spec = new ZitiTunnelConfigSpec("crm-api", "/etc/ziti/app.json");
        assertEquals("/etc/ziti/app.json", TunnelBootstrap.resolveIdentity(spec, Map.of()));
    }

    @Test
    void resolveIdentityShouldThrowWhenMustacheBindingShapeIsFlat() {
        // Flat-map mistake: the template references the "secrets" namespace, but the
        // caller passes a flat map. Resolver leaves "{{secrets.IDENT}}" unresolved, and
        // resolveIdentity must fail fast instead of silently propagating the literal.
        ZitiTunnelConfigSpec spec = new ZitiTunnelConfigSpec("crm-api", "{{secrets.IDENT}}");
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> TunnelBootstrap.resolveIdentity(
                        spec, Map.of("IDENT", "/etc/ziti/app.json")));
        assertTrue(ex.getMessage().contains("{{secrets.IDENT}}"),
                "exception should echo the unresolved Mustache template");
    }

    @Test
    void loadTunnelShouldThrowWhenTypeUnknown() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> TunnelBootstrap.loadTunnel("does-not-exist"));
        assertTrue(ex.getMessage().contains("does-not-exist"),
                "message should mention the missing type");
    }

    @Test
    void waitReadyShouldReturnImmediatelyWhenTimeoutZero() {
        // never-ready tunnel; zero timeout means "don't gate" per the contract
        TunnelBootstrap.waitReady(new NeverReadyTunnel(), Duration.ZERO);
    }

    @Test
    void waitReadyShouldThrowWhenTunnelNotReadyWithinTimeout() {
        assertThrows(
                IllegalStateException.class,
                () -> TunnelBootstrap.waitReady(
                        new NeverReadyTunnel(), Duration.ofMillis(200)));
    }

    @Test
    void discoverAndStartShouldRejectConflictingIdentitiesForSameType() {
        ZitiTunnelConfigSpec a = new ZitiTunnelConfigSpec("svc-a", "/etc/ziti/a.json");
        ZitiTunnelConfigSpec b = new ZitiTunnelConfigSpec("svc-b", "/etc/ziti/b.json");
        HttpClientSpec specA = new HttpClientSpec("http", "https://a.example.com", null);
        specA.setTunnel(a);
        HttpClientSpec specB = new HttpClientSpec("http", "https://b.example.com", null);
        specB.setTunnel(b);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> TunnelBootstrap.discoverAndStart(
                        List.<ClientSpec>of(specA, specB), Duration.ZERO));
        assertTrue(ex.getMessage().contains("identities"),
                "message should mention the conflict");
    }

    private static final class NeverReadyTunnel implements Tunnel {
        @Override
        public String type() {
            return "never-ready";
        }

        @Override
        public AsynchronousSocketChannel connect(String host, int port) throws IOException {
            throw new IOException("not ready");
        }

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
