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

import java.util.function.LongSupplier;
import java.util.logging.Level;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.routing.Filter;

/**
 * Refuses, with HTTP 403, every request whose direct peer is outside the MCP adapter's
 * {@code trustedNetworks} in {@code trust: forwarded} mode (issue #483).
 *
 * Choosing {@code forwarded} makes the gateway the authentication boundary: a request that did
 * not come through it must not be served, not even anonymously. The filter sits in front of
 * authentication and of the MCP dispatcher, so a refused request is never parsed and never runs a
 * tool. The response body is generic and does not name the networks or the peer address. The
 * warning is a {@link RateLimitedWarning}, so callers cannot flood the log.
 */
class TrustedPeerFilter extends Filter {

    static final long WARNING_INTERVAL_MILLIS = RateLimitedWarning.INTERVAL_MILLIS;

    private static final String FORBIDDEN_BODY = "Forbidden";

    private final AgentIdentityResolver resolver;
    private final RateLimitedWarning warning;

    TrustedPeerFilter(Context context, AgentIdentityResolver resolver, Restlet next) {
        this(context, resolver, next, new RateLimitedWarning());
    }

    TrustedPeerFilter(Context context, AgentIdentityResolver resolver, Restlet next,
            LongSupplier clock) {
        this(context, resolver, next, new RateLimitedWarning(clock));
    }

    private TrustedPeerFilter(Context context, AgentIdentityResolver resolver, Restlet next,
            RateLimitedWarning warning) {
        super(context, next);
        this.resolver = resolver;
        this.warning = warning;
    }

    @Override
    protected int beforeHandle(Request request, Response response) {
        String peer = request.getClientInfo() != null ? request.getClientInfo().getAddress() : null;
        if (resolver.isTrustedPeer(peer)) {
            return CONTINUE;
        }
        long dropped = warning.acquire();
        if (dropped >= 0) {
            // Log the peer, never the request's headers: they are attacker-controlled here.
            Context.getCurrentLogger().log(Level.WARNING,
                    "Refused MCP request from untrusted peer " + peer
                            + " (trust: forwarded); " + dropped
                            + " more refusals since the last warning");
        }
        response.setStatus(Status.CLIENT_ERROR_FORBIDDEN);
        response.setEntity(FORBIDDEN_BODY, MediaType.TEXT_PLAIN);
        return STOP;
    }

    /** @see RateLimitedWarning#shouldWarn(long) */
    boolean shouldWarn(long now) {
        return warning.shouldWarn(now);
    }

    /** @return refusals whose warning was suppressed since the last one logged. */
    long suppressedWarnings() {
        return warning.suppressed();
    }
}
