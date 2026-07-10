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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelRouteTable;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec;
import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpClientAdapterTunnelTest {

    @Test
    void selectTunnelShouldReturnNullWhenSpecHasNoTunnel() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        assertNull(HttpClientAdapter.selectTunnel(spec, Map.of("ziti", new FakeTunnel())));
    }

    @Test
    void selectTunnelShouldReturnNullWhenTunnelsEmpty() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        spec.setTunnel(new ZitiTunnelConfigSpec("crm-api", "/etc/ziti/app.json"));
        assertNull(HttpClientAdapter.selectTunnel(spec, Map.of()));
    }

    @Test
    void selectTunnelShouldReturnMatchingTunnelWhenTypeRegistered() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        spec.setTunnel(new ZitiTunnelConfigSpec("crm-api", "/etc/ziti/app.json"));
        Tunnel ziti = new FakeTunnel();
        assertSame(ziti, HttpClientAdapter.selectTunnel(spec, Map.of("ziti", ziti)));
    }

    @Test
    void selectTunnelShouldReturnNullWhenTypeNotRegistered() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        spec.setTunnel(new ZitiTunnelConfigSpec("crm-api", "/etc/ziti/app.json"));
        assertNull(HttpClientAdapter.selectTunnel(spec, Map.of("wireguard", new FakeTunnel())));
    }

    @Test
    void extractHostShouldReturnHostnameWhenBaseUriValid() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://crm.internal:8443/api", null);
        assertEquals("crm.internal", HttpClientAdapter.extractHost(spec));
    }

    @Test
    void extractHostShouldThrowWhenBaseUriBlank() {
        HttpClientSpec spec = new HttpClientSpec("http", "", null);
        assertThrows(IllegalArgumentException.class, () -> HttpClientAdapter.extractHost(spec));
    }

    @Test
    void extractHostShouldThrowWhenBaseUriHasNoHost() {
        HttpClientSpec spec = new HttpClientSpec("http", "file:///etc/whatever", null);
        assertThrows(IllegalArgumentException.class, () -> HttpClientAdapter.extractHost(spec));
    }

    @Test
    void threeArgConstructorShouldBuildTunnelAwareClientWhenTunnelMatches() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://crm.internal/api", null);
        spec.setTunnel(new ZitiTunnelConfigSpec("crm-api", "/etc/ziti/app.json"));

        HttpClientAdapter adapter = new HttpClientAdapter(null, spec, Map.of("ziti", new FakeTunnel()));

        // Use the package-private test helper instead of reaching through the Restlet
        // Context / attributes API: a future refactor that changes how the route table is
        // stored only needs to update tunnelRouteTable(), not every assertion here.
        TunnelRouteTable routes = adapter.tunnelRouteTable();
        assertNotNull(routes, "tunnel-routed adapter must expose a route table");
        assertEquals(1, routes.size());
        assertNotNull(routes.lookup("crm.internal"));
    }

    @Test
    void threeArgConstructorShouldBuildPlainClientWhenNoMatchingTunnel() {
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        // No tunnel configured at all.
        HttpClientAdapter adapter = new HttpClientAdapter(null, spec, Map.of());

        assertNotNull(adapter.getHttpClient());
        // Non-tunnel-routed adapter must not expose a route table.
        assertNull(adapter.tunnelRouteTable());
    }

    @Test
    void twoArgConstructorShouldRemainBackwardCompatible() {
        // Existing tests rely on the 2-arg signature; this ensures it still works.
        HttpClientSpec spec = new HttpClientSpec("http", "https://api.example.com", null);
        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);
        assertNotNull(adapter.getHttpClient());
        assertNull(adapter.tunnelRouteTable());
    }

    private static final class FakeTunnel implements Tunnel {
        @Override
        public String type() {
            return "ziti";
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
