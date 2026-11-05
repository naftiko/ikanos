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

import java.util.Date;
import java.util.concurrent.Semaphore;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.routing.Filter;
import io.ikanos.engine.observability.TelemetryBootstrap;

/**
 * Limits how many requests a server adapter works on at once (#920).
 *
 * <p>Without a limit, Jetty takes up to 200 requests at once with an unbounded queue behind them.
 * Past its capacity an adapter then collapses instead of slowing down: every request in progress
 * holds its data, the heap fills, garbage collection takes the CPU, and nothing completes, not even
 * once the load is gone. Past the limit, a request gets an immediate {@code 503} with
 * {@code Retry-After}, decided before any work or memory is spent on it, so the requests already
 * admitted keep being answered.</p>
 */
class RequestsInProgressFilter extends Filter {

    /** Seconds a refused client is told to wait before trying again. */
    static final int RETRY_AFTER_SECONDS = 1;

    private final Semaphore permits;
    private final int limit;
    private final String adapterType;
    private final String namespace;

    RequestsInProgressFilter(int limit, String adapterType, String namespace, Restlet next) {
        super(null, next);
        if (limit < 1) {
            throw new IllegalArgumentException("maxRequestsInProgress must be at least 1: " + limit);
        }
        this.permits = new Semaphore(limit);
        this.limit = limit;
        this.adapterType = adapterType;
        this.namespace = namespace;
    }

    int getLimit() {
        return limit;
    }

    @Override
    protected int doHandle(Request request, Response response) {
        // Taking and giving back the slot in one place: nothing between them can leak it
        if (!permits.tryAcquire()) {
            refuse(request, response);
            return STOP;
        }
        try {
            TelemetryBootstrap.get().getMetrics().requestInProgress(adapterType, namespace, 1);
            return super.doHandle(request, response);
        } finally {
            permits.release();
            TelemetryBootstrap.get().getMetrics().requestInProgress(adapterType, namespace, -1);
        }
    }

    private void refuse(Request request, Response response) {
        TelemetryBootstrap.get().getMetrics().recordRequestRejected(adapterType, namespace);
        // Read the body first, or Jetty would close a keep-alive connection under the client (#878)
        RejectedRequestBodies.discard(request);
        response.setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE);
        // Restlet only writes Retry-After as a date: one second from now
        response.setRetryAfter(new Date(System.currentTimeMillis() + RETRY_AFTER_SECONDS * 1000L));
        response.setEntity("Too many requests in progress, try again later", MediaType.TEXT_PLAIN);
    }
}
