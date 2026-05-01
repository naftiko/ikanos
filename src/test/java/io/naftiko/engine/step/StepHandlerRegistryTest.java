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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.naftiko.engine.util.StepExecutionContext;

class StepHandlerRegistryTest {

    private StepHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StepHandlerRegistry();
    }

    @Test
    void registerShouldStoreHandler() {
        StepHandler handler = ctx -> TextNode.valueOf("ok");
        registry.register("my-step", handler);

        assertTrue(registry.has("my-step"));
        assertTrue(registry.get("my-step").isPresent());
    }

    @Test
    void getShouldReturnEmptyForUnknownStep() {
        assertFalse(registry.get("unknown").isPresent());
    }

    @Test
    void hasShouldReturnFalseForUnknownStep() {
        assertFalse(registry.has("unknown"));
    }

    @Test
    void registerShouldOverwriteExistingHandler() {
        StepHandler first = ctx -> TextNode.valueOf("first");
        StepHandler second = ctx -> TextNode.valueOf("second");

        registry.register("step", first);
        registry.register("step", second);

        assertSame(second, registry.get("step").orElse(null));
    }

    @Test
    void unregisterShouldRemoveHandler() {
        registry.register("step", ctx -> TextNode.valueOf("x"));
        registry.unregister("step");

        assertFalse(registry.has("step"));
    }

    @Test
    void registerShouldThrowOnNullStepName() {
        StepHandler handler = ctx -> null;
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(null, handler));
    }

    @Test
    void registerShouldThrowOnBlankStepName() {
        StepHandler handler = ctx -> null;
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("  ", handler));
    }

    @Test
    void registerShouldThrowOnNullHandler() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("step", null));
    }

    @Test
    void executeHandlerShouldDelegateToRegisteredHandler() {
        ObjectMapper mapper = new ObjectMapper();
        StepHandler handler = ctx -> {
            String input = (String) ctx.inputParameter("name");
            return TextNode.valueOf("hello " + input);
        };
        registry.register("greet", handler);

        Map<String, Object> params = new HashMap<>();
        params.put("name", "world");
        StepExecutionContext stepContext = new StepExecutionContext();

        JsonNode result = registry.executeHandler("greet", params, stepContext, null);

        assertEquals("hello world", result.asText());
    }

    @Test
    void executeHandlerShouldThrowWhenNoHandlerRegistered() {
        assertThrows(IllegalStateException.class,
                () -> registry.executeHandler("missing", new HashMap<>(),
                        new StepExecutionContext(), null));
    }
}
