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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Handler for a programmatically-registered orchestration step. Implementations receive the step's
 * runtime context and return a result that feeds into subsequent steps or output mappings.
 *
 * <p>Handlers are registered by step name via {@link StepHandlerRegistry} and take precedence over
 * the normal step execution path (call, script, lookup).
 */
public interface StepHandler {

    /**
     * Execute the step logic.
     *
     * @param context immutable view of the execution context — input parameters, prior step
     *        outputs, and configuration values
     * @return result node (object, array, or scalar) to be stored as this step's output, or null if
     *         the step produces no output
     */
    JsonNode execute(StepHandlerContext context);
}
