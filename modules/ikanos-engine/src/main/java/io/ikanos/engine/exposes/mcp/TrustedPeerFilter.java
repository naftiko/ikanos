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

import java.util.concurrent.atomic.AtomicLong;
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
 * warning is logged at most once per {@link #WARNING_INTERVAL_MILLIS}, so callers cannot flood
 * the log.
 */
class TrustedPeerFilter extends Filter {

    static final long WARNING_INTERVAL_MILLIS = 60_000;

    private static final String FORBIDDEN_BODY = "Forbidden";

    private final AgentIdentityResolver resolver;
    private final LongSupplier clock;
    // Starts at the lowest value (nanoTime can be negative), so the first refusal is always logged.
    private final AtomicLong nextWarningAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressed = new AtomicLong();

    TrustedPeerFilter(Context context, AgentIdentityResolver resolver, Restlet next) {
        // Monotonic: a wall-clock step backwards must not silence the warning for longer.
        this(context, resolver, next, () -> System.nanoTime() / 1_000_000);
    }

    TrustedPeerFilter(Context context, AgentIdentityResolver resolver, Restlet next,
            LongSupplier clock) {
        super(context, next);
        this.resolver = resolver;
        this.clock = clock;
    }

    @Override
    protected int beforeHandle(Request request, Response response) {
        String peer = request.getClientInfo() != null ? request.getClientInfo().getAddress() : null;
        if (resolver.isTrustedPeer(peer)) {
            return CONTINUE;
        }
        if (shouldWarn(clock.getAsLong())) {
            // Log the peer, never the request's headers: they are attacker-controlled here.
            long dropped = suppressed.getAndSet(0);
            Context.getCurrentLogger().log(Level.WARNING,
                    "Refused MCP request from untrusted peer " + peer
                            + " (trust: forwarded); " + dropped
                            + " more refusals since the last warning");
        } else {
            suppressed.incrementAndGet();
        }
        response.setStatus(Status.CLIENT_ERROR_FORBIDDEN);
        response.setEntity(FORBIDDEN_BODY, MediaType.TEXT_PLAIN);
        return STOP;
    }

    /**
     * @return {@code true} when no warning was logged in the {@link #WARNING_INTERVAL_MILLIS}
     *         before {@code now}, and reserves the next interval.
     */
    boolean shouldWarn(long now) {
        long next = nextWarningAt.get();
        return now >= next && nextWarningAt.compareAndSet(next, now + WARNING_INTERVAL_MILLIS);
    }

    /** @return refusals whose warning was suppressed since the last one logged. */
    long suppressedWarnings() {
        return suppressed.get();
    }
}
