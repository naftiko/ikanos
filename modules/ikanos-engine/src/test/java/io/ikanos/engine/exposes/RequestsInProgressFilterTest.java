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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;
import org.restlet.data.Status;

/**
 * Unit tests for {@link RequestsInProgressFilter} (#920).
 */
class RequestsInProgressFilterTest {

    @Test
    void handleShouldAnswer503WithRetryAfterWhenTheLimitIsReached() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RequestsInProgressFilter filter = new RequestsInProgressFilter(1, "rest", "demo",
                blockingRestlet(inside, release));

        CompletableFuture<Status> first = CompletableFuture.supplyAsync(() -> statusOf(filter));
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the first request should be admitted");

        Response second = handle(filter);
        release.countDown();

        assertEquals(Status.SERVER_ERROR_SERVICE_UNAVAILABLE, second.getStatus());
        assertNotNull(second.getRetryAfter(), "a refused client should be told when to retry");
        assertEquals(Status.SUCCESS_OK, first.get(5, TimeUnit.SECONDS));
    }

    @Test
    void handleShouldAdmitARequestOnceTheOneInProgressIsDone() {
        RequestsInProgressFilter filter = new RequestsInProgressFilter(1, "rest", "demo", okRestlet());

        assertEquals(Status.SUCCESS_OK, statusOf(filter));
        assertEquals(Status.SUCCESS_OK, statusOf(filter));
    }

    @Test
    void handleShouldFreeTheSlotWhenTheRequestFails() {
        Restlet failing = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                throw new IllegalStateException("operation failed");
            }
        };
        RequestsInProgressFilter filter = new RequestsInProgressFilter(1, "rest", "demo", failing);

        assertThrows(IllegalStateException.class, () -> handle(filter));
        filter.setNext(okRestlet());

        assertEquals(Status.SUCCESS_OK, statusOf(filter));
    }

    @Test
    void constructorShouldRejectALimitBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestsInProgressFilter(0, "rest", "demo", okRestlet()));
    }

    private static Restlet okRestlet() {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
            }
        };
    }

    private static Restlet blockingRestlet(CountDownLatch inside, CountDownLatch release) {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                response.setStatus(Status.SUCCESS_OK);
            }
        };
    }

    private static Status statusOf(Restlet restlet) {
        return handle(restlet).getStatus();
    }

    private static Response handle(Restlet restlet) {
        Request request = new Request(Method.GET, "http://localhost/ping");
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }
}
