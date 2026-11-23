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
import java.util.function.Function;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.engine.Engine;
import org.restlet.engine.connector.ConnectorHelper;

/**
 * Restlet {@link org.restlet.engine.connector.HttpClientHelper} subclass used by every consumed
 * HTTP adapter.
 *
 * <p>On Restlet 3.0.0-m1 it worked around two behaviors of the Jetty connector: Jetty's
 * {@code WWWAuthenticationProtocolHandler} turned a {@code 401} without {@code WWW-Authenticate}
 * into a {@code 1002} with no body (#925), and Jetty sent every entity chunked, even of a known
 * size (#954). Restlet 3.0.0-m2's Netty connector does neither: a {@code 401} reaches the caller
 * with its body, and an entity of known size is sent with {@code Content-Length}.</p>
 *
 * <p>What remains is {@link #NO_REDIRECTS}: the Netty connector follows redirects of synchronous
 * calls for the whole client ({@code followRedirects}), and forward mode needs to turn that off
 * for one request.</p>
 *
 * <p>Restlet instantiates this class reflectively from its name, see
 * {@link HttpClientAdapter#HTTP_CLIENT_HELPER_CLASS_NAME}, but only among the helpers registered
 * with its {@link Engine}: {@link #ensureRegistered()} registers it.</p>
 */
public class IkanosHttpClientHelper extends org.restlet.engine.connector.HttpClientHelper {

    /**
     * Request attribute that, set to {@link Boolean#TRUE}, makes the client return a redirect
     * as is instead of following it. Forward mode sets it, so the caller gets the upstream
     * {@code 3xx} with its {@code Location} relayed by the forward rules, instead of the gateway
     * following it to wherever it points (#1002).
     */
    public static final String NO_REDIRECTS = "io.ikanos.engine.noRedirects";

    /**
     * Set while {@link #handle} serves a request carrying {@link #NO_REDIRECTS}: the connector
     * reads {@link #isFollowRedirects()} on the calling thread when it starts the exchange.
     */
    private static final ThreadLocal<Boolean> NO_FOLLOW = new ThreadLocal<>();

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
    static void ensureRegistered() {
        ensureRegistered(IkanosHttpClientHelper.class, IkanosHttpClientHelper::new);
    }

    /**
     * Registers a helper class with the Restlet {@link Engine} singleton, once per JVM.
     *
     * <p>Restlet resolves a helper class name only among the registered helpers: it takes the
     * one with that canonical name, then instantiates its class with the {@link Client}. A helper
     * that is not registered leaves the client without a connector, and every request fails
     * with {@code 1002} (#940).</p>
     *
     * @param type the helper class
     * @param factory builds the registered prototype from a {@code null} client
     */
    static synchronized <T extends IkanosHttpClientHelper> void ensureRegistered(Class<T> type,
            Function<Client, T> factory) {
        List<ConnectorHelper<Client>> clients = Engine.getInstance().getRegisteredClients();
        if (clients.stream().noneMatch(h -> h.getClass() == type)) {
            clients.add(factory.apply(null));
        }
    }

    /**
     * Handles a call, without following redirects when {@code request} carries
     * {@link #NO_REDIRECTS}. The client follows them otherwise (Restlet's {@code followRedirects}
     * defaults to {@code true}, and an {@code awsSigV4} adapter turns it off for the whole
     * client).
     */
    @Override
    public void handle(Request request, Response response) {
        if (!Boolean.TRUE.equals(request.getAttributes().get(NO_REDIRECTS))) {
            super.handle(request, response);
            return;
        }
        NO_FOLLOW.set(Boolean.TRUE);
        try {
            super.handle(request, response);
        } finally {
            NO_FOLLOW.remove();
        }
    }

    @Override
    public boolean isFollowRedirects() {
        return NO_FOLLOW.get() == null && super.isFollowRedirects();
    }
}
