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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Status;

import io.ikanos.engine.consumes.Outcome;

/**
 * Pins how {@link HttpConsumedResult} maps HTTP statuses to {@link Outcome} and to the strict
 * {@link io.ikanos.engine.consumes.ConsumedResult#isSuccessful()} test. {@link Outcome#SUCCESS}
 * means "not an error" (1xx-3xx); only 2xx is successful, so a 304 and a missing status must
 * fail the strict test.
 */
public class HttpConsumedResultStatusTest {

    private static HttpConsumedResult resultWith(Status status) {
        Response response = new Response(new Request());
        response.setStatus(status);
        return new HttpConsumedResult(null, response);
    }

    @Test
    public void okShouldBeSuccessOutcomeAndSuccessful() {
        HttpConsumedResult result = resultWith(Status.SUCCESS_OK);

        assertEquals(200, result.status());
        assertEquals(Outcome.SUCCESS, result.outcome());
        assertTrue(result.isSuccessful());
    }

    @Test
    public void notModifiedShouldBeSuccessOutcomeButNotSuccessful() {
        HttpConsumedResult result = resultWith(Status.REDIRECTION_NOT_MODIFIED);

        assertEquals(304, result.status());
        assertEquals(Outcome.SUCCESS, result.outcome());
        assertFalse(result.isSuccessful(), "a 304 must not pass the strict 2xx test");
    }

    @Test
    public void missingStatusShouldReportZeroAndNotBeSuccessful() {
        HttpConsumedResult result = resultWith(null);

        assertEquals(0, result.status());
        assertNull(result.reason());
        assertFalse(result.isSuccessful(), "a missing status must not pass the strict 2xx test");
    }

    @Test
    public void missingResponseShouldReportZeroAndNotBeSuccessful() {
        HttpConsumedResult result = new HttpConsumedResult(null, null);

        assertEquals(0, result.status());
        assertFalse(result.isSuccessful(), "a missing response must not pass the strict 2xx test");
    }

    @Test
    public void clientAndServerErrorsShouldMapToErrorOutcomes() {
        assertEquals(Outcome.CLIENT_ERROR, resultWith(Status.CLIENT_ERROR_NOT_FOUND).outcome());
        assertEquals(Outcome.UPSTREAM_ERROR,
                resultWith(Status.SERVER_ERROR_INTERNAL).outcome());
    }
}
