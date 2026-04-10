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
package io.naftiko.engine.util;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Executor for lookup step operations.
 * 
 * Handles cross-reference matching and field extraction from step outputs.
 */
public class LookupExecutor {

    /**
     * Execute a lookup operation against a previous step's output.
     * 
     * Performs matching based on a key field and returns matching entries
     * with only the specified output fields.
     * 
     * @param indexData The output data from the previous (index) step
     * @param matchField The field name to match against (e.g., "email")
     * @param lookupValue The value to match (typically a JsonPath-resolved value)
     * @param outputFields Fields to extract from the matched entry
     * @return A JsonNode containing the matched entry with only the specified fields,
     *         or null if no match found
     */
    public static JsonNode executeLookup(JsonNode indexData, String matchField, 
            String lookupValue, List<String> outputFields) {
        
        if (indexData == null || matchField == null || lookupValue == null) {
            return null;
        }

        // Handle array of entries (most common case)
        if (indexData.isArray()) {
            return lookupInArray(indexData, matchField, lookupValue, outputFields);
        }

        // Handle single object
        if (indexData.isObject()) {
            return lookupInObject(indexData, matchField, lookupValue, outputFields);
        }

        return null;
    }

    /**
     * Perform lookup in an array of objects/entries.
     */
    private static JsonNode lookupInArray(JsonNode arrayNode, String matchField, 
            String lookupValue, List<String> outputFields) {
        
        Iterator<JsonNode> elements = arrayNode.elements();
        while (elements.hasNext()) {
            JsonNode entry = elements.next();

            if (entry.isObject()) {
                JsonNode matchNodeValue = entry.get(matchField);

                // Compare match field value with lookup value
                if (matchNodeValue != null && 
                    lookupValue.equals(matchNodeValue.asText())) {
                    
                    // Found a match - extract specified fields
                    return extractFields(entry, outputFields);
                }
            }
        }

        return null; // No match found
    }

    /**
     * Perform lookup in a single object.
     */
    private static JsonNode lookupInObject(JsonNode objNode, String matchField, 
            String lookupValue, List<String> outputFields) {
        
        JsonNode matchNodeValue = objNode.get(matchField);

        if (matchNodeValue != null && 
            lookupValue.equals(matchNodeValue.asText())) {
            
            return extractFields(objNode, outputFields);
        }

        return null; // No match
    }

    /**
     * Extract specified fields from an entry.
     */
    private static ObjectNode extractFields(JsonNode entry, List<String> fieldNames) {
        if (entry == null || !entry.isObject() || fieldNames == null || fieldNames.isEmpty()) {
            return null;
        }

        ObjectNode result = JsonNodeFactory.instance.objectNode();

        for (String fieldName : fieldNames) {
            JsonNode fieldValue = entry.get(fieldName);
            if (fieldValue != null) {
                result.set(fieldName, fieldValue);
            } else {
                result.putNull(fieldName);
            }
        }

        return result;
    }

    /**
     * Merge lookup result into a context map for use in subsequent steps.
     * 
     * @param result The lookup result (an ObjectNode with extracted fields)
     * @param targetMap The map to merge the result into (output of the lookup step)
     */
    public static void mergeLookupResult(JsonNode result, Map<String, Object> targetMap) {
        if (result == null || !result.isObject() || targetMap == null) {
            return;
        }

        Iterator<String> fieldNames = result.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode fieldValue = result.get(fieldName);
            
            if (fieldValue.isNull()) {
                targetMap.put(fieldName, null);
            } else if (fieldValue.isTextual()) {
                targetMap.put(fieldName, fieldValue.asText());
            } else if (fieldValue.isNumber()) {
                targetMap.put(fieldName, fieldValue.numberValue());
            } else if (fieldValue.isBoolean()) {
                targetMap.put(fieldName, fieldValue.asBoolean());
            } else {
                // For complex types, keep as JsonNode
                targetMap.put(fieldName, fieldValue);
            }
        }
    }

}
