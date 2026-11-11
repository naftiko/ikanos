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
package io.ikanos.spec;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads {@code definitions.inputParameterSets}: a map of set names to maps of input parameters.
 * Each parameter map is read like a tool's {@code inputParameters}: the key becomes the
 * parameter name.
 */
public class InputParameterSetsDeserializer
        extends ValueDeserializer<Map<String, Map<String, InputParameterSpec>>> {

    @Override
    public Map<String, Map<String, InputParameterSpec>> deserialize(JsonParser parser,
            DeserializationContext ctx) {
        JsonNode root = parser.objectReadContext().readTree(parser);
        Map<String, Map<String, InputParameterSpec>> sets = new LinkedHashMap<>();
        if (root == null || !root.isObject()) {
            return sets;
        }
        root.properties().forEach(set -> {
            Map<String, InputParameterSpec> params = new LinkedHashMap<>();
            if (set.getValue() != null && set.getValue().isObject()) {
                set.getValue().properties().forEach(param -> {
                    InputParameterSpec spec =
                            ctx.readTreeAsValue(param.getValue(), InputParameterSpec.class);
                    spec.setName(param.getKey());
                    params.put(param.getKey(), spec);
                });
            }
            sets.put(set.getKey(), params);
        });
        return sets;
    }
}
