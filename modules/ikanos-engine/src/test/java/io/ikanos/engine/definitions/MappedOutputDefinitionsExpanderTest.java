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
package io.ikanos.engine.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for schema references in mapped (call-mode) outputs, bound with {@code mappings}
 * by path (#1036): the expansion is the inline mapped output, and every binding mistake fails
 * the load.
 */
class MappedOutputDefinitionsExpanderTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String SHIP = """
            definitions:
              schemas:
                ship-specs:
                  type: object
                  properties:
                    tonnage: { type: integer, description: "Gross tonnage." }
                ship:
                  type: object
                  properties:
                    imo: { type: string, format: imo }
                    specs: { schema: ship-specs }
                    crew:
                      type: array
                      items:
                        type: object
                        properties:
                          fullName: { type: string }
                    tags: { type: array, items: { type: string } }
            """;

    @Test
    void expandAllShouldBindASchemaToTheInlineMappedOutput() throws Exception {
        IkanosSpec bound = mcp(SHIP, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                        specs: "$.specs"
                        specs.tonnage: "$.gross_tonnage"
                        crew: "$.crew"
                        crew[].fullName: "$.name"
                        tags: "$.labels"
                """);
        IkanosSpec inline = mcp("", """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      properties:
                        imo: { type: string, mapping: "$.imo_number" }
                        specs:
                          type: object
                          mapping: "$.specs"
                          properties:
                            tonnage: { type: number, mapping: "$.gross_tonnage" }
                        crew:
                          type: array
                          mapping: "$.crew"
                          items:
                            type: object
                            properties:
                              fullName: { type: string, mapping: "$.name" }
                        tags:
                          type: array
                          mapping: "$.labels"
                          items: { type: string }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(bound);
        DefinitionsExpander.expandAll(inline);

        assertEquals(List.of(), result.errors());
        assertEquals(outputs(inline, "get-ship"), outputs(bound, "get-ship"));
    }

    @Test
    void expandAllShouldBindASchemaUsedAsTheItemsOfAMappedArray() throws Exception {
        IkanosSpec bound = mcp(SHIP, """
                list-ships:
                  description: "List ships."
                  call: erp.list-ships
                  outputParameters:
                    - type: array
                      mapping: "$.ships"
                      items:
                        schema: ship
                        mappings:
                          imo: "$.imo_number"
                          specs.tonnage: "$.gross_tonnage"
                          crew: "$.crew"
                          crew[].fullName: "$.name"
                          tags: "$.labels"
                """);
        IkanosSpec inline = mcp("", """
                list-ships:
                  description: "List ships."
                  call: erp.list-ships
                  outputParameters:
                    - type: array
                      mapping: "$.ships"
                      items:
                        type: object
                        properties:
                          imo: { type: string, mapping: "$.imo_number" }
                          specs:
                            type: object
                            properties:
                              tonnage: { type: number, mapping: "$.gross_tonnage" }
                          crew:
                            type: array
                            mapping: "$.crew"
                            items:
                              type: object
                              properties:
                                fullName: { type: string, mapping: "$.name" }
                          tags:
                            type: array
                            mapping: "$.labels"
                            items: { type: string }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(bound);
        DefinitionsExpander.expandAll(inline);

        assertEquals(List.of(), result.errors());
        assertEquals(outputs(inline, "list-ships"), outputs(bound, "list-ships"));
    }

    @Test
    void expandAllShouldBindASchemaUsedAsAPropertyWithItsOwnMapping() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    owner:
                      type: object
                      properties:
                        name: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      properties:
                        owner:
                          schema: owner
                          mapping: "$.registeredOwner"
                          mappings:
                            name: "$.legalName"
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("{\"type\":\"object\",\"properties\":{\"owner\":{\"type\":\"object\","
                + "\"mapping\":\"$.registeredOwner\",\"properties\":{\"name\":{\"type\":"
                + "\"string\",\"mapping\":\"$.legalName\"}}}}}", outputs(spec, "get-ship"));
    }

    @Test
    void expandAllShouldBindASchemaInACallModeFlow() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: fleet
                      definitions:
                        schemas:
                          ship:
                            type: object
                            properties:
                              imo: { type: string }
                      flows:
                        get-ship:
                          description: "Get ship."
                          call: erp.get-ship
                          outputParameters:
                            - schema: ship
                              mappings:
                                imo: "$.imo_number"
                """.formatted(IKANOS));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);
        AggregateFlowSpec flow = spec.getCapability().getAggregates().values().iterator().next()
                .getFlows().get("get-ship");

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"type\":\"object\",\"properties\":{\"imo\":{\"type\":\"string\","
                + "\"mapping\":\"$.imo_number\"}}}]",
                IkanosMappers.json().writeValueAsString(flow.getOutputParameters()));
    }

    @Test
    void expandAllShouldNotWarnThatASchemaBoundInAMappedOutputIsUnused() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                """);

        assertEquals(List.of(), DefinitionsExpander.expandAll(spec).warnings());
    }

    @Test
    void expandAllShouldRejectAnUnboundLeafOrArray() throws Exception {
        IkanosSpec spec = mcp(SHIP, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' leaves"
                + " paths unbound: [specs.tonnage, crew, crew[].fullName, tags]. Give each scalar,"
                + " array and object without properties a mapping in 'mappings'."), errors);
    }

    @Test
    void expandAllShouldRejectAPathThatTheSchemaDoesNotHave() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                        flag: "$.flag"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' has no"
                + " path [flag]. Its paths are imo."), errors);
    }

    @Test
    void expandAllShouldRejectAValueThatIsNotAJsonPath() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' binds"
                + " [imo] to a value that is not a JSONPath (starting with '$'). A static"
                + " 'value' is declared inline."), errors);
    }

    @Test
    void expandAllShouldRejectAnArraySchemaWithoutAMappingOfItsOwn() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    tags:
                      type: array
                      items: { type: string }
                """, """
                get-tags:
                  description: "Get tags."
                  call: erp.get-tags
                  outputParameters:
                    - type: object
                      properties:
                        tags:
                          schema: tags
                          mappings:
                            "[]": "$.label"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-tags', output, property 'tags':"
                + " 'schema: tags' leaves paths unbound: [(the output itself: set 'mapping'"
                + " beside 'schema:')]. Give each scalar, array and object without properties a"
                + " mapping in 'mappings'."), errors);
    }

    @Test
    void expandAllShouldRequireABindingForAnObjectWithoutProperties() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                        metadata: { type: object }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' leaves"
                + " paths unbound: [metadata]. Give each scalar, array and object without"
                + " properties a mapping in 'mappings'."), errors);
    }

    @Test
    void expandAllShouldBindAnObjectWithoutPropertiesLikeALeaf() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        metadata: { type: object }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        metadata: "$.meta"
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("{\"type\":\"object\",\"properties\":{\"metadata\":{\"type\":\"object\","
                + "\"mapping\":\"$.meta\"}}}", outputs(spec, "get-ship"));
    }

    @Test
    void expandAllShouldRejectAMappingOnObjectItemsThatTheRuntimeNeverReads() throws Exception {
        IkanosSpec spec = mcp(SHIP, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                        specs.tonnage: "$.gross_tonnage"
                        crew: "$.crew"
                        crew[]: "$.member"
                        crew[].fullName: "$.name"
                        tags: "$.labels"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' binds"
                + " [crew[]], which the runtime never reads: object items are read from each"
                + " element, and the items of nested arrays are passed through as they are."
                + " Remove them."), errors);
    }

    @Test
    void expandAllShouldNeitherRequireNorAcceptABindingBelowNestedArrays() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    grid:
                      type: object
                      properties:
                        rows:
                          type: array
                          items:
                            type: array
                            items:
                              type: object
                              properties:
                                value: { type: number }
                """, """
                unbound:
                  description: "Leaves the cells unbound."
                  call: erp.get-grid
                  outputParameters:
                    - schema: grid
                      mappings:
                        rows: "$.rows"
                bound:
                  description: "Binds the cells."
                  call: erp.get-grid
                  outputParameters:
                    - schema: grid
                      mappings:
                        rows: "$.rows"
                        rows[][].value: "$.v"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'bound', output: 'schema: grid' binds"
                + " [rows[][].value], which the runtime never reads: object items are read from"
                + " each element, and the items of nested arrays are passed through as they"
                + " are. Remove them."), errors);
    }

    @Test
    void expandAllShouldRejectAMappingBesideASchemaOnAnOutputObject() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mapping: "$.ship"
                      mappings:
                        imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'mapping' beside"
                + " 'schema: ship' has no effect: an output object is read from the response"
                + " root. Remove it, or move the object under a property."), errors);
    }

    @Test
    void expandAllShouldRejectAnOutputObjectWithoutProperties() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    meta: { type: object }
                """, """
                get-meta:
                  description: "Get metadata."
                  call: erp.get-meta
                  outputParameters:
                    - schema: meta
                      mapping: "$.meta"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-meta', output: 'schema: meta' is an"
                + " object without properties, which as an output always returns an empty"
                + " object. Use it as a property, with a 'mapping'."), errors);
    }

    @Test
    void expandAllShouldBindASchemaWithoutPathsByItsMappingAlone() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    imo: { type: string, format: imo }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      properties:
                        imo:
                          schema: imo
                          mapping: "$.imo_number"
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("{\"type\":\"object\",\"properties\":{\"imo\":{\"type\":\"string\","
                + "\"mapping\":\"$.imo_number\"}}}", outputs(spec, "get-ship"));
    }

    @Test
    void expandAllShouldRejectASchemaBelowInlineNestedArrays() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                list-fleets:
                  description: "List fleets."
                  call: erp.list-fleets
                  outputParameters:
                    - type: array
                      mapping: "$.fleets"
                      items:
                        type: array
                        items:
                          schema: ship
                          mappings:
                            imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'list-fleets', output, items, items:"
                + " 'schema: ship' sits in the items of a nested array, which the runtime passes"
                + " through as they are: its bindings are never read. Remove it, or bind the"
                + " outer items instead."), errors);
    }

    @Test
    void expandAllShouldRejectASchemaInAnMcpResourceOutput() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                      definitions:
                        schemas:
                          ship:
                            type: object
                            properties:
                              imo: { type: string }
                      resources:
                        flagship:
                          description: "The flagship."
                          uri: "data://flagship"
                          call: erp.get-ship
                          outputParameters:
                            - schema: ship
                              mappings:
                                imo: "$.imo_number"
                      tools:
                        get-ship:
                          description: "Get ship."
                          call: erp.get-ship
                """.formatted(IKANOS));

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', resource 'flagship': 'schema:' and"
                + " 'mappings' are not supported in an MCP resource output. Declare the output"
                + " inline."), errors);
    }

    @Test
    void expandAllShouldBindMapFormOutputsOfARefTool() throws Exception {
        // A ref tool's own outputs map its flow's result whatever their form: not a mock
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  ref: fleet.get-ship
                  outputParameters:
                    ship:
                      schema: ship
                      mappings:
                        imo: "$.imo_number"
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("{\"name\":\"ship\",\"type\":\"object\",\"properties\":{\"imo\":{\"type\":"
                + "\"string\",\"mapping\":\"$.imo_number\"}}}", outputs(spec, "get-ship"));
    }

    @Test
    void expandAllShouldRejectMappingsInAMockOutput() throws Exception {
        IkanosSpec spec = mcp("", """
                get-ship:
                  description: "Get ship."
                  outputParameters:
                    ship:
                      type: object
                      mappings:
                        imo: "$.imo_number"
                      properties:
                        imo: { type: string, value: "IMO 9321483" }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output 'ship': 'schema:' and"
                + " 'mappings' are not allowed in a mock output, which returns static values."
                + " Declare the output inline."), errors);
    }

    @Test
    void expandAllShouldRejectASchemaInAMockFlow() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: fleet
                      definitions:
                        schemas:
                          ship:
                            type: object
                            properties:
                              imo: { type: string }
                      flows:
                        get-ship:
                          description: "Get ship."
                          outputParameters:
                            ship:
                              schema: ship
                              mappings:
                                imo: "$.imo_number"
                """.formatted(IKANOS));

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("aggregate 'fleet', flow 'get-ship', output 'ship': 'schema:' and"
                + " 'mappings' are not allowed in a mock output, which returns static values."
                + " Declare the output inline."), errors);
    }

    @Test
    void expandAllShouldBindTheSameAfterAYamlRoundTrip() throws Exception {
        // Imports deep-copy adapters and aggregates through the serializers
        IkanosSpec spec = mcp(SHIP, """
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
                            imo: "$.imo_number"
                            specs.tonnage: "$.gross_tonnage"
                            crew: "$.crew"
                            crew[].fullName: "$.name"
                            tags: "$.labels"
                        ships:
                          type: array
                          mapping: "$.ships"
                          items:
                            schema: ship
                            mappings:
                              imo: "$.imo_number"
                              specs.tonnage: "$.gross_tonnage"
                              crew: "$.crew"
                              crew[].fullName: "$.name"
                              tags: "$.labels"
                """);
        IkanosSpec copy = read(IkanosMappers.yaml().writeValueAsString(spec));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(copy);
        DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals(outputs(spec, "list-ships"), outputs(copy, "list-ships"));
    }

    @Test
    void expandAllShouldBindANamedOutputTheSameAfterAYamlRoundTrip() throws Exception {
        // A named output writes its mapping as `value` unless it references a schema
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    imo: { type: string }
                    tags: { type: array, items: { type: string } }
                """, """
                get-ship:
                  ref: fleet.get-ship
                  outputParameters:
                    imo: { schema: imo, mapping: "$.imo_number" }
                    tags: { schema: tags, mapping: "$.labels" }
                """);
        IkanosSpec copy = read(IkanosMappers.yaml().writeValueAsString(spec));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(copy);
        DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals(outputs(spec, "get-ship"), outputs(copy, "get-ship"));
    }

    @Test
    void expandAllShouldStillRejectMappingsWithoutASchemaAfterAYamlRoundTrip() throws Exception {
        IkanosSpec spec = mcp("", """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      properties:
                        owner:
                          type: object
                          mappings:
                            name: "$.name"
                          properties:
                            name: { type: string, mapping: "$.name" }
                """);
        IkanosSpec copy = read(IkanosMappers.yaml().writeValueAsString(spec));

        List<String> errors = DefinitionsExpander.expandAll(copy).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output, property 'owner':"
                + " 'mappings' binds a schema, so it needs 'schema:' beside it. Name the schema,"
                + " or declare a 'mapping' on each leaf inline."), errors);
    }

    @Test
    void expandAllShouldRejectAKeyBesideASchemaInAMappedOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      type: object
                      mappings:
                        imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' takes"
                + " only 'mappings' and 'mapping' in a mapped output, found [type]."), errors);
    }

    @Test
    void expandAllShouldRejectMappingsWithoutASchema() throws Exception {
        IkanosSpec spec = mcp("", """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      mappings:
                        imo: "$.imo_number"
                      properties:
                        imo: { type: string, mapping: "$.imo" }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'mappings' binds a"
                + " schema, so it needs 'schema:' beside it. Name the schema, or declare a"
                + " 'mapping' on each leaf inline."), errors);
    }

    @Test
    void expandAllShouldRejectMappingsInAnOrchestratedOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  steps:
                    fetch: { type: call, call: erp.get-ship }
                  mappings:
                    - target: ship
                      value: "$.fetch"
                  outputParameters:
                    ship:
                      schema: ship
                      mappings:
                        imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output 'ship': 'schema: ship'"
                + " takes no other key in an output, found [mappings]."), errors);
    }

    @Test
    void expandAllShouldRejectAPropertyThatAPathCannotAddress() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                        "call.sign": { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo_number"
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' has"
                + " properties that a path cannot address, as their name contains '.', '[' or"
                + " ']': [call.sign]. Declare the output inline."), errors);
    }

    @Test
    void expandAllShouldRejectASchemaInAMockOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  outputParameters:
                    ship:
                      schema: ship
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output 'ship': 'schema:' and"
                + " 'mappings' are not allowed in a mock output, which returns static values."
                + " Declare the output inline."), errors);
    }

    @Test
    void expandAllShouldNotRequireAPathForAPropertyTheRuntimeNeverReads() throws Exception {
        // Below nested arrays nothing is read, so a name no path can address is harmless
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    grid:
                      type: object
                      properties:
                        rows:
                          type: array
                          items:
                            type: array
                            items:
                              type: object
                              properties:
                                "cell.value": { type: string }
                """, """
                get-grid:
                  description: "Get grid."
                  call: erp.get-grid
                  outputParameters:
                    - schema: grid
                      mappings:
                        rows: "$.rows"
                """);

        assertEquals(List.of(), DefinitionsExpander.expandAll(spec).errors());
    }

    @Test
    void expandAllShouldResolveTheSchemasOfChildrenRejectedBesideASchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                    crew:
                      type: object
                      properties:
                        name: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - schema: ship
                      mappings:
                        imo: "$.imo"
                      properties:
                        x: { schema: crew }
                        z: { schema: nope }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output: 'schema: ship' takes"
                + " only 'mappings' and 'mapping' in a mapped output, found [properties].",
                "MCP adapter 'tools', tool 'get-ship', output: unknown schema 'nope'. MCP adapter"
                + " 'tools' declares ship, crew."), result.errors());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("'crew'")),
                result.warnings().toString());
    }

    @Test
    void expandAllShouldResolveTheSchemasOfAMockOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                get-ship:
                  description: "Get ship."
                  outputParameters:
                    ship:
                      type: object
                      properties:
                        imo: { schema: ship }
                        flag: { schema: nope }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', output 'ship': 'schema:' and"
                + " 'mappings' are not allowed in a mock output, which returns static values."
                + " Declare the output inline.",
                "MCP adapter 'tools', tool 'get-ship', output 'ship': unknown schema 'nope'. MCP"
                + " adapter 'tools' declares ship."), result.errors());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void expandAllShouldResolveTheSchemasUnderValuesOfAnOrchestratedOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    coded: { type: string, enum: [a, b] }
                """, """
                get-codes:
                  description: "Get codes."
                  steps:
                    fetch: { type: call, call: erp.get-codes }
                  mappings:
                    - target: codes
                      value: "$.fetch"
                  outputParameters:
                    codes:
                      type: object
                      values: { schema: coded }
                    others:
                      type: object
                      values: { schema: nope }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("MCP adapter 'tools', tool 'get-codes', output 'others', values:"
                + " unknown schema 'nope'. MCP adapter 'tools' declares coded."), result.errors());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void expandAllShouldResolveTheSchemasOfChildrenRejectedBesideAnOrchestratedSchema()
            throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    obj:
                      type: object
                      properties:
                        id: { type: string }
                    only: { type: string, enum: [a, b] }
                """, """
                get-codes:
                  description: "Get codes."
                  steps:
                    fetch: { type: call, call: erp.get-codes }
                  mappings:
                    - target: o
                      value: "$.fetch"
                  outputParameters:
                    o:
                      schema: obj
                      values: { schema: only }
                    list:
                      type: array
                      items:
                        schema: obj
                        values: { schema: nope }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("MCP adapter 'tools', tool 'get-codes', output 'o': 'schema: obj'"
                + " takes no other key in an output, found [values].",
                "MCP adapter 'tools', tool 'get-codes', output 'list', items: 'schema: obj' takes"
                + " no other key in an output, found [values].",
                "MCP adapter 'tools', tool 'get-codes', output 'list', items: unknown schema"
                + " 'nope'. MCP adapter 'tools' declares obj, only."), result.errors());
        assertEquals(List.of(), result.warnings());
    }

    private static String outputs(IkanosSpec spec, String tool) throws Exception {
        McpServerToolSpec unit = ((McpServerSpec) spec.getCapability().getExposes().get(0))
                .getTools().get(tool);
        String json = IkanosMappers.json().writeValueAsString(unit.getOutputParameters());
        // One output: compare it without the list brackets
        return json.substring(1, json.length() - 1);
    }

    private static IkanosSpec mcp(String definitions, String tools) throws Exception {
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

    private static IkanosSpec read(String yaml) throws Exception {
        return IkanosMappers.yaml().readValue(yaml, IkanosSpec.class);
    }
}
