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
package io.naftiko.engine.step;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;

class StepHandlerContextTest {

    @Test
    void inputParametersShouldBeAccessible() {
        Map<String, Object> params = new HashMap<>();
        params.put("key", "value");

        StepHandlerContext ctx = new DefaultStepHandlerContext(params, null, null);

        assertEquals("value", ctx.inputParameters().get("key"));
    }

    @Test
    void stepOutputsShouldBeAccessible() {
        Map<String, JsonNode> outputs = new HashMap<>();
        outputs.put("step1", TextNode.valueOf("output1"));

        StepHandlerContext ctx = new DefaultStepHandlerContext(null, outputs, null);

        assertEquals("output1", ctx.stepOutputs().get("step1").asText());
    }

    @Test
    void withValuesShouldBeAccessible() {
        Map<String, Object> withVals = new HashMap<>();
        withVals.put("timeout", 30);

        StepHandlerContext ctx = new DefaultStepHandlerContext(null, null, withVals);

        assertEquals(30, ctx.withValues().get("timeout"));
    }

    @Test
    void convenienceMethodsShouldWork() {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "test");
        Map<String, JsonNode> outputs = new HashMap<>();
        outputs.put("prev", IntNode.valueOf(42));

        StepHandlerContext ctx = new DefaultStepHandlerContext(params, outputs, null);

        assertEquals("test", ctx.inputParameter("name"));
        assertEquals(42, ctx.stepOutput("prev").asInt());
    }

    @Test
    void inputParametersShouldBeImmutable() {
        Map<String, Object> params = new HashMap<>();
        params.put("key", "value");

        StepHandlerContext ctx = new DefaultStepHandlerContext(params, null, null);

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.inputParameters().put("new", "val"));
    }

    @Test
    void stepOutputsShouldBeImmutable() {
        Map<String, JsonNode> outputs = new HashMap<>();
        outputs.put("step1", TextNode.valueOf("x"));

        StepHandlerContext ctx = new DefaultStepHandlerContext(null, outputs, null);

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.stepOutputs().put("new", TextNode.valueOf("y")));
    }

    @Test
    void withValuesShouldBeImmutable() {
        Map<String, Object> withVals = new HashMap<>();
        withVals.put("k", "v");

        StepHandlerContext ctx = new DefaultStepHandlerContext(null, null, withVals);

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.withValues().put("new", "val"));
    }

    @Test
    void nullMapsShouldReturnEmptyCollections() {
        StepHandlerContext ctx = new DefaultStepHandlerContext(null, null, null);

        assertNotNull(ctx.inputParameters());
        assertTrue(ctx.inputParameters().isEmpty());
        assertNotNull(ctx.stepOutputs());
        assertTrue(ctx.stepOutputs().isEmpty());
        assertNotNull(ctx.withValues());
        assertTrue(ctx.withValues().isEmpty());
    }
}
