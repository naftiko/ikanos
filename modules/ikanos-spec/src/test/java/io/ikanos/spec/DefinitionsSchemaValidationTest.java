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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;

/**
 * Schema tests for shared {@code definitions} on MCP adapters and aggregates (#910): where
 * {@code inputParameterSets} and {@code schema:} may appear, and what the schema rejects.
 */
class DefinitionsSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static Schema schema;

    private static final String DEFINITIONS = """
            definitions:
              inputParameterSets:
                odata-list:
                  filter: { type: string, required: false, description: "Filter." }
                  top: { type: integer, required: false, description: "Rows." }
              schemas:
                date-range:
                  type: object
                  properties:
                    from: { type: string, format: date }
                    to: { type: string, format: date }
                  required: [from]
                ship:
                  type: object
                  properties:
                    imo: { type: string }
                    window: { schema: date-range, description: "Window." }
            """;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = DefinitionsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @Test
    void validateShouldAcceptDefinitionsOnMcpAdapterWithEveryUseSite() throws Exception {
        List<Error> errors = schema.validate(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameterSets: [odata-list]
                  inputParameters:
                    period: { schema: date-range, description: "Period.", required: false }
                    periods: { type: array, description: "Periods.", items: { schema: date-range } }
                    filter:
                      type: object
                      description: "Filter."
                      properties:
                        window: { schema: date-range }
                  call: erp.list-orders
                list-ships:
                  description: "List ships."
                  steps:
                    ships: { type: call, call: erp.list-ships }
                  mappings:
                    - target: ships
                      value: "$.ships"
                  outputParameters:
                    ships:
                      type: array
                      items: { schema: ship }
                    flagship: { schema: ship }
                    fleet:
                      type: object
                      properties:
                        lead: { schema: ship }
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldAcceptDefinitionsOnAggregate() throws Exception {
        List<Error> errors = schema.validate(read("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - display: "Orders"
                      namespace: orders
                      definitions:
                        inputParameterSets:
                          key:
                            id: { type: string, description: "ID." }
                      flows:
                        get-order:
                          description: "Get an order."
                          inputParameterSets: [key]
                          call: erp.get-order
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                      tools:
                        get-order:
                          ref: orders.get-order
                """.formatted(IKANOS)));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectKeysBesideSchemaOnAnInput() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameters:
                    period: { schema: date-range, type: object, description: "Period." }
                  call: erp.list-orders
                """));
    }

    @Test
    void validateShouldAcceptDefaultBesideSchemaOnATopLevelInput() throws Exception {
        List<Error> errors = schema.validate(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameters:
                    period:
                      schema: date-range
                      description: "Period."
                      default: { from: "2026-01-01" }
                  call: erp.list-orders
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectDefaultBesideSchemaInANestedSchema() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameters:
                    periods:
                      type: array
                      description: "Periods."
                      items: { schema: date-range, default: { from: "2026-01-01" } }
                  call: erp.list-orders
                """));
    }

    @Test
    void validateShouldRejectRequiredBesideSchemaInANestedSchema() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameters:
                    periods:
                      type: array
                      description: "Periods."
                      items: { schema: date-range, required: [from] }
                  call: erp.list-orders
                """));
    }

    @Test
    void validateShouldRejectQualifiedReference() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameterSets: [other.odata-list]
                  call: erp.list-orders
                """));
    }

    @Test
    void validateShouldRejectDollarRef() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameters:
                    period: { $ref: "#/definitions/schemas/date-range", description: "Period." }
                  call: erp.list-orders
                """));
    }

    @Test
    void validateShouldAcceptSchemaWithMappingAloneInMappedOutput() throws Exception {
        // Whether the schema has paths left to bind is the engine's and Polychro's check
        List<Error> errors = schema.validate(mcp("""
                definitions:
                  schemas:
                    imo: { type: string }
                """, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      properties:
                        imo: { schema: imo, mapping: "$.imo_number" }
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectSchemaInAMappedMcpResourceOutput() throws Exception {
        String resource = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                      resources:
                        flagship:
                          display: "Flagship"
                          description: "The flagship."
                          uri: "data://flagship"
                          call: erp.get-ship
                          outputParameters:
                            - %s
                      tools:
                        get-ship:
                          description: "Get a ship."
                          call: erp.get-ship
                """;
        List<Error> inline = schema.validate(read(resource.formatted(IKANOS,
                "{ type: object, properties: { imo: { type: string, mapping: \"$.imo_number\" } } }")));

        assertTrue(inline.isEmpty(), inline.toString());
        assertRejected(read(resource.formatted(IKANOS,
                "{ schema: ship, mappings: { imo: \"$.imo_number\" } }")));
    }

    @Test
    void validateShouldAcceptSchemaWithMappingsInEveryMappedOutputPosition() throws Exception {
        List<Error> errors = schema.validate(mcp(DEFINITIONS, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                        window.from: "$.start"
                        window.to: "$.end"
                list-ships:
                  description: "List ships."
                  call: erp.list-ships
                  outputParameters:
                    - type: object
                      properties:
                        lead:
                          schema: ship
                          mapping: "$.lead"
                          mappings:
                            imo: "$.imo"
                            window.from: "$.from"
                            window.to: "$.to"
                        ships:
                          type: array
                          mapping: "$.ships"
                          items:
                            schema: ship
                            mappings:
                              imo: "$.imo"
                              window.from: "$.from"
                              window.to: "$.to"
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldAcceptAnItemsPathInMappings() throws Exception {
        List<Error> errors = schema.validate(mcp("""
                definitions:
                  schemas:
                    fleet:
                      type: object
                      properties:
                        ships: { type: array, items: { type: object, properties: { imo: { type: string } } } }
                        tags: { type: array, items: { type: string } }
                """, """
                get-fleet:
                  description: "Get the fleet."
                  call: erp.get-fleet
                  outputParameters:
                    - schema: fleet
                      mappings:
                        ships: "$.ships"
                        ships[].imo: "$.imo"
                        tags: "$.tags"
                        tags[]: "$.label"
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectKeysBesideSchemaAndMappingsInAMappedOutput() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      type: object
                      mappings:
                        imo: "$.imo_number"
                """));
    }

    @Test
    void validateShouldRejectAMappingsValueThatIsNotAJsonPath() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "imo_number"
                """));
    }

    @Test
    void validateShouldRejectAMalformedMappingsPath() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        window..from: "$.start"
                """));
    }

    @Test
    void validateShouldRejectKeysBesideSchemaInAnOutput() throws Exception {
        assertRejected(mcp(DEFINITIONS, """
                list-ships:
                  description: "List ships."
                  steps:
                    ships: { type: call, call: erp.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships"
                  outputParameters:
                    flagship: { schema: ship, type: object }
                """));
    }

    @Test
    void validateShouldRejectUnknownKeyInDefinitions() throws Exception {
        assertRejected(mcp("""
                definitions:
                  outputShapes:
                    ship: { type: object }
                """, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                """));
    }

    @Test
    void validateShouldAcceptInputWithNeitherTypeNorSchemaAsAString() throws Exception {
        // An MCP tool input without type is a string (#986), beside definitions too
        List<Error> errors = schema.validate(mcp(DEFINITIONS, """
                list-orders:
                  description: "List orders."
                  inputParameters:
                    period: { description: "Period." }
                  call: erp.list-orders
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldAcceptInlineOrchestratedOutputOnMcpResource() throws Exception {
        List<Error> errors = schema.validate(mcpResource("result: { type: string }"));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectSchemaInMcpResourceOutput() throws Exception {
        assertRejected(mcpResource("result: { schema: ship }"));
    }

    @Test
    void validateShouldAcceptInlineConsumedMcpToolArgument() throws Exception {
        List<Error> errors = schema.validate(consumedMcpTool("""
                period:
                  type: object
                  description: "Period."
                  properties:
                    from: { type: string }
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectSchemaOnConsumedMcpToolArgument() throws Exception {
        assertRejected(consumedMcpTool("period: { schema: date-range, description: \"P.\" }"));
    }

    @Test
    void validateShouldRejectSchemaNestedInConsumedMcpToolArgument() throws Exception {
        assertRejected(consumedMcpTool("""
                period:
                  type: object
                  description: "Period."
                  properties:
                    window: { schema: date-range }
                """));
    }

    @Test
    void validateShouldRejectSchemaInItemsOfConsumedMcpToolArgument() throws Exception {
        assertRejected(consumedMcpTool("""
                periods:
                  type: array
                  description: "Periods."
                  items:
                    type: object
                    properties:
                      window: { schema: date-range }
                """));
    }

    /** An MCP resource in steps mode, with the given orchestrated outputs. */
    private static JsonNode mcpResource(String outputs) throws Exception {
        return read("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                      resources:
                        fleet:
                          display: "Fleet"
                          description: "The fleet."
                          uri: "data://fleet"
                          steps:
                            ships: { type: call, call: erp.list-ships }
                          mappings:
                            - target: result
                              value: "$.ships"
                          outputParameters:
                %s      tools:
                        get-ship:
                          description: "Get a ship."
                          call: erp.get-ship
                """.formatted(IKANOS, outputs.indent(12)));
    }

    /** A consumed MCP tool with the given arguments. */
    private static JsonNode consumedMcpTool(String inputs) throws Exception {
        return read("""
                ikanos: "%s"
                capability:
                  consumes:
                    - type: mcp
                      namespace: billing
                      endpoint: "https://billing.test/mcp"
                      tools:
                        get-invoice:
                          description: "Retrieve one invoice."
                          inputParameters:
                %s  exposes:
                    - type: mcp
                      port: 3001
                      namespace: billing-tools
                      tools:
                        get-invoice:
                          description: "Retrieve one invoice."
                          call: billing.get-invoice
                """.formatted(IKANOS, inputs.indent(12)));
    }

    private static final String MAPPING_SETS = """
            definitions:
              schemas:
                ship:
                  type: object
                  properties:
                    imo: { type: string }
                    flag: { type: string }
              mappingSets:
                ship-from-registry:
                  imo: "$.imo_number"
                  flag: "$.flag_state"
            """;

    @Test
    void validateShouldAcceptMappingSetsBesideASchemaInEveryMappedOutputPosition()
            throws Exception {
        List<Error> errors = schema.validate(mcp(MAPPING_SETS, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappingSets: [ship-from-registry]
                list-ships:
                  description: "List ships."
                  call: erp.list-ships
                  outputParameters:
                    - type: object
                      properties:
                        lead:
                          schema: ship
                          mapping: "$.lead"
                          mappingSets: [ship-from-registry]
                          mappings:
                            flag: "$.registry.flag"
                        ships:
                          type: array
                          mapping: "$.ships"
                          items: { schema: ship, mappingSets: [ship-from-registry] }
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectAMappingSetValueThatIsNotAJsonPath() throws Exception {
        assertRejected(mcp("""
                definitions:
                  mappingSets:
                    ship-from-registry:
                      imo: "IMO 9321483"
                """, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                """));
    }

    @Test
    void validateShouldRejectAMalformedPathInAMappingSet() throws Exception {
        assertRejected(mcp("""
                definitions:
                  mappingSets:
                    ship-from-registry:
                      "crew..name": "$.name"
                """, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                """));
    }

    @Test
    void validateShouldRejectAnEmptyMappingSet() throws Exception {
        assertRejected(mcp("""
                definitions:
                  mappingSets:
                    ship-from-registry: {}
                """, """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                """));
    }

    @Test
    void validateShouldRejectMappingSetsThatAreNotAListOfBareNames() throws Exception {
        String tool = """
                get-ship:
                  description: "Get a ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappingSets: %s
                """;

        assertRejected(mcp(MAPPING_SETS, tool.formatted("ship-from-registry")));
        assertRejected(mcp(MAPPING_SETS, tool.formatted("[]")));
        assertRejected(mcp(MAPPING_SETS, tool.formatted("[fleet.ship-from-registry]")));
        assertRejected(mcp(MAPPING_SETS, tool.formatted(
                "[ship-from-registry, ship-from-registry]")));
    }

    @Test
    void validateShouldAcceptMappingSetsInAStepMapping() throws Exception {
        List<Error> errors = schema.validate(mcp(MAPPING_SETS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: erp.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry]
                  outputParameters:
                    flagship: { schema: ship }
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectAStepMappingWithAnEmptyMappingSetList() throws Exception {
        assertRejected(mcp(MAPPING_SETS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: erp.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: []
                  outputParameters:
                    flagship: { schema: ship }
                """));
    }

    private static void assertRejected(JsonNode document) {
        List<Error> errors = schema.validate(document);
        assertFalse(errors.isEmpty(), "Expected the document to be rejected");
    }

    private static JsonNode mcp(String definitions, String tools) throws Exception {
        return read("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                %s      tools:
                %s""".formatted(IKANOS, definitions.indent(6), tools.indent(8)));
    }

    private static JsonNode read(String yaml) throws Exception {
        return IkanosMappers.yaml().readTree(yaml);
    }
}
