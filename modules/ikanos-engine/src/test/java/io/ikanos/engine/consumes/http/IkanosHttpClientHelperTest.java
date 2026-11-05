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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.junit.jupiter.api.Test;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.data.Protocol;
import org.restlet.engine.Engine;

class IkanosHttpClientHelperTest {

    @Test
    void startShouldRemoveTheWwwAuthenticationHandlerThatJettyInstalls() throws Exception {
        IkanosHttpClientHelper helper = new IkanosHttpClientHelper(
                new Client(new Context(), List.of(Protocol.HTTP)));
        helper.start();
        try {
            assertNull(helper.getHttpClient().getProtocolHandlers()
                    .remove(WWWAuthenticationProtocolHandler.NAME));
        } finally {
            helper.stop();
        }
    }

    @Test
    void removeUnusedProtocolHandlersShouldRemoveAnInstalledHandler() throws Exception {
        HttpClient jettyClient = new HttpClient();
        jettyClient.start();
        try {
            IkanosHttpClientHelper.removeUnusedProtocolHandlers(jettyClient);

            assertNull(jettyClient.getProtocolHandlers()
                    .remove(WWWAuthenticationProtocolHandler.NAME));
        } finally {
            jettyClient.stop();
        }
    }

    @Test
    void jettyShouldInstallTheWwwAuthenticationHandlerOnStart() throws Exception {
        HttpClient jettyClient = new HttpClient();
        jettyClient.start();
        try {
            assertNotNull(jettyClient.getProtocolHandlers()
                    .remove(WWWAuthenticationProtocolHandler.NAME),
                    "if Jetty stops installing it, IkanosHttpClientHelper can be removed");
        } finally {
            jettyClient.stop();
        }
    }

    @Test
    void ensureRegisteredShouldRegisterTheHelperOnce() {
        IkanosHttpClientHelper.ensureRegistered();
        IkanosHttpClientHelper.ensureRegistered();

        assertEquals(1, Engine.getInstance().getRegisteredClients().stream()
                .filter(h -> h.getClass() == IkanosHttpClientHelper.class).count());
        Client client = new Client(new Context(), List.of(Protocol.HTTP, Protocol.HTTPS),
                HttpClientAdapter.HTTP_CLIENT_HELPER_CLASS_NAME);
        assertTrue(client.isAvailable(), "Restlet should find the registered helper");
    }

    @Test
    void removeUnusedProtocolHandlersShouldIgnoreAMissingClient() {
        assertDoesNotThrow(() -> IkanosHttpClientHelper.removeUnusedProtocolHandlers(null));
    }
}
