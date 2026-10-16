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

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Custom deserializer for OutputParameterSpec that handles nested structure definitions including
 * properties, items, and values in a polymorphic manner.
 */
public class OutputParameterDeserializer extends ValueDeserializer<OutputParameterSpec> {

    @Override
    public OutputParameterSpec deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = p.objectReadContext().readTree(p);
        return deserializeNode(node, ctxt);
    }

    /**
     * Recursively deserialize a JsonNode into OutputParameterSpec.
     */
    private OutputParameterSpec deserializeNode(JsonNode node, DeserializationContext ctxt) {
        OutputParameterSpec spec = new OutputParameterSpec();

        if (node.has("name")) {
            spec.setName(node.get("name").asString());
        }

        if (node.has("type")) {
            spec.setType(node.get("type").asString());
        }

        if (node.has("mapping")) {
            spec.setMapping(node.get("mapping").asString());
        }

        if (node.has("value")) {
            String rawValue = node.get("value").asString();
            String trimmedValue = rawValue != null ? rawValue.trim() : "";

            // ConsumedOutputParameter uses "value" for JsonPath extraction (name + value
            // starting with $). Aggregate mock functions also use name + value, but with
            // static/template strings — those must stay in setValue().
            if (node.has("name") && trimmedValue.startsWith("$") && !node.has("mapping")) {
                spec.setMapping(rawValue);
            } else {
                spec.setValue(rawValue);
            }
        }

        if (node.has("const")) {
            spec.setConstant(node.get("const").asString());
        }

        if (node.has("selector")) {
            spec.setSelector(node.get("selector").asString());
        }

        if (node.has("description")) {
            spec.setDescription(node.get("description").asString());
        }

        // Handle nested "items" array for array types
        if (node.has("items")) {
            JsonNode itemsNode = node.get("items");
            if (itemsNode.isArray() && itemsNode.size() > 0) {
                OutputParameterSpec itemsSpec = deserializeNode(itemsNode.get(0), ctxt);
                spec.setItems(itemsSpec);
            } else if (isOrchestratedItemsMap(itemsNode)) {
                spec.setItems(deserializeOrchestratedItems(itemsNode, ctxt));
            } else if (itemsNode.isObject()) {
                OutputParameterSpec itemsSpec = deserializeNode(itemsNode, ctxt);
                spec.setItems(itemsSpec);
            }
        }

        // Handle nested "values" for map types
        if (node.has("values")) {
            JsonNode valuesNode = node.get("values");
            OutputParameterSpec valuesSpec = deserializeNode(valuesNode, ctxt);
            spec.setValues(valuesSpec);
        }

        // Handle nested "properties" object for object types
        if (node.has("properties")) {
            JsonNode propertiesNode = node.get("properties");
            if (propertiesNode.isObject()) {
                propertiesNode.properties().forEach(entry -> {
                    try {
                        String propName = entry.getKey();
                        JsonNode propNode = entry.getValue();

                        OutputParameterSpec propSpec = deserializeNode(propNode, ctxt);
                        // Set the name from the property key AFTER deserialization
                        // so the dispatch logic sees the original node content
                        if (propSpec.getName() == null) {
                            propSpec.setName(propName);
                        }
                        spec.getProperties().add(propSpec);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Error deserializing property: " + entry.getKey(), e);
                    }
                });
            }
        }

        // Handle "required" array
        if (node.has("required")) {
            JsonNode requiredNode = node.get("required");
            if (requiredNode.isArray()) {
                requiredNode.forEach(item -> spec.getRequired().add(item.asString()));
            }
        }

        // Handle "enum" array
        if (node.has("enum")) {
            JsonNode enumNode = node.get("enum");
            if (enumNode.isArray()) {
                enumNode.forEach(item -> spec.getEnumeration().add(item.asString()));
            }
        }

        // Handle numeric properties
        if (node.has("precision")) {
            spec.setPrecision(node.get("precision").asInt());
        }

        if (node.has("scale")) {
            spec.setScale(node.get("scale").asInt());
        }

        if (node.has("maxLength")) {
            spec.setMaxLength(node.get("maxLength").asString());
        }

        if (node.has("contentEncoding")) {
            spec.setContentEncoding(node.get("contentEncoding").asString());
        }

        if (node.has("contentCompression")) {
            spec.setContentCompression(node.get("contentCompression").asString());
        }

        if (node.has("contentMediaType")) {
            spec.setContentMediaType(node.get("contentMediaType").asString());
        }

        return spec;
    }

    /**
     * Whether {@code itemsNode} uses the orchestrated items form: a non-empty map of named
     * property declarations ({@code {fullName: {type: string}, role: {type: string}}}) rather than
     * a single parameter declaration ({@code {type: object, properties: {...}}}).
     *
     * <p>The two are told apart by {@code type}: a single declaration has a textual
     * {@code type}, while in the map form every entry is an object — including an entry for a
     * property that happens to be named {@code type}.</p>
     */
    static boolean isOrchestratedItemsMap(JsonNode itemsNode) {
        if (itemsNode == null || !itemsNode.isObject() || itemsNode.isEmpty()) {
            return false;
        }
        JsonNode type = itemsNode.get("type");
        if (type != null && type.isString()) {
            return false;
        }
        for (JsonNode entry : itemsNode) {
            if (!entry.isObject()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deserialize the orchestrated items map form into an {@code object} item whose
     * {@code properties} are the named entries.
     */
    private OutputParameterSpec deserializeOrchestratedItems(JsonNode itemsNode,
            DeserializationContext ctxt) {
        OutputParameterSpec itemsSpec = new OutputParameterSpec();
        itemsSpec.setType("object");
        var fields = itemsNode.properties().iterator();
        while (fields.hasNext()) {
            var entry = fields.next();
            OutputParameterSpec propSpec = deserializeNode(entry.getValue(), ctxt);
            if (propSpec.getName() == null) {
                propSpec.setName(entry.getKey());
            }
            itemsSpec.getProperties().add(propSpec);
        }
        return itemsSpec;
    }

}
