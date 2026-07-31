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
package io.ikanos.engine.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class McpMetaTraceContextGetterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getShouldReturnMetaValue() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get_weather",
                    "_meta": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    }
                  }
                }
                """);

        String value = McpMetaTraceContextGetter.INSTANCE.get(request, "traceparent");
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", value);
    }

    @Test
    void getShouldReturnNullForMissingKey() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get_weather",
                    "_meta": {}
                  }
                }
                """);

        assertNull(McpMetaTraceContextGetter.INSTANCE.get(request, "traceparent"));
    }

    @Test
    void getShouldReturnNullWhenMetaAbsent() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "get_weather"
                  }
                }
                """);

        assertNull(McpMetaTraceContextGetter.INSTANCE.get(request, "traceparent"));
    }

    @Test
    void getShouldReturnNullWhenParamsAbsent() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "ping"
                }
                """);

        assertNull(McpMetaTraceContextGetter.INSTANCE.get(request, "traceparent"));
    }

    @Test
    void getShouldReturnNullForNullRequest() {
        assertNull(McpMetaTraceContextGetter.INSTANCE.get(null, "traceparent"));
    }

    @Test
    @SuppressWarnings("null") // deliberately passing null to @Nonnull param
    void getShouldReturnNullForNullKey() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "params": {
                    "_meta": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    }
                  }
                }
                """);

        assertNull(McpMetaTraceContextGetter.INSTANCE.get(request, null));
    }

    @Test
    void getShouldReturnNullWhenMetaValueIsNullNode() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "params": {
                    "_meta": {
                      "traceparent": null
                    }
                  }
                }
                """);

        assertNull(McpMetaTraceContextGetter.INSTANCE.get(request, "traceparent"));
    }

    @Test
    void keysShouldReturnAllMetaKeys() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "params": {
                    "_meta": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                      "tracestate": "vendor=value",
                      "baggage": "key=value"
                    }
                  }
                }
                """);

        Iterable<String> keys = McpMetaTraceContextGetter.INSTANCE.keys(request);
        assertNotNull(keys);

        List<String> keyList = new ArrayList<>();
        keys.forEach(keyList::add);
        assertEquals(3, keyList.size());
        assertTrue(keyList.containsAll(List.of("traceparent", "tracestate", "baggage")));
    }

    @Test
    void keysShouldReturnEmptyForNullRequest() {
        Iterable<String> keys = McpMetaTraceContextGetter.INSTANCE.keys(null);
        assertFalse(keys.iterator().hasNext());
    }

    @Test
    void keysShouldReturnEmptyWhenMetaAbsent() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "params": {
                    "name": "get_weather"
                  }
                }
                """);

        Iterable<String> keys = McpMetaTraceContextGetter.INSTANCE.keys(request);
        assertFalse(keys.iterator().hasNext());
    }

    @Test
    void keysShouldReturnEmptyWhenMetaIsNotAnObject() throws Exception {
        JsonNode request = mapper.readTree("""
                {
                  "params": {
                    "_meta": "not-an-object"
                  }
                }
                """);

        Iterable<String> keys = McpMetaTraceContextGetter.INSTANCE.keys(request);
        assertFalse(keys.iterator().hasNext());
    }
}
