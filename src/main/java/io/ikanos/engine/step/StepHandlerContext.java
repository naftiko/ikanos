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
package io.ikanos.engine.step;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable execution context provided to a {@link StepHandler}.
 */
public interface StepHandlerContext {

    /** All input parameters for the current operation invocation. */
    Map<String, Object> inputParameters();

    /** Outputs from all previously executed steps, keyed by step name. */
    Map<String, JsonNode> stepOutputs();

    /** Values from the 'with' block (if the step spec has one), after resolution. */
    Map<String, Object> withValues();

    /** Convenience: get a specific step output by step name. */
    JsonNode stepOutput(String stepName);

    /** Convenience: get a specific input parameter. */
    Object inputParameter(String name);
}
