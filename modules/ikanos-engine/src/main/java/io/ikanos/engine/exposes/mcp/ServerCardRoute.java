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
package io.ikanos.engine.exposes.mcp;

import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;

/**
 * Sends the requests for an MCP adapter's server card to the card restlet, and every other
 * request to the rest of the adapter's chain.
 *
 * <p>The card path is the adapter's {@code path} followed by {@code /server-card}. An adapter
 * mounted without a {@code path} answers MCP on any path, so its card answers on any path whose
 * last segment is {@code server-card}, such as {@code /server-card} or {@code /mcp/server-card}.
 * </p>
 */
class ServerCardRoute extends Restlet {

    private final String cardPath;
    private final Restlet card;
    private final boolean preflightOnly;
    private final Restlet next;

    /**
     * @param mountPath the adapter's {@code path}, or {@code null} for none
     * @param preflightOnly route only the CORS preflight ({@code OPTIONS}) to the card, as for a
     *        card behind the server's authentication, whose {@code GET} is routed further in
     */
    ServerCardRoute(Context context, String mountPath, Restlet card, boolean preflightOnly,
            Restlet next) {
        super(context);
        this.cardPath = mountPath == null || "/".equals(mountPath) ? null
                : mountPath + ServerCardBuilder.PATH_SUFFIX;
        this.card = card;
        this.preflightOnly = preflightOnly;
        this.next = next;
    }

    @Override
    public void handle(Request request, Response response) {
        if (isCardPath(request.getResourceRef() != null ? request.getResourceRef().getPath()
                : null) && (!preflightOnly || Method.OPTIONS.equals(request.getMethod()))) {
            card.handle(request, response);
        } else {
            next.handle(request, response);
        }
    }

    /** Whether {@code path} is the card's path. */
    boolean isCardPath(String path) {
        if (path == null) {
            return false;
        }
        return cardPath != null ? cardPath.equals(path)
                : path.endsWith(ServerCardBuilder.PATH_SUFFIX);
    }

    @Override
    public synchronized void start() throws Exception {
        super.start();
        card.start();
        next.start();
    }

    @Override
    public synchronized void stop() throws Exception {
        next.stop();
        card.stop();
        super.stop();
    }
}
