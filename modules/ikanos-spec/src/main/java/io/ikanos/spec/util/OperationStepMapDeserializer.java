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
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Deserializer for a {@code steps:} list.
 *
 * <p>Steps run in the order they are listed, so {@code steps:} is a list whose items carry their
 * own {@code name}. The steps are held in a {@link StepOrder#newSteps() step map} keyed by name, in
 * list order, which is how the engine reads them. A step without a name, two steps with the same
 * name, and the earlier map form keyed by step name are refused with a message that says how to
 * fix the document.</p>
 */
public class OperationStepMapDeserializer extends ValueDeserializer<Map<String, OperationStepSpec>> {

    /** The fix for a document written before steps became a list. */
    public static final String MIGRATE_HINT =
            "Run 'ikanos migrate <file>' to rewrite the document.";

    @Override
    public Map<String, OperationStepSpec> deserialize(JsonParser parser,
            DeserializationContext ctx) {
        JsonNode root = parser.objectReadContext().readTree(parser);
        Map<String, OperationStepSpec> steps = StepOrder.newSteps();
        if (root == null || root.isNull()) {
            return steps;
        }
        if (root.isObject()) {
            throw new IllegalArgumentException(
                    "'steps' is a list of steps, each with a 'name', not a map keyed by step "
                            + "name. " + MIGRATE_HINT);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("'steps' must be a list of steps.");
        }
        int index = 0;
        for (JsonNode item : root) {
            JsonNode nameNode = item.get("name");
            if (nameNode == null || !nameNode.isString() || nameNode.asString().isBlank()) {
                throw new IllegalArgumentException(
                        "Step " + (index + 1) + " in 'steps' has no 'name'.");
            }
            String name = nameNode.asString();
            if (steps.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Two steps are named '" + name + "': step names must be unique.");
            }
            OperationStepSpec step = ctx.readTreeAsValue(item, OperationStepSpec.class);
            step.setName(name);
            steps.put(name, step);
            index++;
        }
        return steps;
    }
}
