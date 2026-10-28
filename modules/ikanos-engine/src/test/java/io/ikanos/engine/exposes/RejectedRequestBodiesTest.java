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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Expectation;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.representation.InputRepresentation;
import org.restlet.representation.Representation;

class RejectedRequestBodiesTest {

    @Test
    void drainShouldReadBodyWhenSizeIsWithinLimit() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[1024]);
        AtomicBoolean abandoned = new AtomicBoolean();

        RejectedRequestBodies.drain(requestWithBody(body, 1024), () -> abandoned.set(true));

        assertEquals(0, body.available(), "Body within the limit should be read");
        assertFalse(abandoned.get(), "A body read in time should not be abandoned");
    }

    @Test
    void drainShouldReadBodyWhenSizeIsExactlyTheLimit() {
        int size = (int) RejectedRequestBodies.MAX_SIZE;
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[size]);

        RejectedRequestBodies.drain(requestWithBody(body, size), () -> { });

        assertEquals(0, body.available(), "Body of exactly the limit should be read");
    }

    @Test
    void drainShouldLeaveBodyUnreadWhenSizeIsAboveLimit() {
        int size = (int) RejectedRequestBodies.MAX_SIZE + 1;
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[size]);

        RejectedRequestBodies.drain(requestWithBody(body, size), () -> { });

        assertEquals(size, body.available(), "Body above the limit should not be read");
    }

    @Test
    void drainShouldLeaveBodyUnreadWhenSizeIsUnknown() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[1024]);

        RejectedRequestBodies.drain(requestWithBody(body, Representation.UNKNOWN_SIZE), () -> { });

        assertEquals(1024, body.available(), "Body of unknown size should not be read");
    }

    @Test
    void drainShouldLeaveBodyUnreadWhenClientExpectsContinue() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[1024]);
        Request request = requestWithBody(body, 1024);
        request.getClientInfo().getExpectations().add(Expectation.continueResponse());

        RejectedRequestBodies.drain(request, () -> { });

        assertEquals(1024, body.available(),
                "Body should not be read when the client waits for 100 Continue");
    }

    @Test
    void drainShouldDoNothingWhenBodyWasAlreadyRead() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[1024]);
        Request request = requestWithBody(body, 1024);
        request.getEntity().setAvailable(false);

        RejectedRequestBodies.drain(request, () -> { });

        assertEquals(1024, body.available(), "A consumed body should not be read again");
    }

    @Test
    void drainShouldAbandonBodyWhenItDoesNotArriveInTime() {
        // Like the connector's stream when the body never comes: read() blocks until abandoned
        CountDownLatch abandoned = new CountDownLatch(1);
        InputStream stalled = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    abandoned.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("abandoned");
            }
        };

        long start = System.nanoTime();
        RejectedRequestBodies.drain(requestWithBody(stalled, 1024), abandoned::countDown);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(0, abandoned.getCount(), "The body should be abandoned");
        assertTrue(elapsedMillis >= RejectedRequestBodies.MAX_WAIT,
                "drain should wait for the body up to the limit, waited " + elapsedMillis);
        assertTrue(elapsedMillis < RejectedRequestBodies.MAX_WAIT + 2_000,
                "drain should give up shortly after the limit, waited " + elapsedMillis);
    }

    @Test
    void discardShouldDoNothingWhenRequestIsNotServedByJetty() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[1024]);

        RejectedRequestBodies.discard(requestWithBody(body, 1024));

        assertEquals(1024, body.available(),
                "Without a Jetty connector the body cannot be abandoned safely, so it is not read");
    }

    private static Request requestWithBody(InputStream body, long declaredSize) {
        Request request = new Request(Method.POST, "http://localhost/test");
        request.setEntity(new InputRepresentation(body, MediaType.APPLICATION_JSON, declaredSize));
        return request;
    }
}
