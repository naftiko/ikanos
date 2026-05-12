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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Generic deserializer that converts a JSON/YAML named-object map
 * (where each key is the entity name) into a {@link LinkedHashMap}.
 *
 * <p>After deserializing each value, the map key is injected back into the entity
 * via the provided {@code nameSetter} so that {@code getName()} continues to work
 * in engine code without requiring every call site to be changed.</p>
 *
 * <p>Insertion order is preserved by {@link LinkedHashMap} to match the YAML declaration
 * order, which matters for step orchestration semantics and error messages.</p>
 *
 * <p>Usage — in the owning container's deserializer or via {@code @JsonDeserialize}:</p>
 * <pre>{@code
 *   NamedMapDeserializer<MySpec> d = new NamedMapDeserializer<>(
 *       MySpec.class,
 *       MySpec::setName
 *   );
 * }</pre>
 *
 * @param <T> the type of each named entity
 */
public class NamedMapDeserializer<T> extends JsonDeserializer<Map<String, T>> {

    private final Class<T> type;
    private final BiConsumer<T, String> nameSetter;

    /**
     * @param type       the class of each map value
     * @param nameSetter method reference that injects the map key as the entity name
     */
    public NamedMapDeserializer(Class<T> type, BiConsumer<T, String> nameSetter) {
        this.type = type;
        this.nameSetter = nameSetter;
    }

    @Override
    public Map<String, T> deserialize(JsonParser parser, DeserializationContext ctx)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        Map<String, T> result = new LinkedHashMap<>();
        if (root == null || root.isNull()) {
            return result;
        }
        if (root.isArray()) {
            // Backward-compatibility: accept legacy list format where each item has a "name" field.
            // This allows test fixtures and inline YAML written before the named-map migration to
            // continue working without modification. The name field is used as the map key and is
            // also injected back via nameSetter so that getName() returns consistently.
            for (JsonNode itemNode : root) {
                JsonNode nameNode = itemNode.get("name");
                String key = nameNode != null && !nameNode.isNull() ? nameNode.asText() : null;
                try {
                    T value = ctx.readTreeAsValue(itemNode, type);
                    if (key != null) {
                        nameSetter.accept(value, key);
                        result.put(key, value);
                    } else {
                        // No name field — derive a unique key from index
                        String generatedKey = type.getSimpleName() + "-" + result.size();
                        result.put(generatedKey, value);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to deserialize list item of '"
                                    + type.getSimpleName() + "'", e);
                }
            }
            return result;
        }
        root.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode valueNode = entry.getValue();
            try {
                T value = ctx.readTreeAsValue(valueNode, type);
                nameSetter.accept(value, key);
                result.put(key, value);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to deserialize '" + type.getSimpleName() + "' entry '" + key + "'", e);
            }
        });
        return result;
    }
}
