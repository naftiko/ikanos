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
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Custom deserializer for InputParameterSpec that handles nested structure definitions including
 * properties, items, and values in a polymorphic manner.
 */
public class InputParameterDeserializer extends ValueDeserializer<InputParameterSpec> {

    @Override
    public InputParameterSpec deserialize(JsonParser parser, DeserializationContext cxt) {
        JsonNode node = parser.objectReadContext().readTree(parser);
        return deserializeNode(node, cxt);
    }

    /**
     * Recursively deserialize a JsonNode into InputParameterSpec.
     */
    private InputParameterSpec deserializeNode(JsonNode node, DeserializationContext cxt) {
        InputParameterSpec spec = new InputParameterSpec();

        if (node.has("name")) {
            spec.setName(node.get("name").asString());
        }

        if (node.has("type")) {
            spec.setType(node.get("type").asString());
        }

        if (node.has("in")) {
            spec.setIn(node.get("in").asString());
        }

        if (node.has("template")) {
            spec.setTemplate(node.get("template").asString());
        }

        if (node.has("const")) {
            spec.setConstant(node.get("const").asString());
        }

        if (node.has("value")) {
            spec.setValue(node.get("value").asString());
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
                InputParameterSpec itemsSpec = deserializeNode(itemsNode.get(0), cxt);
                spec.setItems(itemsSpec);
            } else if (itemsNode.isObject()) {
                InputParameterSpec itemsSpec = deserializeNode(itemsNode, cxt);
                spec.setItems(itemsSpec);
            }
        }

        // Handle nested "values" for map types
        if (node.has("values")) {
            JsonNode valuesNode = node.get("values");
            InputParameterSpec valuesSpec = deserializeNode(valuesNode, cxt);
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

                        // Inject the property name into the property spec
                        if (propNode.isObject()) {
                            ((ObjectNode) propNode).put("name", propName);
                        }

                        InputParameterSpec propSpec = deserializeNode(propNode, cxt);
                        spec.getProperties().add(propSpec);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Error deserializing property: " + entry.getKey(), e);
                    }
                });
            }
        }

        // Handle "required": may be a boolean (per-parameter flag) or an array (JSON Schema
        // object listing which child properties are required).
        if (node.has("required")) {
            JsonNode requiredNode = node.get("required");
            if (requiredNode.isBoolean()) {
                spec.setRequired(requiredNode.asBoolean());
            } else if (requiredNode.isArray()) {
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

        // MCP tool input schemas: object-level required list, format and bounds
        if (node.has("requiredProperties") && node.get("requiredProperties").isArray()) {
            node.get("requiredProperties").forEach(item -> spec.getRequired().add(item.asString()));
        }

        if (node.has("format")) {
            spec.setFormat(node.get("format").asString());
        }

        if (node.has("minimum") && node.get("minimum").isNumber()) {
            spec.setMinimum(node.get("minimum").numberValue());
        }

        if (node.has("maximum") && node.get("maximum").isNumber()) {
            spec.setMaximum(node.get("maximum").numberValue());
        }

        if (node.has("additionalProperties") && node.get("additionalProperties").isBoolean()) {
            spec.setAdditionalProperties(node.get("additionalProperties").asBoolean());
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

}
