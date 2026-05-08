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

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Custom deserializer for OutputParameterSpec that handles nested structure definitions including
 * properties, items, and values in a polymorphic manner.
 */
public class OutputParameterDeserializer extends JsonDeserializer<OutputParameterSpec> {

    @Override
    public OutputParameterSpec deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException, JsonProcessingException {
        JsonNode node = p.getCodec().readTree(p);
        return deserializeNode(node, ctxt);
    }

    /**
     * Recursively deserialize a JsonNode into OutputParameterSpec.
     */
    private OutputParameterSpec deserializeNode(JsonNode node, DeserializationContext ctxt)
            throws IOException {
        OutputParameterSpec spec = new OutputParameterSpec();

        if (node.has("name")) {
            spec.setName(node.get("name").asText());
        }

        if (node.has("type")) {
            spec.setType(node.get("type").asText());
        }

        if (node.has("mapping")) {
            spec.setMapping(node.get("mapping").asText());
        }

        if (node.has("value")) {
            String rawValue = node.get("value").asText();
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
            spec.setConstant(node.get("const").asText());
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
                OutputParameterSpec itemsSpec = deserializeNode(itemsNode.get(0), ctxt);
                spec.setItems(itemsSpec);
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
