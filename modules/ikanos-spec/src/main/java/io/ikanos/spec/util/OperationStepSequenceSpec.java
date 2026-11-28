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

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * A {@code sequence} step: a short list of steps that run in order, where a single step is
 * expected, such as a track of a {@code parallel} step. Its output is the output of the last of
 * its steps that has one.
 */
public class OperationStepSequenceSpec extends OperationStepSpec implements NestedSteps {

    @JsonProperty("steps")
    @JsonDeserialize(using = OperationStepMapDeserializer.class)
    @JsonSerialize(using = OperationStepListSerializer.class)
    private final Map<String, OperationStepSpec> steps = StepOrder.newSteps();

    public OperationStepSequenceSpec() {
        this(null);
    }

    public OperationStepSequenceSpec(String name) {
        super("sequence", name);
    }

    public Map<String, OperationStepSpec> getSteps() {
        return steps;
    }

    public void setSteps(Map<String, OperationStepSpec> steps) {
        if (steps == null) {
            return;
        }
        StepOrder.requireOrdered(steps);
        synchronized (this.steps) {
            this.steps.clear();
            this.steps.putAll(steps);
        }
    }

    @Override
    public Map<String, OperationStepSpec> nestedSteps() {
        return steps;
    }
}
