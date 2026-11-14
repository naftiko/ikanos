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

import java.util.logging.Level;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.routing.Filter;

/**
 * Answers {@code 500} when an exception escapes an adapter's handler chain (issue #897).
 *
 * <p>Ikanos plugs its chains straight into the server connector, with no error handler. An
 * exception that reached the connector was logged there, but the response was never committed:
 * the client waited for an answer until it gave up, and the server's idle timeout did not end
 * it. This filter wraps the whole chain of each adapter, on a dedicated listener
 * ({@link ServerAdapter#initServer}) and on a shared one ({@link ServerHost}). It logs the failure
 * under a reference and answers a generic message with that reference, like any other unexpected
 * failure (see {@link ErrorReference}). {@link Error}s are caught too: answering them costs less
 * than holding the connection open.</p>
 */
class UncaughtExceptionFilter extends Filter {

    /** The caller-safe text of the answer, followed by the error reference. */
    static final String PUBLIC_MESSAGE = "Unexpected error while handling the request";

    UncaughtExceptionFilter(Restlet next) {
        super(null, next);
    }

    @Override
    protected int doHandle(Request request, Response response) {
        try {
            return super.doHandle(request, response);
        } catch (RuntimeException | Error e) {
            String correlationId = ErrorReference.record(Level.SEVERE,
                    "Uncaught exception while handling a " + request.getMethod() + " request", e,
                    request);
            // Read a body the handler left unread, or Jetty would close a keep-alive connection
            // under the client (#878)
            RejectedRequestBodies.discard(request);
            // Drop what the failed handler had already set, such as forwarded headers
            response.getHeaders().clear();
            response.setStatus(Status.SERVER_ERROR_INTERNAL);
            response.setEntity(ErrorReference.withReference(PUBLIC_MESSAGE, correlationId),
                    MediaType.TEXT_PLAIN);
            return STOP;
        }
    }
}
