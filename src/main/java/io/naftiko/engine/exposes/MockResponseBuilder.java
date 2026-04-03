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
package io.naftiko.engine.exposes;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.naftiko.spec.OutputParameterSpec;

/**
 * Builds mock JSON responses from outputParameters with const values.
 * Shared by REST and MCP adapters for mock mode (no consumed HTTP adapter).
 */
public class MockResponseBuilder {

    private MockResponseBuilder() {}

    /**
     * Check if a list of output parameters can produce a mock response.
     * Returns true if at least one parameter in the tree has a const value.
     */
    public static boolean canBuildMockResponse(List<? extends OutputParameterSpec> outputParameters) {
        if (outputParameters == null || outputParameters.isEmpty()) {
            return false;
        }

        for (OutputParameterSpec param : outputParameters) {
            if (hasConstValue(param)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Build a JSON object with mock data from outputParameters const values.
     *
     * @return the mock JSON node, or null if no const values found
     */
    public static JsonNode buildMockData(List<? extends OutputParameterSpec> outputParameters,
            ObjectMapper mapper) {
        if (outputParameters == null || outputParameters.isEmpty()) {
            return null;
        }

        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();

        for (OutputParameterSpec param : outputParameters) {
            JsonNode paramValue = buildParameterValue(param, mapper);
            if (paramValue != null && !(paramValue instanceof NullNode)) {
                String fieldName = param.getName() != null ? param.getName() : "value";
                result.set(fieldName, paramValue);
            }
        }

        return result.size() > 0 ? result : null;
    }

    /**
     * Build a JSON node for a single parameter, using const values or structures.
     */
    public static JsonNode buildParameterValue(OutputParameterSpec param, ObjectMapper mapper) {
        if (param == null) {
            return NullNode.instance;
        }

        if (param.getConstant() != null) {
            return mapper.getNodeFactory().textNode(param.getConstant());
        }

        String type = param.getType();

        if ("array".equalsIgnoreCase(type)) {
            com.fasterxml.jackson.databind.node.ArrayNode arrayNode = mapper.createArrayNode();
            OutputParameterSpec items = param.getItems();

            if (items != null) {
                JsonNode itemValue = buildParameterValue(items, mapper);
                if (itemValue != null && !(itemValue instanceof NullNode)) {
                    arrayNode.add(itemValue);
                }
            }

            return arrayNode;
        }

        if ("object".equalsIgnoreCase(type)) {
            com.fasterxml.jackson.databind.node.ObjectNode objectNode = mapper.createObjectNode();

            if (param.getProperties() != null) {
                for (OutputParameterSpec prop : param.getProperties()) {
                    JsonNode propValue = buildParameterValue(prop, mapper);
                    if (propValue != null && !(propValue instanceof NullNode)) {
                        String propName = prop.getName() != null ? prop.getName() : "property";
                        objectNode.set(propName, propValue);
                    }
                }
            }

            return objectNode.size() > 0 ? objectNode : NullNode.instance;
        }

        return NullNode.instance;
    }

    /**
     * Recursively check if a parameter or its nested structure has any const values.
     */
    static boolean hasConstValue(OutputParameterSpec param) {
        if (param == null) {
            return false;
        }

        if (param.getConstant() != null) {
            return true;
        }

        if (param.getProperties() != null) {
            for (OutputParameterSpec prop : param.getProperties()) {
                if (hasConstValue(prop)) {
                    return true;
                }
            }
        }

        if (param.getItems() != null) {
            return hasConstValue(param.getItems());
        }

        return false;
    }
}
