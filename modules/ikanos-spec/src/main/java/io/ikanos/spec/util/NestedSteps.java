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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A step that holds steps of its own, such as the tracks of a {@code parallel} step or the steps
 * of a {@code sequence}.
 */
public interface NestedSteps {

    /** The steps this step holds, by name, in the order they are declared. */
    Map<String, OperationStepSpec> nestedSteps();

    /**
     * Every step of {@code steps}, depth first: each step, then the steps it holds. Code that
     * looks at every step a unit runs, such as the calls it makes or the questions it may ask,
     * walks this list rather than the top-level steps only.
     *
     * @param steps a step list, may be {@code null}
     * @return the steps, never {@code null}
     */
    static List<OperationStepSpec> all(Map<String, OperationStepSpec> steps) {
        List<OperationStepSpec> all = new ArrayList<>();
        collect(steps, all);
        return all;
    }

    private static void collect(Map<String, OperationStepSpec> steps,
            List<OperationStepSpec> all) {
        if (steps == null) {
            return;
        }
        List<OperationStepSpec> ordered;
        synchronized (steps) {
            ordered = new ArrayList<>(steps.values());
        }
        for (OperationStepSpec step : ordered) {
            all.add(step);
            if (step instanceof NestedSteps nested) {
                collect(nested.nestedSteps(), all);
            }
        }
    }
}
