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
package io.ikanos.engine.consumes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Status;
import io.ikanos.engine.consumes.http.HttpConsumedResult;

/**
 * {@link ConsumedResult#noResponseReceived()}: a connector code or no status means the call got
 * no HTTP answer; any HTTP status, error or not, means it did (#873).
 */
class ConsumedResultNoResponseTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1000, 1001, 1002, 1099})
    void noResponseReceivedShouldBeTrueForAConnectorCodeOrNoStatus(int status) {
        assertTrue(ConsumedResult.noResponseReceived(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 304, 400, 404, 500, 502, 503, 599})
    void noResponseReceivedShouldBeFalseForAnHttpStatus(int status) {
        assertFalse(ConsumedResult.noResponseReceived(status));
    }

    @Test
    void noResponseReceivedShouldBeTrueForAConnectionFailureOfTheHttpClient() {
        Response response = new Response(new Request());
        response.setStatus(Status.CONNECTOR_ERROR_COMMUNICATION);

        assertTrue(new HttpConsumedResult(null, response).noResponseReceived());
    }
}
