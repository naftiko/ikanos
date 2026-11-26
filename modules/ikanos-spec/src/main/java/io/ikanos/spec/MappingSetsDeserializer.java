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
 * Jackson deserializer for {@code definitions.mappingSets} (#1133): set name to a map of paths
 * to JSONPaths.
 *
 * <p>It reads any shape, so that the definitions expander reports a mistake with its own message
 * instead of a mapping exception: a set that is not a map holds no mapping, a null value stays
 * null, and a value that is not a string keeps its JSON form. Neither is a JSONPath, which the
 * expander rejects where the set is declared.</p>
 */
public class MappingSetsDeserializer extends ValueDeserializer<Map<String, Map<String, String>>> {

    @Override
    public Map<String, Map<String, String>> deserialize(JsonParser parser,
            DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        Map<String, Map<String, String>> sets = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return sets;
        }
        for (var set : node.properties()) {
            Map<String, String> mappings = new LinkedHashMap<>();
            if (set.getValue().isObject()) {
                for (var mapping : set.getValue().properties()) {
                    JsonNode value = mapping.getValue();
                    mappings.put(mapping.getKey(), value.isNull() ? null
                            : value.isString() ? value.asString() : value.toString());
                }
            }
            sets.put(set.getKey(), mappings);
        }
        return sets;
    }
}
