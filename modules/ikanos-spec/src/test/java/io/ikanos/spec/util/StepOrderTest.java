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
package io.ikanos.spec.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;

public class StepOrderTest {

    private static Map<String, OperationStepSpec> ordered() {
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("get-voyage", new OperationStepCallSpec("get-voyage", "registry.get-voyage"));
        steps.put("get-ship", new OperationStepCallSpec("get-ship", "registry.get-ship"));
        steps.put("list-crew", new OperationStepCallSpec("list-crew", "registry.list-crew"));
        return steps;
    }

    @Test
    void requireOrderedShouldAcceptAMapWithADefinedOrder() {
        Map<String, OperationStepSpec> steps = ordered();
        assertEquals(steps, StepOrder.requireOrdered(steps));
    }

    @Test
    void requireOrderedShouldAcceptNull() {
        assertNull(StepOrder.requireOrdered(null));
    }

    @Test
    void requireOrderedShouldRejectAHashMap() {
        assertThrows(IllegalArgumentException.class,
                () -> StepOrder.requireOrdered(new HashMap<>(ordered())));
    }

    @Test
    void setStepsShouldKeepTheDeclaredOrderOnEveryStepContainer() {
        AggregateFlowSpec flow = new AggregateFlowSpec();
        McpServerToolSpec tool = new McpServerToolSpec();
        McpServerResourceSpec resource = new McpServerResourceSpec();
        RestServerOperationSpec operation = new RestServerOperationSpec();

        flow.setSteps(ordered());
        tool.setSteps(ordered());
        resource.setSteps(ordered());
        operation.setSteps(ordered());

        List<String> expected = List.of("get-voyage", "get-ship", "list-crew");
        assertEquals(expected, new ArrayList<>(flow.getSteps().keySet()));
        assertEquals(expected, new ArrayList<>(tool.getSteps().keySet()));
        assertEquals(expected, new ArrayList<>(resource.getSteps().keySet()));
        assertEquals(expected, new ArrayList<>(operation.getSteps().keySet()));
    }

    @Test
    void setStepsShouldRejectAMapWithoutADefinedOrder() {
        Map<String, OperationStepSpec> unordered = new HashMap<>(ordered());
        assertThrows(IllegalArgumentException.class,
                () -> new AggregateFlowSpec().setSteps(unordered));
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerToolSpec().setSteps(unordered));
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerResourceSpec().setSteps(unordered));
        assertThrows(IllegalArgumentException.class,
                () -> new RestServerOperationSpec().setSteps(unordered));
    }
}
