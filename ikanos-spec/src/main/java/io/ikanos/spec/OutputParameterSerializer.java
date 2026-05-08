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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Custom serializer for OutputParameterSpec that converts nested structure definitions back to
 * YAML-compatible format.
 */
public class OutputParameterSerializer extends JsonSerializer<OutputParameterSpec> {

    @Override
    public void serialize(OutputParameterSpec spec, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        if (spec == null) {
            gen.writeNull();
            return;
        }

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        serializeSpec(spec, node);
        gen.writeTree(node);
    }

    /**
     * Serialize a single OutputParameterSpec into an ObjectNode.
     */
    private void serializeSpec(OutputParameterSpec spec, ObjectNode node) {
        // Write scalar string fields
        if (spec.getName() != null) {
            node.put("name", spec.getName());
        }

        if (spec.getType() != null) {
            node.put("type", spec.getType());
        }

        if (spec.getMapping() != null) {
            // Spec alignment:
            // - ConsumedOutputParameter (named) uses "value"
            // - Mapped output parameters use "mapping"
            if (spec.getName() != null && !spec.getName().isBlank()) {
                node.put("value", spec.getMapping());
            } else {
                node.put("mapping", spec.getMapping());
            }
        }

        // Static runtime value (MappedOutputParameter)
        if (spec.getValue() != null && (spec.getName() == null || spec.getName().isBlank())) {
            node.put("value", spec.getValue());
        }

        if (spec.getConstant() != null) {
            node.put("const", spec.getConstant());
        }

        if (spec.getSelector() != null) {
            node.put("selector", spec.getSelector());
        }

        if (spec.getDescription() != null) {
            node.put("description", spec.getDescription());
        }

        // Write numeric fields
        if (spec.getPrecision() != null) {
            node.put("precision", spec.getPrecision());
        }

        if (spec.getScale() != null) {
            node.put("scale", spec.getScale());
        }

        if (spec.getMaxLength() != null) {
            node.put("maxLength", spec.getMaxLength());
        }

        // Write content fields
        if (spec.getContentEncoding() != null) {
            node.put("contentEncoding", spec.getContentEncoding());
        }

        if (spec.getContentCompression() != null) {
            node.put("contentCompression", spec.getContentCompression());
        }

        if (spec.getContentMediaType() != null) {
            node.put("contentMediaType", spec.getContentMediaType());
        }

        // Serialize enumeration array
        if (!spec.getEnumeration().isEmpty()) {
            ArrayNode enumArray = node.putArray("enum");
            for (String val : spec.getEnumeration()) {
                enumArray.add(val);
            }
        }

        // Serialize required array
        if (!spec.getRequired().isEmpty()) {
            ArrayNode requiredArray = node.putArray("required");
            for (String val : spec.getRequired()) {
                requiredArray.add(val);
            }
        }

        // Serialize examples array
        if (!spec.getExamples().isEmpty()) {
            ArrayNode examplesArray = node.putArray("examples");
            for (String val : spec.getExamples()) {
                examplesArray.add(val);
            }
        }

        // Serialize tuple array
        if (!spec.getTuple().isEmpty()) {
            ArrayNode tupleArray = node.putArray("tuple");
            for (String val : spec.getTuple()) {
                tupleArray.add(val);
            }
        }

        // Serialize nested items
        if (spec.getItems() != null) {
            ObjectNode itemsArray = node.putArray("items").addObject();
            serializeSpec(spec.getItems(), itemsArray);
        }

        // Serialize nested values
        if (spec.getValues() != null) {
            ObjectNode valuesNode = node.putObject("values");
            serializeSpec(spec.getValues(), valuesNode);
        }

        // Serialize properties as map (reverse of deserialization)
        if (!spec.getProperties().isEmpty()) {
            ObjectNode propertiesNode = node.putObject("properties");
            for (OutputParameterSpec prop : spec.getProperties()) {
                String propName = prop.getName();
                if (propName != null) {
                    ObjectNode propNode = propertiesNode.putObject(propName);
                    // Don't write the name again in the property (it's the key)
                    serializeSpecWithoutName(prop, propNode);
                }
            }
        }
    }

    /**
     * Serialize a spec without writing its name field (used for properties).
     */
    private void serializeSpecWithoutName(OutputParameterSpec spec, ObjectNode node) {
        // Skip name since it's the key in the properties map

        if (spec.getType() != null) {
            node.put("type", spec.getType());
        }

        if (spec.getMapping() != null) {
            node.put("mapping", spec.getMapping());
        }

        if (spec.getValue() != null) {
            node.put("value", spec.getValue());
        }

        if (spec.getConstant() != null) {
            node.put("const", spec.getConstant());
        }

        if (spec.getSelector() != null) {
            node.put("selector", spec.getSelector());
        }

        if (spec.getDescription() != null) {
            node.put("description", spec.getDescription());
        }

        if (spec.getPrecision() != null) {
            node.put("precision", spec.getPrecision());
        }

        if (spec.getScale() != null) {
            node.put("scale", spec.getScale());
        }

        if (spec.getMaxLength() != null) {
            node.put("maxLength", spec.getMaxLength());
        }

        if (spec.getContentEncoding() != null) {
            node.put("contentEncoding", spec.getContentEncoding());
        }

        if (spec.getContentCompression() != null) {
            node.put("contentCompression", spec.getContentCompression());
        }

        if (spec.getContentMediaType() != null) {
            node.put("contentMediaType", spec.getContentMediaType());
        }

        if (!spec.getEnumeration().isEmpty()) {
            ArrayNode enumArray = node.putArray("enum");
            for (String val : spec.getEnumeration()) {
                enumArray.add(val);
            }
        }

        if (!spec.getRequired().isEmpty()) {
            ArrayNode requiredArray = node.putArray("required");
            for (String val : spec.getRequired()) {
                requiredArray.add(val);
            }
        }

        if (!spec.getExamples().isEmpty()) {
            ArrayNode examplesArray = node.putArray("examples");
            for (String val : spec.getExamples()) {
                examplesArray.add(val);
            }
        }

        if (!spec.getTuple().isEmpty()) {
            ArrayNode tupleArray = node.putArray("tuple");
            for (String val : spec.getTuple()) {
                tupleArray.add(val);
            }
        }

        if (spec.getItems() != null) {
            ObjectNode itemsArray = node.putArray("items").addObject();
            serializeSpec(spec.getItems(), itemsArray);
        }

        if (spec.getValues() != null) {
            ObjectNode valuesNode = node.putObject("values");
            serializeSpec(spec.getValues(), valuesNode);
        }

        if (!spec.getProperties().isEmpty()) {
            ObjectNode propertiesNode = node.putObject("properties");
            for (OutputParameterSpec prop : spec.getProperties()) {
                String propName = prop.getName();
                if (propName != null) {
                    ObjectNode propNode = propertiesNode.putObject(propName);
                    serializeSpecWithoutName(prop, propNode);
                }
            }
        }
    }

}
