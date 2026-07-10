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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import io.ikanos.engine.util.StepExecutionContext;

/**
 * Registry for programmatically-registered step handlers. The engine consults this registry before
 * executing any step — if a handler is registered for the step's name, it takes precedence over the
 * normal execution path.
 */
public class StepHandlerRegistry {

    private final ConcurrentHashMap<String, StepHandler> handlers = new ConcurrentHashMap<>();

    /** Register a handler for the given step name. */
    public void register(String stepName, StepHandler handler) {
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("Step name must not be null or blank.");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null.");
        }
        handlers.put(stepName, handler);
    }

    /** Look up a handler by step name. */
    public Optional<StepHandler> get(String stepName) {
        return Optional.ofNullable(handlers.get(stepName));
    }

    /** Check if a handler is registered for the given step name. */
    public boolean has(String stepName) {
        return handlers.containsKey(stepName);
    }

    /** Remove a registered handler. */
    public void unregister(String stepName) {
        handlers.remove(stepName);
    }

    /**
     * Build a {@link StepHandlerContext} and execute the registered handler for the given step.
     *
     * @param stepName the step name
     * @param inputParameters the input parameters for the current invocation
     * @param stepContext the step execution context with prior step outputs
     * @param withValues resolved 'with' values (may be null)
     * @return the handler result, or null if the handler returns null
     */
    public JsonNode executeHandler(String stepName, Map<String, Object> inputParameters,
            StepExecutionContext stepContext, Map<String, Object> withValues) {
        StepHandler handler = handlers.get(stepName);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for step: " + stepName);
        }
        StepHandlerContext context = new DefaultStepHandlerContext(
                inputParameters, stepContext.getAllStepOutputs(), withValues);
        return handler.execute(context);
    }
}
