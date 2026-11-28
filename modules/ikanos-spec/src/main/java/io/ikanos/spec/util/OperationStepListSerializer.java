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
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a step map back as the {@code steps:} list it was read from, in run order. Each step
 * carries its {@code name}, as {@link OperationStepMapDeserializer} expects.
 */
public class OperationStepListSerializer extends ValueSerializer<Map<String, OperationStepSpec>> {

    @Override
    public void serialize(Map<String, OperationStepSpec> steps, JsonGenerator gen,
            SerializationContext ctx) {
        List<OperationStepSpec> ordered;
        // A step map locks on itself (StepOrder.newSteps), so copy it under that lock.
        synchronized (steps) {
            ordered = new ArrayList<>(steps.values());
        }
        gen.writeStartArray();
        for (OperationStepSpec step : ordered) {
            ctx.writeValue(gen, step);
        }
        gen.writeEndArray();
    }

    @Override
    public boolean isEmpty(SerializationContext ctx, Map<String, OperationStepSpec> steps) {
        return steps == null || steps.isEmpty();
    }
}
