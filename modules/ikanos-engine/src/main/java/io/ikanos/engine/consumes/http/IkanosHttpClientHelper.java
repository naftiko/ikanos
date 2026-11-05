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

import java.util.List;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.restlet.Client;
import org.restlet.engine.Engine;
import org.restlet.engine.connector.ConnectorHelper;

/**
 * Restlet {@link org.restlet.engine.connector.HttpClientHelper} subclass used by every consumed
 * HTTP adapter.
 *
 * <p>It removes Jetty's {@link WWWAuthenticationProtocolHandler} once the Jetty client has
 * started. Ikanos sends credentials itself and never registers a Jetty {@code Authentication},
 * so that handler has nothing to answer a challenge with. It does, however, fail every
 * {@code 401} that comes without a {@code WWW-Authenticate} header ("Authentication challenge
 * without WWW-Authenticate header"), which Restlet then reports as {@code 1002 Internal
 * Connector Error} with no body. Many APIs answer {@code 401} that way, with the explanation
 * in a JSON body, so without this the agent never sees why the call failed (#925).</p>
 *
 * <p>Restlet instantiates this class reflectively from its name, see
 * {@link HttpClientAdapter#HTTP_CLIENT_HELPER_CLASS_NAME}, but only among the helpers registered
 * with its {@link Engine}: {@link #ensureRegistered()} registers it.</p>
 */
public class IkanosHttpClientHelper extends org.restlet.engine.connector.HttpClientHelper {

    /**
     * Restlet calls this constructor reflectively when it resolves the helper class name.
     *
     * @param client the Restlet client
     */
    public IkanosHttpClientHelper(Client client) {
        super(client);
    }

    /**
     * Registers this helper with the Restlet {@link Engine} singleton, once per JVM, so that a
     * {@link Client} created with {@link HttpClientAdapter#HTTP_CLIENT_HELPER_CLASS_NAME} finds
     * it. It is appended after the default helpers, so a client that names no helper class still
     * gets the default one.
     */
    static synchronized void ensureRegistered() {
        List<ConnectorHelper<Client>> clients = Engine.getInstance().getRegisteredClients();
        if (clients.stream().noneMatch(h -> h.getClass() == IkanosHttpClientHelper.class)) {
            clients.add(new IkanosHttpClientHelper(null));
        }
    }

    /**
     * Starts the Jetty client, then removes the protocol handlers that Jetty installs on start
     * and that Ikanos does not use.
     */
    @Override
    public void start() throws Exception {
        super.start();
        removeUnusedProtocolHandlers(getHttpClient());
    }

    /**
     * Package-private for testing. Removes Jetty's WWW authentication handler, so that a
     * {@code 401} reaches Ikanos as a regular response with its body.
     */
    static void removeUnusedProtocolHandlers(HttpClient httpClient) {
        if (httpClient != null) {
            httpClient.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
        }
    }
}
