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
package io.ikanos.engine.exposes.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.ikanos.spec.InputParameterSpec;

/**
 * Builds the JSON Schema advertised as a tool's {@code inputSchema} in {@code tools/list} from its
 * declared input parameters.
 */
final class McpToolInputSchema {

    private McpToolInputSchema() {}

    static Map<String, Object> forParameters(List<InputParameterSpec> inputParams) {
        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (inputParams != null) {
            for (InputParameterSpec param : inputParams) {
                schemaProperties.put(param.getName(), propertySchema(param));
                if (param.isRequired()) {
                    required.add(param.getName());
                }
            }
        }

        // Keys are only emitted when present, so a tool without parameters stays {"type":"object"}.
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        if (!schemaProperties.isEmpty()) {
            inputSchema.put("properties", schemaProperties);
        }
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        return inputSchema;
    }

    static Map<String, Object> propertySchema(InputParameterSpec param) {
        Map<String, Object> property = new LinkedHashMap<>();
        String type = param.getType() != null ? param.getType() : "string";
        property.put("type", type);
        if (param.getDescription() != null) {
            property.put("description", param.getDescription());
        }
        if (!param.getEnumeration().isEmpty()) {
            List<Object> values = new ArrayList<>();
            for (String value : param.getEnumeration()) {
                values.add(typedEnumValue(type, value));
            }
            property.put("enum", values);
        }
        if (param.getFormat() != null) {
            property.put("format", param.getFormat());
        }
        if (param.getMinimum() != null) {
            property.put("minimum", param.getMinimum());
        }
        if (param.getMaximum() != null) {
            property.put("maximum", param.getMaximum());
        }
        if (param.getItems() != null) {
            property.put("items", propertySchema(param.getItems()));
        }
        if (!param.getProperties().isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (InputParameterSpec child : param.getProperties()) {
                properties.put(child.getName(), propertySchema(child));
            }
            property.put("properties", properties);
        }
        // Object-level list of required property names (`requiredProperties` on a tool parameter,
        // `required` inside a nested schema). The parameter-level boolean is handled by the caller.
        if (!param.getRequired().isEmpty()) {
            property.put("required", new ArrayList<>(param.getRequired()));
        }
        if (param.getAdditionalProperties() != null) {
            property.put("additionalProperties", param.getAdditionalProperties());
        }
        return property;
    }

    /**
     * Enum values are stored as strings; emit them with the parameter's JSON type so that the
     * advertised schema stays valid for number, integer and boolean parameters.
     */
    private static Object typedEnumValue(String type, String value) {
        try {
            switch (type) {
                case "integer":
                    return new BigInteger(value);
                case "number":
                    return new BigDecimal(value);
                case "boolean":
                    if ("true".equals(value) || "false".equals(value)) {
                        return Boolean.valueOf(value);
                    }
                    return value;
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
