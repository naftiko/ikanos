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

import static io.ikanos.engine.observability.OtelNullSafety.nonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

/**
 * Unit tests for {@link UncaughtExceptionFilter} (issue #897).
 */
class UncaughtExceptionFilterTest {

    private static final String SECRET_DETAIL = "jdbc:postgresql://db.internal/orders";

    @Test
    void handleShouldAnswer500WhenTheNextRestletThrows() throws Exception {
        Response response = handle(new UncaughtExceptionFilter(throwing(
                new IllegalStateException(SECRET_DETAIL))));

        assertEquals(Status.SERVER_ERROR_INTERNAL, response.getStatus());
        String body = response.getEntity().getText();
        assertTrue(body.startsWith(UncaughtExceptionFilter.PUBLIC_MESSAGE), body);
        assertTrue(body.contains("(reference: "), "the caller should get a reference: " + body);
        assertFalse(body.contains(SECRET_DETAIL), "the exception detail must not leak: " + body);
    }

    @Test
    void handleShouldReferToTheTraceOfAFailedRequestWhoseSpanHasEnded() throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        Span span = Span.wrap(nonNull(SpanContext.create(traceId, "b7ad6b7169203331",
                nonNull(TraceFlags.getSampled()), nonNull(TraceState.getDefault()))));
        Restlet traced = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                // Like ResourceRestlet: the span's scope closes before the filter catches
                ErrorReference.rememberTrace(request, span);
                throw new IllegalStateException(SECRET_DETAIL);
            }
        };

        Response response = handle(new UncaughtExceptionFilter(traced));

        assertTrue(response.getEntity().getText().contains("(reference: " + traceId + ")"),
                "the reference should find the request's trace");
    }

    @Test
    void handleShouldAnswer500WhenTheNextRestletThrowsAnError() {
        Response response = handle(new UncaughtExceptionFilter(throwing(
                new AssertionError(SECRET_DETAIL))));

        assertEquals(Status.SERVER_ERROR_INTERNAL, response.getStatus());
    }

    @Test
    void handleShouldReplaceWhatTheFailedRestletAlreadySet() throws Exception {
        Restlet partial = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("{\"partial\":", MediaType.APPLICATION_JSON);
                throw new IllegalStateException(SECRET_DETAIL);
            }
        };

        Response response = handle(new UncaughtExceptionFilter(partial));

        assertEquals(Status.SERVER_ERROR_INTERNAL, response.getStatus());
        assertEquals(MediaType.TEXT_PLAIN, response.getEntity().getMediaType());
        assertFalse(response.getEntity().getText().contains("partial"));
    }

    @Test
    void handleShouldLeaveASuccessfulResponseUnchanged() throws Exception {
        Restlet ok = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_CREATED);
                response.setEntity("done", MediaType.TEXT_PLAIN);
            }
        };

        Response response = handle(new UncaughtExceptionFilter(ok));

        assertEquals(Status.SUCCESS_CREATED, response.getStatus());
        assertEquals("done", response.getEntity().getText());
    }

    private static Restlet throwing(Throwable failure) {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) failure;
            }
        };
    }

    private static Response handle(Restlet restlet) {
        Request request = new Request(Method.GET, "http://localhost/orders");
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }
}
