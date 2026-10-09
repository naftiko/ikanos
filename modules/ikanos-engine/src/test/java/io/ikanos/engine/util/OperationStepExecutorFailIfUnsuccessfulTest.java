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
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Status;
import io.ikanos.engine.util.OperationStepExecutor.StepFailedException;

/**
 * Pins the success rule of {@link OperationStepExecutor#failIfUnsuccessful} (#739): only 2xx
 * passes. 3xx and a missing response fail. Keep these cases when porting the check to another
 * response type, so the rule cannot silently become "below 400".
 */
public class OperationStepExecutorFailIfUnsuccessfulTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 299})
    public void shouldPassOn2xx(int code) {
        assertDoesNotThrow(
                () -> OperationStepExecutor.failIfUnsuccessful("step", response(code)));
    }

    @ParameterizedTest
    @ValueSource(ints = {300, 301, 302, 304, 307, 399, 400, 404, 500, 503})
    public void shouldFailOnNon2xxIncluding3xx(int code) {
        StepFailedException error = assertThrows(StepFailedException.class,
                () -> OperationStepExecutor.failIfUnsuccessful("step", response(code)));

        assertEquals("step", error.getStepName());
        assertEquals(code, error.getStatusCode());
        assertEquals("Step 'step' failed with HTTP " + code, error.getMessage());
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
                        response(Status.CONNECTOR_ERROR_CONNECTION.getCode())));

        assertEquals(Status.CONNECTOR_ERROR_CONNECTION.getCode(), error.getStatusCode());
        assertEquals("Step 'step' failed: connector error "
                + Status.CONNECTOR_ERROR_CONNECTION.getCode(), error.getMessage());
    }

    private static Response response(int code) {
        Response response = new Response(new Request());
        response.setStatus(new Status(code));
        return response;
    }
}
