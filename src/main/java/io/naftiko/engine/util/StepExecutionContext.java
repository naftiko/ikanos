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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Execution context for orchestrated operation steps.
 * 
 * Maintains the state of executed steps, including their outputs, allowing
 * subsequent lookup steps to reference and cross-reference previous results.
 */
public class StepExecutionContext {

    /**
     * Maps step names to their execution outputs (as JsonNode objects)
     */
    private final Map<String, JsonNode> stepOutputs;

    public StepExecutionContext() {
        this.stepOutputs = new ConcurrentHashMap<>();
    }

    /**
     * Store the output of a completed step for later reference.
     * 
     * @param stepName The name of the step (as declared in step definition)
     * @param output The output result of the step execution
     */
    public void storeStepOutput(String stepName, JsonNode output) {
        if (stepName != null && output != null) {
            stepOutputs.put(stepName, output);
        }
    }

    /**
     * Retrieve the output of a previously executed step.
     * 
     * @param stepName The name of the step to retrieve
     * @return The JsonNode output of the step, or null if not found
     */
    public JsonNode getStepOutput(String stepName) {
        return stepOutputs.get(stepName);
    }

    /**
     * Check if a step has been executed.
     * 
     * @param stepName The name of the step to check
     * @return true if the step output is available
     */
    public boolean hasStepOutput(String stepName) {
        return stepOutputs.containsKey(stepName);
    }

    /**
     * Get all stored step outputs.
     * 
     * @return An immutable view of all step outputs
     */
    public Map<String, JsonNode> getAllStepOutputs() {
        return new HashMap<>(stepOutputs);
    }

    /**
     * Clear all stored step outputs.
     */
    public void clear() {
        stepOutputs.clear();
    }

}
