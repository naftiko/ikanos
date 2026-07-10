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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ikanos.engine.consumes.tunnel.Tunnel;
import java.io.IOException;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZitiTunnelTest {

    private String previousIdentity;

    @BeforeEach
    void clearIdentityProperty() {
        previousIdentity = System.clearProperty(ZitiTunnel.IDENTITY_PROPERTY);
    }

    @AfterEach
    void restoreIdentityProperty() {
        if (previousIdentity == null) {
            System.clearProperty(ZitiTunnel.IDENTITY_PROPERTY);
        } else {
            System.setProperty(ZitiTunnel.IDENTITY_PROPERTY, previousIdentity);
        }
    }

    @Test
    void typeShouldReturnZitiAlways() {
        try (ZitiTunnel tunnel = new ZitiTunnel()) {
            assertEquals("ziti", tunnel.type());
        }
    }

    @Test
    void noArgConstructorShouldBeAvailableForServiceLoader() {
        // ServiceLoader requires a public no-arg constructor; assert nothing thrown.
        // Use assertDoesNotThrow rather than try-with-resources so the test does not
        // implicitly invoke close() — the intent is solely to verify the constructor.
        assertDoesNotThrow(() -> new ZitiTunnel());
    }

    @Test
    void serviceLoaderShouldDiscoverZitiTunnelProvider() {
        boolean found = false;
        for (Tunnel candidate : ServiceLoader.load(Tunnel.class)) {
            if ("ziti".equals(candidate.type())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "ZitiTunnel must be discoverable via ServiceLoader<Tunnel>");
    }

    @Test
    void ensureContextShouldThrowWhenIdentityPropertyMissing() {
        try (ZitiTunnel tunnel = new ZitiTunnel()) {
            IOException ex = assertThrows(IOException.class, tunnel::ensureContext);
            assertTrue(ex.getMessage().contains(ZitiTunnel.IDENTITY_PROPERTY));
        }
    }

    @Test
    void ensureContextShouldThrowWhenIdentityFileMissing() {
        System.setProperty(ZitiTunnel.IDENTITY_PROPERTY, "/this/file/definitely/does/not/exist.json");
        try (ZitiTunnel tunnel = new ZitiTunnel()) {
            IOException ex = assertThrows(IOException.class, tunnel::ensureContext);
            assertTrue(ex.getMessage().contains("not found"));
        }
    }

    @Test
    void isReadyShouldReturnFalseWhenContextCannotBeInitialised() {
        // No identity → ensureContext fails → isReady catches and returns false.
        try (ZitiTunnel tunnel = new ZitiTunnel()) {
            assertFalse(tunnel.isReady());
        }
    }

    @Test
    void closeShouldBeIdempotentWhenContextNeverInitialised() {
        ZitiTunnel tunnel = new ZitiTunnel();
        // Two closes without an initialised context must not throw.
        tunnel.close();
        tunnel.close();
    }
}
