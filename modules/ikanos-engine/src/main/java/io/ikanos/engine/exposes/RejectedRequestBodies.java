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
package io.ikanos.engine.exposes;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.eclipse.jetty.io.Content;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.data.Expectation;
import org.restlet.engine.adapter.HttpRequest;
import org.restlet.engine.adapter.JettyServerCall;
import org.restlet.representation.Representation;

/**
 * Discards the body of a request that the authentication chain rejects before reading it.
 *
 * <p>Without this, the connector commits the rejection while body bytes may still be in flight,
 * then closes the connection without a {@code Connection: close} header, and a keep-alive client
 * sending its next request on that connection reads EOF instead of a status line (issue #878).</p>
 *
 * <p>The client is not authenticated, so the work is bounded. Only bodies with a declared size of
 * at most {@link #MAX_SIZE} bytes are read. Reads return as soon as bytes arrive, so a body sent
 * with the request costs nothing, but a body still missing after {@link #MAX_WAIT} milliseconds
 * is abandoned, so a rejected request holds a worker thread for at most that long. Bodies of
 * unknown size (chunked), requests that expect a {@code 100 Continue} and abandoned bodies are
 * left unread, and for those the connection is closed as before.</p>
 *
 * <p>A body is abandoned by failing the Jetty request content, which wakes the blocked read and is
 * safe to call from the timer thread, unlike closing the stream. This relies on Restlet's Jetty
 * connector classes, so requests served by any other connector are not drained; revisit this when
 * Restlet moves its HTTP connector to Netty.</p>
 */
final class RejectedRequestBodies {

    /** Largest declared body size, in bytes, that is discarded. */
    static final long MAX_SIZE = 64 * 1024;

    /**
     * Longest time, in milliseconds, spent waiting for the body to arrive. Mirrored by
     * {@code McpAuthenticationIntegrationTest.REJECTED_BODY_MAX_WAIT}.
     */
    static final long MAX_WAIT = 500;

    /** Abandons the bodies that do not arrive in time. */
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private RejectedRequestBodies() {
    }

    /**
     * Reads and discards the body of the given rejected request, within the bounds above.
     */
    static void discard(Request request) {
        if (request instanceof HttpRequest httpRequest
                && httpRequest.getHttpCall() instanceof JettyServerCall call) {
            Content.Source content = call.getRequest();
            drain(request, () -> content.fail(
                    new TimeoutException("Body of rejected request not received in time")));
        }
    }

    /**
     * Reads and discards the body of the given request, running {@code abandon} from another
     * thread if it is still missing after {@link #MAX_WAIT}. The {@code abandon} action must make
     * the pending read of the request stream fail.
     */
    static void drain(Request request, Runnable abandon) {
        if (request.getClientInfo().getExpectations().contains(Expectation.continueResponse())) {
            return;
        }

        Representation entity = request.getEntity();

        if (entity == null || !entity.isAvailable() || entity.getSize() <= 0
                || entity.getSize() > MAX_SIZE) {
            return;
        }

        AtomicBoolean done = new AtomicBoolean();
        ScheduledFuture<?> timeout = TIMER.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                abandon.run();
            }
        }, MAX_WAIT, TimeUnit.MILLISECONDS);

        try {
            InputStream stream = entity.getStream();
            byte[] buffer = new byte[8192];
            long remaining = entity.getSize();

            while (stream != null && remaining > 0) {
                int read = stream.read(buffer, 0, (int) Math.min(remaining, buffer.length));

                if (read < 0) {
                    break;
                }

                remaining -= read;
            }
        } catch (IOException | RuntimeException e) {
            Context.getCurrentLogger().log(Level.FINE,
                    "Unable to discard the body of a rejected request", e);
        } finally {
            done.set(true);
            timeout.cancel(false);
        }
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ikanos-rejected-body-timer");
            thread.setDaemon(true);
            thread.setContextClassLoader(RejectedRequestBodies.class.getClassLoader());
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
