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
package io.naftiko.engine.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;

/**
 * Unit tests for {@link RestletHeaderSetter} — W3C traceparent injection into Restlet requests.
 */
public class RestletHeaderSetterTest {

    @Test
    void setShouldAddHeaderToRequest() {
        Request request = new Request(Method.GET, "http://localhost/test");

        RestletHeaderSetter.INSTANCE.set(request,
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                request.getHeaders().getFirstValue("traceparent", true));
    }

    @Test
    void setShouldOverwriteExistingHeader() {
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("traceparent", "old-value");

        RestletHeaderSetter.INSTANCE.set(request, "traceparent", "new-value");

        assertEquals("new-value",
                request.getHeaders().getFirstValue("traceparent", true));
    }

    @Test
    void setShouldIgnoreNullRequest() {
        assertDoesNotThrow(() ->
                RestletHeaderSetter.INSTANCE.set(null, "traceparent", "value"));
    }

    @Test
    @SuppressWarnings("null") // deliberately passing null to @Nonnull param
    void setShouldIgnoreNullKey() {
        Request request = new Request(Method.GET, "http://localhost/test");
        assertDoesNotThrow(() ->
                RestletHeaderSetter.INSTANCE.set(request, null, "value"));
    }

    @Test
    @SuppressWarnings("null") // deliberately passing null to @Nonnull param
    void setShouldIgnoreNullValue() {
        Request request = new Request(Method.GET, "http://localhost/test");
        assertDoesNotThrow(() ->
                RestletHeaderSetter.INSTANCE.set(request, "traceparent", null));
    }
}
