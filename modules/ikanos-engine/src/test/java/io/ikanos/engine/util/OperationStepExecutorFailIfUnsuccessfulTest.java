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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.InputRepresentation;
import org.restlet.representation.StringRepresentation;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.consumes.http.HttpConsumedResult;
import io.ikanos.engine.util.OperationStepExecutor.StepFailedException;

/**
 * Pins the success rule of {@link OperationStepExecutor#failIfUnsuccessful} (#739): only 2xx
 * passes. 3xx and a missing response fail. The rule is tested through
 * {@link ConsumedResult#isSuccessful()}, not {@link Outcome#SUCCESS} (which also covers 3xx), so
 * it cannot silently become "below 400".
 */
public class OperationStepExecutorFailIfUnsuccessfulTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 299})
    public void shouldPassOn2xx(int code) {
        assertDoesNotThrow(
                () -> OperationStepExecutor.failIfUnsuccessful("step", result(code)));
    }

    @ParameterizedTest
    @ValueSource(ints = {300, 301, 302, 304, 307, 399, 400, 404, 500, 503})
    public void shouldFailOnNon2xxIncluding3xx(int code) {
        StepFailedException error = assertThrows(StepFailedException.class,
                () -> OperationStepExecutor.failIfUnsuccessful("step", result(code)));

        assertEquals("step", error.getStepName());
        assertEquals(code, error.getStatusCode());
        assertEquals("Step 'step' failed with HTTP " + code, error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 304})
    public void shouldFailOn3xxEvenThoughOutcomeIsSuccess(int code) {
        ConsumedResult result = result(code);

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertThrows(StepFailedException.class,
                () -> OperationStepExecutor.failIfUnsuccessful("step", result));
    }

    @Test
    public void shouldFailWhenNoResponseWasReceived() {
        StepFailedException error = assertThrows(StepFailedException.class,
                () -> OperationStepExecutor.failIfUnsuccessful("step", null));

        assertEquals(0, error.getStatusCode());
        assertEquals("Step 'step' failed: no response received", error.getMessage());
    }

    @Test
    public void shouldFailOnConnectorErrorStatus() {
        StepFailedException error = assertThrows(StepFailedException.class,
                () -> OperationStepExecutor.failIfUnsuccessful("step",
                        result(Status.CONNECTOR_ERROR_CONNECTION.getCode())));

        assertEquals(Status.CONNECTOR_ERROR_CONNECTION.getCode(), error.getStatusCode());
        assertEquals("Step 'step' failed: connector error "
                + Status.CONNECTOR_ERROR_CONNECTION.getCode(), error.getMessage());
    }

    @Test
    public void shouldReleaseTheUpstreamBodyOnFailure() {
        Response response = new Response(new Request());
        response.setStatus(Status.SERVER_ERROR_INTERNAL);
        StringRepresentation body = new StringRepresentation("secret error body");
        response.setEntity(body);

        assertThrows(StepFailedException.class, () -> OperationStepExecutor
                .failIfUnsuccessful("step", new HttpConsumedResult(null, response)));

        assertTrue(!body.isAvailable(), "the upstream body should be released");
    }

    /** REST and MCP resources never return the body, so it is released unread (#963 review). */
    @Test
    public void shouldNotReadTheUpstreamBodyOutsideAnMcpTool() {
        AtomicLong read = new AtomicLong();
        Response response = failed(new InputRepresentation(endless(read),
                MediaType.APPLICATION_JSON));

        StepFailedException error = assertThrows(StepFailedException.class, () -> OperationStepExecutor
                .failIfUnsuccessful("step", new HttpConsumedResult(null, response)));

        assertNull(error.getUpstreamBody());
        assertEquals(0, read.get(), "the upstream body should not be read");
    }

    @Test
    public void shouldKeepTheUpstreamBodyForAnMcpTool() {
        StringRepresentation body = new StringRepresentation("{\"message\":\"Ship not found\"}");
        Response response = failed(body);

        StepFailedException error;
        try (var keep = OperationStepExecutor.keepUpstreamErrorBodies()) {
            error = assertThrows(StepFailedException.class, () -> OperationStepExecutor
                    .failIfUnsuccessful("step", new HttpConsumedResult(null, response)));
        }

        assertEquals("{\"message\":\"Ship not found\"}", error.getUpstreamBody());
        assertTrue(!body.isAvailable(), "the upstream body should be released");
    }

    /** An endless body is read up to the cap, then left out: never buffered whole. */
    @Test
    public void shouldLeaveOutAnUpstreamBodyLargerThanTheCap() {
        AtomicLong read = new AtomicLong();
        Response response = failed(new InputRepresentation(endless(read),
                MediaType.APPLICATION_JSON));

        StepFailedException error;
        try (var keep = OperationStepExecutor.keepUpstreamErrorBodies()) {
            error = assertThrows(StepFailedException.class, () -> OperationStepExecutor
                    .failIfUnsuccessful("step", new HttpConsumedResult(null, response)));
        }

        assertEquals("The upstream error body was left out: it is larger than "
                + OperationStepExecutor.MAX_UPSTREAM_ERROR_BODY_BYTES + " bytes.",
                error.getUpstreamBody());
        assertTrue(read.get() <= OperationStepExecutor.MAX_UPSTREAM_ERROR_BODY_BYTES + 8192,
                "read " + read.get() + " bytes, past the cap");
    }

    @Test
    public void shouldNotReadABodyThatSaysItIsLargerThanTheCap() {
        AtomicLong read = new AtomicLong();
        InputRepresentation entity = new InputRepresentation(endless(read),
                MediaType.APPLICATION_JSON);
        entity.setSize(OperationStepExecutor.MAX_UPSTREAM_ERROR_BODY_BYTES + 1L);
        Response response = failed(entity);

        StepFailedException error;
        try (var keep = OperationStepExecutor.keepUpstreamErrorBodies()) {
            error = assertThrows(StepFailedException.class, () -> OperationStepExecutor
                    .failIfUnsuccessful("step", new HttpConsumedResult(null, response)));
        }

        assertTrue(error.getUpstreamBody().startsWith("The upstream error body was left out"));
        assertEquals(0, read.get(), "the upstream body should not be read");
    }

    @Test
    public void shouldStopKeepingTheBodyOnceTheScopeIsClosed() {
        try (var outer = OperationStepExecutor.keepUpstreamErrorBodies()) {
            try (var inner = OperationStepExecutor.keepUpstreamErrorBodies()) {
                // a tool called from a tool, on the same thread
            }
            assertEquals("kept", bodyOf(new StringRepresentation("kept")),
                    "closing the inner scope keeps the outer one");
        }

        assertNull(bodyOf(new StringRepresentation("dropped")));
    }

    private static String bodyOf(StringRepresentation body) {
        Response response = failed(body);
        return assertThrows(StepFailedException.class, () -> OperationStepExecutor
                .failIfUnsuccessful("step", new HttpConsumedResult(null, response)))
                .getUpstreamBody();
    }

    private static Response failed(org.restlet.representation.Representation body) {
        Response response = new Response(new Request());
        response.setStatus(Status.SERVER_ERROR_INTERNAL);
        response.setEntity(body);
        return response;
    }

    /** A body that never ends, counting what is read from it. */
    private static InputStream endless(AtomicLong read) {
        return new InputStream() {
            @Override
            public int read() {
                read.incrementAndGet();
                return 'x';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'x');
                read.addAndGet(length);
                return length;
            }
        };
    }

    private static ConsumedResult result(int code) {
        Response response = new Response(new Request());
        response.setStatus(new Status(code));
        return new HttpConsumedResult(null, response);
    }
}
