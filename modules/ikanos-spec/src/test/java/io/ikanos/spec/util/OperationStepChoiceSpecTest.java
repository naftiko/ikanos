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

import java.util.List;
import org.junit.jupiter.api.Test;

public class OperationStepChoiceSpecTest {

    @Test
    void nestedStepsShouldNameUnnamedPathsThenAndElse() {
        OperationStepChoiceSpec choice = new OperationStepChoiceSpec("route");
        choice.setThen(new OperationStepCallSpec(null, "a.b"));
        choice.setElse(new OperationStepCallSpec(null, "a.c"));

        assertEquals(List.of("then", "else"), List.copyOf(choice.nestedSteps().keySet()));
    }

    @Test
    void nestedStepsShouldListBothPathsWhenTheyShareAName() {
        OperationStepChoiceSpec choice = new OperationStepChoiceSpec("route");
        choice.setThen(new OperationStepCallSpec("notify", "a.b"));
        choice.setElse(new OperationStepCallSpec("notify", "a.c"));

        assertEquals(List.of("notify", "notify#else"),
                List.copyOf(choice.nestedSteps().keySet()));
        assertEquals("a.c", ((OperationStepCallSpec) choice.nestedSteps().get("notify#else"))
                .getCall());
    }
}
