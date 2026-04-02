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
package io.naftiko.spec;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Custom deserializer for InputParameterSpec that handles nested structure definitions including
 * properties, items, and values in a polymorphic manner.
 */
public class InputParameterDeserializer extends JsonDeserializer<InputParameterSpec> {

    @Override
    public InputParameterSpec deserialize(JsonParser parser, DeserializationContext cxt)
            throws IOException, JsonProcessingException {
        JsonNode node = parser.getCodec().readTree(parser);
        return deserializeNode(node, cxt);
    }

    /**
     * Recursively deserialize a JsonNode into InputParameterSpec.
     */
    private InputParameterSpec deserializeNode(JsonNode node, DeserializationContext cxt)
            throws IOException {
        InputParameterSpec spec = new InputParameterSpec();

        if (node.has("name")) {
            spec.setName(node.get("name").asText());
        }

        if (node.has("type")) {
            spec.setType(node.get("type").asText());
        }

        if (node.has("in")) {
            spec.setIn(node.get("in").asText());
        }

        if (node.has("template")) {
            spec.setTemplate(node.get("template").asText());
        }

        if (node.has("const")) {
            spec.setConstant(node.get("const").asText());
        }

        if (node.has("value")) {
            spec.setValue(node.get("value").asText());
        }

        if (node.has("selector")) {
            spec.setSelector(node.get("selector").asText());
        }

        if (node.has("description")) {
            spec.setDescription(node.get("description").asText());
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
                        throw new RuntimeException(
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
                requiredNode.forEach(item -> spec.getRequired().add(item.asText()));
            }
        }

        // Handle "enum" array
        if (node.has("enum")) {
            JsonNode enumNode = node.get("enum");
            if (enumNode.isArray()) {
                enumNode.forEach(item -> spec.getEnumeration().add(item.asText()));
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
            spec.setMaxLength(node.get("maxLength").asText());
        }

        if (node.has("contentEncoding")) {
            spec.setContentEncoding(node.get("contentEncoding").asText());
        }

        if (node.has("contentCompression")) {
            spec.setContentCompression(node.get("contentCompression").asText());
        }

        if (node.has("contentMediaType")) {
            spec.setContentMediaType(node.get("contentMediaType").asText());
        }

        return spec;
    }

}
