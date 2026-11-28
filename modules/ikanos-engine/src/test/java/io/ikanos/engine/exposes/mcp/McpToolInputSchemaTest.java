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
                  required: true
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
    void forParametersShouldEmitTheDraftFileObjectMarkedXMcpFileForAFileInput()
            throws Exception {
        JsonNode schema = schemaOf("""
                - name: report
                  type: file
                  accept: ["application/pdf", ".csv"]
                  maxSize: 50MiB
                  required: true
                  description: "The report."
                """);

        JsonNode report = schema.path("properties").path("report");
        assertEquals(JSON.readTree("""
                {"type":"object","description":"The report.",
                 "x-mcp-file":{"accept":["application/pdf",".csv"],"maxSize":52428800},
                 "properties":{
                   "id":{"type":"string","minLength":1,"maxLength":128},
                   "name":{"type":"string"},
                   "mimeType":{"type":"string"},
                   "size":{"type":"integer","minimum":0},
                   "digest":{"type":"object",
                     "properties":{"algorithm":{"const":"sha-256"},"value":{"type":"string"}},
                     "required":["algorithm","value"]},
                   "data":{"type":"string"}},
                 "required":["id","mimeType","size"]}
                """).toString(), report.toString());
        assertEquals(JSON.readTree("[\"report\"]"), schema.path("required"));
    }

    @Test
    void forParametersShouldLeaveTheFileDescriptorEmptyWithoutAcceptOrMaxSize()
            throws Exception {
        JsonNode schema = schemaOf("""
                - name: attachment
                  type: file
                  description: "Any file."
                """);

        assertEquals(JSON.createObjectNode(),
                schema.path("properties").path("attachment").path("x-mcp-file"));
    }

    @Test
    void forParametersShouldLeaveInputOutOfRequiredWhenRequiredIsNotDeclared() throws Exception {
        JsonNode schema = schemaOf("""
                - name: team
                  type: string
                  description: "Team key."
                """);

        assertFalse(schema.has("required"), "#977: an input is optional by default");
    }

    @Test
    void forParametersShouldListInputInRequiredWhenRequiredIsTrue() throws Exception {
        JsonNode schema = schemaOf("""
                - name: id
                  type: string
                  required: true
                  description: "Issue ID."
                - name: cursor
                  type: string
                  description: "Cursor of the next page."
                """);

        assertEquals(JSON.readTree("[\"id\"]"), schema.path("required"));
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
    void forParametersShouldEmitExamplesWithTheParameterType() throws Exception {
        JsonNode schema = schemaOf("""
                - name: severity
                  type: string
                  description: "Severity."
                  examples: [sev2]
                - name: page_size
                  type: integer
                  description: "Page size."
                  examples: [25, 50]
                - name: urgent
                  type: boolean
                  description: "Urgent."
                  examples: [true]
                """).path("properties");

        assertEquals("[\"sev2\"]", JSON.writeValueAsString(schema.path("severity").path("examples")));
        assertEquals("[25,50]", JSON.writeValueAsString(schema.path("page_size").path("examples")));
        assertEquals("[true]", JSON.writeValueAsString(schema.path("urgent").path("examples")));
    }

    @Test
    void forParametersShouldEmitIntegralDecimalExamplesAsIntegers() throws Exception {
        JsonNode schema = schemaOf("""
                - name: page_size
                  type: integer
                  description: "Page size."
                  examples: [25.0, 50]
                """).path("properties");

        // 25.0 is the integer 25, as default: 25.0 is an integer default. A fractional example
        // such as 2.5 never gets here: the capability refuses to load (InputDefaults).
        assertEquals("[25,50]",
                JSON.writeValueAsString(schema.path("page_size").path("examples")));
    }

    @Test
    void forParametersShouldEmitExamplesBesideADefault() throws Exception {
        JsonNode limit = schemaOf("""
                - name: limit
                  type: integer
                  description: "Page size."
                  default: 20
                  examples: [50]
                """).path("properties").path("limit");

        assertEquals(JSON.readTree("20"), limit.path("default"));
        assertEquals("[50]", JSON.writeValueAsString(limit.path("examples")));
    }

    @Test
    void forParametersShouldEmitExamplesOnNestedProperties() throws Exception {
        JsonNode schema = schemaOf("""
                - name: labels
                  type: object
                  description: "Labels."
                  properties:
                    team: { type: string, examples: [payments] }
                """);

        assertEquals(JSON.readTree("[\"payments\"]"), schema.path("properties").path("labels")
                .path("properties").path("team").path("examples"));
    }

    @Test
    void forParametersShouldOmitExamplesWhenNoneAreDeclared() throws Exception {
        JsonNode schema = schemaOf("""
                - name: severity
                  type: string
                  description: "Severity."
                """);

        assertFalse(schema.path("properties").path("severity").has("examples"));
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

    @Test
    void forParametersShouldAdvertiseDefaultWithItsJsonType() throws Exception {
        JsonNode properties = schemaOf("""
                - name: period
                  type: string
                  description: "Period."
                  required: false
                  default: LAST_90DAYS
                - name: size
                  type: integer
                  description: "Page size."
                  required: false
                  default: 200
                - name: draft
                  type: boolean
                  description: "Draft."
                  required: false
                  default: true
                - name: tags
                  type: array
                  description: "Tags."
                  required: false
                  items: { type: string }
                  default: [a, b]
                - name: filter
                  type: object
                  description: "Filter."
                  required: false
                  default: { status: open }
                """).path("properties");

        assertEquals(JSON.readTree("\"LAST_90DAYS\""), properties.path("period").path("default"));
        assertEquals(JSON.readTree("200"), properties.path("size").path("default"));
        assertEquals(JSON.readTree("true"), properties.path("draft").path("default"));
        assertEquals(JSON.readTree("[\"a\",\"b\"]"), properties.path("tags").path("default"));
        assertEquals(JSON.readTree("{\"status\":\"open\"}"),
                properties.path("filter").path("default"));
    }

    @Test
    void forParametersShouldNotAdvertiseDefaultWhenNoneIsDeclared() throws Exception {
        JsonNode properties = schemaOf("""
                - name: size
                  type: integer
                  description: "Page size."
                  required: false
                """).path("properties");

        assertFalse(properties.path("size").has("default"), properties.toString());
    }
}
