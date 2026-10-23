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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.type.TypeFactory;

class McpToolInputSchemaTest {

    private static final ObjectMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();

    private static List<InputParameterSpec> params(String yaml) throws Exception {
        return YAML.readValue(yaml, TypeFactory.createDefaultInstance()
                .constructCollectionType(List.class, InputParameterSpec.class));
    }

    private static JsonNode schemaOf(String yaml) throws Exception {
        Map<String, Object> schema = McpToolInputSchema.forParameters(params(yaml));
        return JSON.valueToTree(schema);
    }

    @Test
    void forParametersShouldKeepScalarShapeUnchanged() throws Exception {
        JsonNode schema = schemaOf("""
                - name: id
                  type: string
                  description: "Identifier."
                - name: limit
                  type: integer
                  description: "Limit."
                  required: false
                """);

        assertEquals(JSON.readTree("""
                {"type":"object",
                 "properties":{
                   "id":{"type":"string","description":"Identifier."},
                   "limit":{"type":"integer","description":"Limit."}},
                 "required":["id"]}
                """), schema);
    }

    @Test
    void forParametersShouldEmitItemsOfArrayOfObjects() throws Exception {
        JsonNode schema = schemaOf("""
                - name: fees
                  type: array
                  description: "Fees."
                  items:
                    type: object
                    properties:
                      add_on_code: { type: string, description: "Add-on code." }
                      units: { type: number, minimum: 0 }
                    required: [add_on_code, units]
                    additionalProperties: false
                """);

        assertEquals(JSON.readTree("""
                {"type":"array","description":"Fees.",
                 "items":{"type":"object",
                   "properties":{
                     "add_on_code":{"type":"string","description":"Add-on code."},
                     "units":{"type":"number","minimum":0}},
                   "required":["add_on_code","units"],
                   "additionalProperties":false}}
                """), schema.path("properties").path("fees"));
    }

    @Test
    void forParametersShouldMapRequiredPropertiesToNestedRequired() throws Exception {
        JsonNode schema = schemaOf("""
                - name: customer
                  type: object
                  description: "Customer."
                  required: false
                  properties:
                    external_id: { type: string }
                    country: { type: string, enum: [FR, US] }
                  requiredProperties: [external_id]
                  additionalProperties: true
                """);

        assertEquals(JSON.readTree("""
                {"type":"object","description":"Customer.",
                 "properties":{
                   "external_id":{"type":"string"},
                   "country":{"type":"string","enum":["FR","US"]}},
                 "required":["external_id"],
                 "additionalProperties":true}
                """), schema.path("properties").path("customer"));
        assertFalse(schema.has("required"), "customer is optional at tool level");
    }

    @Test
    void forParametersShouldEmitEnumFormatMinimumAndMaximum() throws Exception {
        JsonNode schema = schemaOf("""
                - name: status
                  type: string
                  description: "Status."
                  enum: [draft, finalized]
                - name: issued_after
                  type: string
                  description: "Date."
                  format: date
                - name: page_size
                  type: integer
                  description: "Page size."
                  minimum: 1
                  maximum: 100
                - name: ratio
                  type: number
                  description: "Ratio."
                  maximum: 0.5
                """).path("properties");

        assertEquals(JSON.readTree("[\"draft\",\"finalized\"]"), schema.path("status").path("enum"));
        assertEquals("date", schema.path("issued_after").path("format").asString());
        assertEquals(JSON.readTree("1"), schema.path("page_size").path("minimum"));
        assertEquals(JSON.readTree("100"), schema.path("page_size").path("maximum"));
        assertEquals(JSON.readTree("0.5"), schema.path("ratio").path("maximum"));
    }

    @Test
    void forParametersShouldEmitArrayOfStrings() throws Exception {
        JsonNode schema = schemaOf("""
                - name: tags
                  type: array
                  description: "Tags."
                  items: { type: string, enum: [a, b] }
                """);

        assertEquals(JSON.readTree("{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}"),
                schema.path("properties").path("tags").path("items"));
    }
}
