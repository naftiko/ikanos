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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;

/**
 * Unit tests for {@link RestletHeaderGetter} — W3C traceparent extraction from Restlet requests.
 */
public class RestletHeaderGetterTest {

    @Test
    void getShouldReturnHeaderValueCaseInsensitively() {
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().add("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        String value = RestletHeaderGetter.INSTANCE.get(request, "traceparent");
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", value);
    }

    @Test
    void getShouldReturnNullForMissingHeader() {
        Request request = new Request(Method.GET, "http://localhost/test");

        assertNull(RestletHeaderGetter.INSTANCE.get(request, "traceparent"));
    }

    @Test
    void getShouldReturnNullForNullRequest() {
        assertNull(RestletHeaderGetter.INSTANCE.get(null, "traceparent"));
    }

    @Test
    @SuppressWarnings("null") // deliberately passing null to @Nonnull param
    void getShouldReturnNullForNullKey() {
        Request request = new Request(Method.GET, "http://localhost/test");
        assertNull(RestletHeaderGetter.INSTANCE.get(request, null));
    }

    @Test
    void keysShouldReturnAllHeaderNames() {
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().add("traceparent", "value1");
        request.getHeaders().add("tracestate", "value2");

        Iterable<String> keys = RestletHeaderGetter.INSTANCE.keys(request);
        assertNotNull(keys);

        List<String> keyList = new ArrayList<>();
        keys.forEach(keyList::add);
        assertTrue(keyList.size() >= 2, "Should return at least the 2 added headers");
    }

    @Test
    void keysShouldReturnEmptyForNullRequest() {
        Iterable<String> keys = RestletHeaderGetter.INSTANCE.keys(null);
        assertFalse(keys.iterator().hasNext());
    }
}
