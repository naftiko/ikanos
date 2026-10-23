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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates the bounded JSON Schema subset allowed on MCP tool input parameters (#852).
 */
class McpToolInputSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpToolInputSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validateTool(String inputParameters) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Nested inputs"
                  description: "Nested MCP tool input schema test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "billing"
                      baseUri: "https://api.billing.test"
                      resources:
                        invoices:
                          path: "/invoices"
                          operations:
                            create-invoice:
                              method: "POST"
                  exposes:
                    - type: "mcp"
                      namespace: "billing-mcp"
                      port: 3001
                      tools:
                        create-invoice:
                          description: "Create an invoice."
                          call: "billing.create-invoice"
                          inputParameters:
                %s
                """.formatted(IKANOS, indent(inputParameters, 12));
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    private static String indent(String block, int spaces) {
        String pad = " ".repeat(spaces);
        StringBuilder out = new StringBuilder();
        for (String line : block.strip().split("\n")) {
            out.append(pad).append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    @Test
    void schemaShouldAcceptArrayOfObjectsWithRequiredProperties() throws Exception {
        List<Error> errors = validateTool("""
                fees:
                  type: "array"
                  description: "Fees to bill."
                  items:
                    type: "object"
                    properties:
                      add_on_code: { type: "string", description: "Add-on code." }
                      units: { type: "number", minimum: 0 }
                      tags: { type: "array", items: { type: "string" } }
                    required: ["add_on_code", "units"]
                    additionalProperties: false
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldAcceptObjectWithPropertiesAndRequiredProperties() throws Exception {
        List<Error> errors = validateTool("""
                customer:
                  type: "object"
                  description: "Customer."
                  required: false
                  properties:
                    external_id: { type: "string" }
                    country: { type: "string", enum: ["FR", "US"] }
                  requiredProperties: ["external_id"]
                  additionalProperties: true
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldAcceptEnumFormatMinimumAndMaximum() throws Exception {
        List<Error> errors = validateTool("""
                status:
                  type: "string"
                  description: "Status."
                  enum: ["draft", "finalized"]
                issued_after:
                  type: "string"
                  description: "Date."
                  format: "date"
                page_size:
                  type: "integer"
                  description: "Page size."
                  minimum: 1
                  maximum: 100
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectRequiredArrayAtParameterLevel() throws Exception {
        List<Error> errors = validateTool("""
                customer:
                  type: "object"
                  description: "Customer."
                  properties:
                    external_id: { type: "string" }
                  required: ["external_id"]
                """);
        assertFalse(errors.isEmpty(),
                "Parameter-level required is a boolean; requiredProperties lists properties");
    }

    @Test
    void schemaShouldRejectBooleanRequiredInsideNestedSchema() throws Exception {
        List<Error> errors = validateTool("""
                fees:
                  type: "array"
                  description: "Fees."
                  items:
                    type: "object"
                    properties:
                      code: { type: "string", required: true }
                """);
        assertFalse(errors.isEmpty(), "Nested required must be an array of property names");
    }

    @Test
    void schemaShouldRejectUnknownKeywordInsideNestedSchema() throws Exception {
        List<Error> errors = validateTool("""
                fees:
                  type: "array"
                  description: "Fees."
                  items:
                    type: "object"
                    $ref: "https://evil.test/schema.json"
                """);
        assertFalse(errors.isEmpty(), "Only the bounded subset is allowed in nested schemas");
    }

    @Test
    void schemaShouldRejectItemsWrittenAsList() throws Exception {
        List<Error> errors = validateTool("""
                tags:
                  type: "array"
                  description: "Tags."
                  items:
                    - type: "string"
                """);
        assertFalse(errors.isEmpty(), "items is a single schema object");
    }

    @Test
    void schemaShouldAcceptNestedInputsOnAggregateFlow() throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Nested inputs"
                  description: "Nested inputs on an aggregate flow"
                capability:
                  aggregates:
                    - display: "Billing"
                      namespace: "billing-domain"
                      flows:
                        create-invoice:
                          description: "Create an invoice."
                          call: "billing.create-invoice"
                          inputParameters:
                            fees:
                              type: "array"
                              description: "Fees."
                              items:
                                type: "object"
                                properties:
                                  code: { type: "string" }
                                required: ["code"]
                  consumes:
                    - type: "http"
                      namespace: "billing"
                      baseUri: "https://api.billing.test"
                      resources:
                        invoices:
                          path: "/invoices"
                          operations:
                            create-invoice:
                              method: "POST"
                  exposes:
                    - type: "mcp"
                      namespace: "billing-mcp"
                      port: 3001
                      tools:
                        create-invoice:
                          ref: "billing-domain.create-invoice"
                """.formatted(IKANOS);
        List<Error> errors = schema.validate(YAML.readTree(yaml));
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }
}
