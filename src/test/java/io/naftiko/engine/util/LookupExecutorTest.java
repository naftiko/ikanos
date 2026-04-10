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
package io.naftiko.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class LookupExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void executeLookupShouldProjectMatchingArrayEntry() throws Exception {
        JsonNode indexData = MAPPER.readTree("""
                [
                  {"email":"alice@example.com","id":"u-1","active":true},
                  {"email":"bob@example.com","id":"u-2","active":false}
                ]
                """);

        JsonNode result = LookupExecutor.executeLookup(indexData, "email", "bob@example.com",
                List.of("id", "active", "missing"));

        assertNotNull(result);
        assertEquals("u-2", result.path("id").asText());
        assertEquals(false, result.path("active").asBoolean());
        assertEquals(true, result.path("missing").isNull());
    }

    @Test
    public void executeLookupShouldProjectMatchingObject() throws Exception {
        JsonNode indexData = MAPPER.readTree("""
                {"email":"alice@example.com","name":"Alice","team":"core"}
                """);

        JsonNode result = LookupExecutor.executeLookup(indexData, "email", "alice@example.com",
                List.of("name", "team"));

        assertNotNull(result);
        assertEquals("Alice", result.path("name").asText());
        assertEquals("core", result.path("team").asText());
    }

    @Test
    public void executeLookupShouldReturnNullWhenNoMatchExists() throws Exception {
        JsonNode indexData = MAPPER.readTree("""
                [{"email":"alice@example.com","id":"u-1"}]
                """);

        JsonNode result = LookupExecutor.executeLookup(indexData, "email", "nobody@example.com",
                List.of("id"));

        assertNull(result);
    }

    @Test
    public void mergeLookupResultShouldConvertScalarTypesAndPreserveComplexNodes()
            throws Exception {
        JsonNode result = MAPPER.readTree("""
                {
                  "name": "Alice",
                  "age": 42,
                  "enabled": true,
                  "nickname": null,
                  "meta": {"region":"eu"}
                }
                """);
        Map<String, Object> target = new LinkedHashMap<>();

        LookupExecutor.mergeLookupResult(result, target);

        assertEquals("Alice", target.get("name"));
        assertEquals(42, ((Number) target.get("age")).intValue());
        assertEquals(true, target.get("enabled"));
        assertNull(target.get("nickname"));
        assertInstanceOf(ObjectNode.class, target.get("meta"));
        assertEquals("eu", ((JsonNode) target.get("meta")).path("region").asText());
    }
}