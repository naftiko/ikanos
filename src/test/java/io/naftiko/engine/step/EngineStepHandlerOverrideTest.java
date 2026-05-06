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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.naftiko.Capability;
import io.naftiko.engine.util.OperationStepExecutor;
import io.naftiko.spec.util.OperationStepCallSpec;

/**
 * Integration test that verifies step handlers override normal step execution in the engine.
 */
class EngineStepHandlerOverrideTest {

    @Test
    void stepWithRegisteredHandlerShouldExecuteHandlerNotCallPath() {
        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .stepHandler("do-greet", ctx -> {
                    String name = (String) ctx.inputParameter("name");
                    ObjectMapper m = new ObjectMapper();
                    ObjectNode result = m.createObjectNode();
                    result.put("message", "Hello, " + name + "!");
                    return result;
                })
                .build();

        // Execute steps through the OperationStepExecutor
        OperationStepExecutor executor = new OperationStepExecutor(capability);

        OperationStepCallSpec step = new OperationStepCallSpec("do-greet", "placeholder.greet");
        Map<String, Object> params = new HashMap<>();
        params.put("name", "World");

        OperationStepExecutor.StepExecutionResult result =
                executor.executeSteps(List.of(step), params);

        JsonNode output = result.stepContext.getStepOutput("do-greet");
        assertNotNull(output);
        assertEquals("Hello, World!", output.get("message").asText());
    }

    @Test
    void stepWithoutRegisteredHandlerShouldFollowNormalPath() {
        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .stepHandler("do-greet", ctx -> TextNode.valueOf("handled"))
                .build();

        assertFalse(capability.getStepHandlerRegistry().has("other-step"));

        // A step with a different name should NOT be handled by the registry.
        // It will attempt the normal call path, which is expected to fail for
        // the placeholder endpoint.
        OperationStepExecutor executor = new OperationStepExecutor(capability);

        OperationStepCallSpec step = new OperationStepCallSpec("other-step", "placeholder.greet");
        Map<String, Object> params = new HashMap<>();

        assertThrows(IllegalArgumentException.class,
                () -> executor.executeSteps(List.of(step), params));
    }

    @Test
    void handlerReturningNullShouldProduceNoOutput() {
        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .stepHandler("do-greet", ctx -> null)
                .build();

        OperationStepExecutor executor = new OperationStepExecutor(capability);

        OperationStepCallSpec step = new OperationStepCallSpec("do-greet", "placeholder.greet");
        Map<String, Object> params = new HashMap<>();

        OperationStepExecutor.StepExecutionResult result =
                executor.executeSteps(List.of(step), params);

        assertNull(result.stepContext.getStepOutput("do-greet"));
    }

    @Test
    void multipleHandlersShouldExecuteIndependently() {
        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .stepHandler("do-greet", ctx -> TextNode.valueOf("greeted"))
                .stepHandler("add", ctx -> {
                    ObjectMapper m = new ObjectMapper();
                    ObjectNode node = m.createObjectNode();
                    node.put("result", 42);
                    return node;
                })
                .build();

        OperationStepExecutor executor = new OperationStepExecutor(capability);

        OperationStepCallSpec step1 = new OperationStepCallSpec("do-greet", "placeholder.greet");
        OperationStepCallSpec step2 = new OperationStepCallSpec("add", "placeholder.add");

        Map<String, Object> params = new HashMap<>();
        OperationStepExecutor.StepExecutionResult result =
                executor.executeSteps(List.of(step1, step2), params);

        assertEquals("greeted", result.stepContext.getStepOutput("do-greet").asText());
        assertEquals(42, result.stepContext.getStepOutput("add").get("result").asInt());
    }

    @Test
    void handlerOutputShouldFeedIntoSubsequentSteps() {
        Capability capability = Capability.builder()
                .loadFromClasspath("/embedding/embedding-capability.yaml")
                .stepHandler("do-greet", ctx -> {
                    ObjectMapper m = new ObjectMapper();
                    ObjectNode node = m.createObjectNode();
                    node.put("message", "Hello");
                    return node;
                })
                .stepHandler("add", ctx -> {
                    // Verify we can access output from the previous step
                    JsonNode prev = ctx.stepOutput("do-greet");
                    ObjectMapper m = new ObjectMapper();
                    ObjectNode node = m.createObjectNode();
                    node.put("combined", prev.get("message").asText() + " World");
                    return node;
                })
                .build();

        OperationStepExecutor executor = new OperationStepExecutor(capability);

        OperationStepCallSpec step1 = new OperationStepCallSpec("do-greet", "placeholder.greet");
        OperationStepCallSpec step2 = new OperationStepCallSpec("add", "placeholder.add");

        OperationStepExecutor.StepExecutionResult result =
                executor.executeSteps(List.of(step1, step2), new HashMap<>());

        assertEquals("Hello World",
                result.stepContext.getStepOutput("add").get("combined").asText());
    }
}
