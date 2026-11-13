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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link DefinitionsExpander}: the expansion of {@code inputParameterSets} and
 * {@code schema:} references (#910), and the errors and warnings it reports.
 */
class DefinitionsExpanderTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void expandAllShouldApplySetsInOrderThenOwnInputs() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    odata-list:
                      filter: { type: string, required: false, description: "Filter." }
                      top: { type: integer, required: false, description: "Rows." }
                    tracing:
                      trace-id: { type: string, required: false, description: "Trace." }
                """, """
                list-orders:
                  description: "List orders."
                  inputParameterSets: [odata-list, tracing]
                  inputParameters:
                    id: { type: string, description: "Order ID." }
                  call: erp.list-orders
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        McpServerToolSpec tool = tool(spec, "list-orders");
        assertEquals(List.of("filter", "top", "trace-id", "id"), names(tool.getInputParameters()));
        assertTrue(tool.getInputParameterSets().isEmpty(), "Sets are merged once expanded");
    }

    @Test
    void expandAllShouldReplaceSetEntryWholeAtItsPositionWhenUnitRedeclaresIt() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    odata-list:
                      filter: { type: string, required: false, description: "Filter.", format: "x" }
                      top: { type: integer, required: false, description: "Rows." }
                """, """
                list-orders:
                  description: "List orders."
                  inputParameterSets: [odata-list]
                  inputParameters:
                    filter: { type: string, description: "Mandatory filter.", required: true }
                  call: erp.list-orders
                """);

        DefinitionsExpander.expandAll(spec);

        List<InputParameterSpec> inputs = tool(spec, "list-orders").getInputParameters();
        assertEquals(List.of("filter", "top"), names(inputs));
        InputParameterSpec filter = inputs.get(0);
        assertTrue(filter.isRequired());
        assertEquals("Mandatory filter.", filter.getDescription());
        assertNull(filter.getFormat(), "No partial override: the set's entry is replaced whole");
    }

    @Test
    void expandAllShouldKeepAnUndeclaredRequiredFlagUndeclared() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    date-range:
                      type: object
                      properties:
                        from: { type: string, format: date }
                  inputParameterSets:
                    paging:
                      top: { type: integer, description: "Rows." }
                      skip: { type: integer, description: "Offset.", required: false }
                      cursor: { type: string, description: "Cursor.", required: true }
                """, """
                list-calls:
                  description: "List calls."
                  inputParameterSets: [paging]
                  inputParameters:
                    period: { schema: date-range, description: "Call period." }
                    window: { schema: date-range, description: "Window.", required: true }
                  call: erp.list-calls
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        List<InputParameterSpec> inputs = tool(spec, "list-calls").getInputParameters();
        assertEquals(List.of("top", "skip", "cursor", "period", "window"), names(inputs));
        assertNull(inputs.get(0).getDeclaredRequired(),
                "A set entry that declares no required flag stays undeclared, as inline");
        assertEquals(Boolean.FALSE, inputs.get(1).getDeclaredRequired());
        assertEquals(Boolean.TRUE, inputs.get(2).getDeclaredRequired());
        assertNull(inputs.get(3).getDeclaredRequired(),
                "A schema use that declares no required flag stays undeclared, as inline");
        assertEquals(Boolean.TRUE, inputs.get(4).getDeclaredRequired());
    }

    @Test
    void expandAllShouldGiveEveryToolItsOwnCopyOfASetEntry() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    key:
                      id: { type: string, description: "ID." }
                """, """
                get-a:
                  description: "Get A."
                  inputParameterSets: [key]
                  call: erp.get-a
                get-b:
                  description: "Get B."
                  inputParameterSets: [key]
                  call: erp.get-b
                """);

        DefinitionsExpander.expandAll(spec);
        tool(spec, "get-a").getInputParameters().get(0).setDescription("Changed.");

        assertEquals("ID.", tool(spec, "get-b").getInputParameters().get(0).getDescription());
    }

    @Test
    void expandAllShouldExpandSchemaOnInputWithUseSiteDescriptionAndRequired() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    date-range:
                      type: object
                      description: "A date range."
                      properties:
                        from: { type: string, format: date }
                        to: { type: string, format: date }
                      required: [from]
                """, """
                list-calls:
                  description: "List calls."
                  inputParameters:
                    period: { schema: date-range, description: "Call period.", required: false }
                    window: { type: array, description: "Windows.", items: { schema: date-range } }
                  call: erp.list-calls
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        List<InputParameterSpec> inputs = tool(spec, "list-calls").getInputParameters();
        InputParameterSpec period = inputs.get(0);
        assertEquals("period", period.getName());
        assertEquals("object", period.getType());
        assertEquals("Call period.", period.getDescription());
        assertFalse(period.isRequired());
        assertEquals(List.of("from"), period.getRequired());
        assertNull(period.getSchema());
        InputParameterSpec items = inputs.get(1).getItems();
        assertEquals("A date range.", items.getDescription());
        assertEquals(List.of("from", "to"), names(items.getProperties()));
    }

    @Test
    void expandAllShouldExpandSchemaThatUsesAnotherSchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    point:
                      type: object
                      properties:
                        x: { type: number }
                    segment:
                      type: object
                      properties:
                        start: { schema: point }
                        end: { schema: point, description: "End." }
                """, """
                draw:
                  description: "Draw."
                  inputParameters:
                    line: { schema: segment, description: "Line." }
                  call: erp.draw
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        InputParameterSpec line = tool(spec, "draw").getInputParameters().get(0);
        InputParameterSpec end = line.getProperties().get(1);
        assertEquals("end", end.getName());
        assertEquals("End.", end.getDescription());
        assertEquals("x", end.getProperties().get(0).getName());
    }

    @Test
    void expandAllShouldConvertSchemaToOrchestratedOutputShape() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      description: "A ship."
                      properties:
                        imo: { type: string, enum: ["1"] }
                        tonnage: { type: integer }
                        tags: { type: array, items: { type: string } }
                      required: [imo]
                """, """
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
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        List<OutputParameterSpec> outputs = tool(spec, "list-ships").getOutputParameters();
        OutputParameterSpec items = outputs.get(0).getItems();
        assertNull(items.getName());
        assertEquals("object", items.getType());
        assertNull(items.getDescription(), "Orchestrated outputs carry no description");
        assertTrue(items.getRequired().isEmpty());
        OutputParameterSpec tonnage = items.getProperties().get(1);
        assertEquals("number", tonnage.getType(), "integer becomes number in an output");
        OutputParameterSpec tags = items.getProperties().get(2);
        assertNull(tags.getItems(), "Scalar items are not declared by an orchestrated output");
        assertEquals("flagship", outputs.get(1).getName());
        assertEquals("object", outputs.get(1).getType());
    }

    @Test
    void expandAllShouldMergeSetsIntoARefToolsOwnInputs() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    key:
                      id: { type: string, description: "ID." }
                """, """
                get-a:
                  ref: domain.get-a
                  inputParameterSets: [key]
                """);

        DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("id"), names(tool(spec, "get-a").getInputParameters()));
    }

    @Test
    void expandAllShouldExpandAggregateFlowWithItsAggregateDefinitions() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - display: "Domain"
                      namespace: domain
                      definitions:
                        inputParameterSets:
                          key:
                            id: { type: string, description: "ID." }
                      flows:
                        get-a:
                          description: "Get A."
                          inputParameterSets: [key]
                          call: erp.get-a
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                      tools:
                        get-a:
                          ref: domain.get-a
                """.formatted(IKANOS));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        AggregateFlowSpec flow = spec.getCapability().getAggregates().get("domain")
                .getFlows().get("get-a");
        assertEquals(List.of("id"), List.copyOf(flow.getInputParameters().keySet()));
    }

    @Test
    void expandAllShouldRejectToolUsingAnAggregatesDefinitions() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - display: "Domain"
                      namespace: domain
                      definitions:
                        inputParameterSets:
                          key:
                            id: { type: string, description: "ID." }
                      flows:
                        get-a:
                          description: "Get A."
                          inputParameterSets: [key]
                          call: erp.get-a
                  exposes:
                    - type: mcp
                      port: 3000
                      namespace: tools
                      tools:
                        get-b:
                          description: "Get B."
                          inputParameterSets: [key]
                          call: erp.get-b
                """.formatted(IKANOS));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("MCP adapter 'tools', tool 'get-b': unknown input parameter set"
                + " 'key'. MCP adapter 'tools' declares none."), result.errors());
    }

    @Test
    void expandAllShouldReportUnknownSchemaWithTheDeclaredOnes() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    date-range: { type: string }
                """, """
                list-calls:
                  description: "List calls."
                  inputParameters:
                    period: { schema: daterange, description: "Period." }
                  call: erp.list-calls
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'list-calls', input 'period': unknown"
                + " schema 'daterange'. MCP adapter 'tools' declares date-range."), errors);
    }

    @Test
    void expandAllShouldReportSchemaUsedAsSet() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    date-range: { type: string }
                """, """
                list-calls:
                  description: "List calls."
                  inputParameterSets: [date-range]
                  call: erp.list-calls
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("'date-range' is a schema, not an input parameter set"),
                errors.toString());
    }

    @Test
    void expandAllShouldReportSetUsedAsSchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    key:
                      id: { type: string, description: "ID." }
                """, """
                get-a:
                  description: "Get A."
                  inputParameters:
                    id: { schema: key, description: "ID." }
                  call: erp.get-a
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertTrue(errors.get(0).contains("'key' is an input parameter set, not a schema"),
                errors.toString());
    }

    @Test
    void expandAllShouldReportSameInputFromTwoSets() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    a:
                      id: { type: string, description: "ID." }
                    b:
                      id: { type: string, description: "Other ID." }
                """, """
                get-a:
                  description: "Get A."
                  inputParameterSets: [a, b]
                  call: erp.get-a
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-a': input 'id' comes from both"
                + " input parameter sets 'a' and 'b'. Keep it in one set, or declare it on the"
                + " tool."), errors);
    }

    @Test
    void expandAllShouldReportCycleBetweenSchemasOnce() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    node:
                      type: object
                      properties:
                        next: { schema: link }
                    link:
                      type: array
                      items: { schema: node }
                """, """
                walk:
                  description: "Walk."
                  inputParameters:
                    start: { schema: node, description: "Start." }
                  call: erp.walk
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools': schemas reference each other in a cycle:"
                + " node -> link -> node."), errors);
    }

    @Test
    void expandAllShouldReportKeysBesideSchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    date-range: { type: string }
                """, """
                list-calls:
                  description: "List calls."
                  inputParameters:
                    period: { schema: date-range, type: string, format: date, description: "P." }
                  call: erp.list-calls
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'list-calls', input 'period':"
                + " 'schema: date-range' takes no other key than 'description' and 'required',"
                + " found [type, format]. Change the schema, or declare the input inline."),
                errors);
    }

    @Test
    void expandAllShouldReportQualifiedAndRefPointers() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    date-range: { type: string }
                """, """
                list-calls:
                  description: "List calls."
                  inputParameterSets: [other.key]
                  inputParameters:
                    period: { $ref: "#/definitions/schemas/date-range", description: "P." }
                  call: erp.list-calls
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'other.key' is not the bare name of a definition"));
        assertEquals("MCP adapter 'tools', tool 'list-calls', input 'period': '$ref' is not"
                + " supported. Reference a schema of the definitions by its bare name, with"
                + " 'schema: name'.", errors.get(1));
    }

    @Test
    void expandAllShouldRejectDollarRefEvenWhenItNamesALocalSchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship: { type: object, properties: { imo: { type: string } } }
                """, """
                get-ship:
                  description: "Get a ship."
                  inputParameters:
                    ship: { $ref: ship, description: "Ship." }
                  call: erp.get-ship
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', input 'ship': '$ref' is not"
                + " supported. Reference a schema of the definitions by its bare name, with"
                + " 'schema: name'."), errors);
    }

    @Test
    void expandAllShouldRejectDollarRefBesideSchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship: { type: object, properties: { imo: { type: string } } }
                """, """
                get-ship:
                  description: "Get a ship."
                  inputParameters:
                    ship: { schema: ship, $ref: "#/elsewhere", description: "Ship." }
                  call: erp.get-ship
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', input 'ship': '$ref' is not"
                + " supported. Reference a schema of the definitions by its bare name, with"
                + " 'schema: name'."), errors);
    }

    @Test
    void expandAllShouldRejectDollarRefInAnOrchestratedOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship: { type: object, properties: { imo: { type: string } } }
                """, """
                list-ships:
                  description: "List ships."
                  steps:
                    ships: { type: call, call: erp.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships"
                  outputParameters:
                    flagship: { $ref: ship }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertTrue(errors.contains("MCP adapter 'tools', tool 'list-ships', output 'flagship':"
                + " '$ref' is not supported. Reference a schema of the definitions by its bare"
                + " name, with 'schema: name'."), errors.toString());
    }

    @Test
    void expandAllShouldRejectSchemaInMappedOutput() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship: { type: object, properties: { imo: { type: string } } }
                """, """
                get-ship:
                  description: "Get ship."
                  call: erp.get-ship
                  outputParameters:
                    - type: object
                      schema: ship
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'schema:' is only allowed in orchestrated (steps)"
                + " outputs"), errors.toString());
    }

    @Test
    void expandAllShouldReportAMistakeInASetOnceForAllItsUsers() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    key:
                      id: { schema: missing, description: "ID." }
                """, """
                get-a:
                  description: "Get A."
                  inputParameterSets: [key]
                  call: erp.get-a
                get-b:
                  description: "Get B."
                  inputParameterSets: [key]
                  call: erp.get-b
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', input parameter set 'key', input 'id':"
                + " unknown schema 'missing'. MCP adapter 'tools' declares none."), errors);
    }

    @Test
    void expandAllShouldWarnOnDefinitionsNoUnitUses() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  inputParameterSets:
                    unused-set:
                      id: { schema: only-in-unused-set, description: "ID." }
                  schemas:
                    only-in-unused-set: { type: string }
                    used: { type: string }
                """, """
                get-a:
                  description: "Get A."
                  inputParameters:
                    id: { schema: used, description: "ID." }
                  call: erp.get-a
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(List.of(
                "MCP adapter 'tools' declares the input parameter set 'unused-set', which no tool"
                        + " uses.",
                "MCP adapter 'tools' declares the schema 'only-in-unused-set', which no tool"
                        + " uses."), result.warnings());
    }

    @Test
    void expandAllShouldStopPastTheExpansionBound() throws Exception {
        // Each level uses the next one twice: 2^20 declarations without any cycle.
        StringBuilder schemas = new StringBuilder("definitions:\n  schemas:\n");
        for (int level = 0; level < 20; level++) {
            schemas.append("    s").append(level).append(":\n")
                    .append("      type: object\n      properties:\n")
                    .append("        a: { schema: s").append(level + 1).append(" }\n")
                    .append("        b: { schema: s").append(level + 1).append(" }\n");
        }
        schemas.append("    s20: { type: string }\n");
        IkanosSpec spec = mcp(schemas.toString(), """
                bomb:
                  description: "Bomb."
                  inputParameters:
                    root: { schema: s0, description: "Root." }
                  call: erp.bomb
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools': its definitions expand to more than "
                + DefinitionsExpander.MAX_EXPANDED_NODES + " parameter declarations. Expansion"
                + " is bounded, so that a document cannot multiply itself without limit."),
                errors);
    }

    @Test
    void expandAllShouldBoundASetUsedByManyToolsWithoutCopyingItPastTheBound() throws Exception {
        // One set of 2,000 entries used by 2,000 tools: 4,000,000 declarations once expanded.
        int size = 2_000;
        StringBuilder definitions = new StringBuilder(
                "definitions:\n  inputParameterSets:\n    wide:\n");
        for (int i = 0; i < size; i++) {
            definitions.append("      p").append(i).append(": { type: string }\n");
        }
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < size; i++) {
            tools.append("t").append(i).append(":\n")
                    .append("  description: \"Tool.\"\n")
                    .append("  inputParameterSets: [wide]\n")
                    .append("  call: erp.t").append(i).append("\n");
        }
        IkanosSpec spec = mcp(definitions.toString(), tools.toString());

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools': its definitions expand to more than "
                + DefinitionsExpander.MAX_EXPANDED_NODES + " parameter declarations. Expansion"
                + " is bounded, so that a document cannot multiply itself without limit."),
                errors);
        long materialized = 0;
        for (McpServerToolSpec tool : ((McpServerSpec) spec.getCapability().getExposes().get(0))
                .getTools().values()) {
            materialized += tool.getInputParameters().size();
        }
        assertTrue(materialized <= DefinitionsExpander.MAX_EXPANDED_NODES,
                "Set entries are charged before they are copied: " + materialized + " copies");
    }

    @Test
    void expandAllShouldChargeTheBoundWithTheDeclarationsEmittedOnly() throws Exception {
        // Each level uses the next one twice: one use of s0 emits 2^15 - 1 = 32,767
        // declarations, below the bound, however many times the schemas are checked.
        StringBuilder schemas = new StringBuilder("definitions:\n  schemas:\n");
        for (int level = 0; level < 14; level++) {
            schemas.append("    s").append(level).append(":\n")
                    .append("      type: object\n      properties:\n")
                    .append("        a: { schema: s").append(level + 1).append(" }\n")
                    .append("        b: { schema: s").append(level + 1).append(" }\n");
        }
        schemas.append("    s14: { type: string }\n");
        IkanosSpec spec = mcp(schemas.toString(), """
                tree:
                  description: "Tree."
                  inputParameters:
                    root: { schema: s0, description: "Root." }
                  call: erp.tree
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(32_767, count(tool(spec, "tree").getInputParameters().get(0)));
    }

    @Test
    void expandAllShouldReportAMistakeInAnUnusedSchemaWithoutExpandingIt() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    broken:
                      type: object
                      properties:
                        window: { schema: missing }
                """, """
                get-a:
                  description: "Get A."
                  inputParameters:
                    id: { type: string, description: "ID." }
                  call: erp.get-a
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', schema 'broken', property 'window': unknown"
                + " schema 'missing'. MCP adapter 'tools' declares broken."), errors);
    }

    @Test
    void expandAllShouldExpandDirectItemsReferenceLikeAWholeArraySchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    label: { type: string }
                    labels: { type: array, items: { schema: label } }
                """, """
                list-labels:
                  description: "List labels."
                  steps:
                    labels: { type: call, call: erp.list-labels }
                  mappings:
                    - target: direct
                      value: "$.labels"
                    - target: whole
                      value: "$.labels"
                  outputParameters:
                    direct:
                      type: array
                      items: { schema: label }
                    whole: { schema: labels }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        List<OutputParameterSpec> outputs = tool(spec, "list-labels").getOutputParameters();
        assertEquals("array", outputs.get(0).getType());
        assertNull(outputs.get(0).getItems(),
                "Scalar items are not declared by an orchestrated output, referenced or not");
        assertNull(outputs.get(1).getItems());
    }

    @Test
    void expandAllShouldReportEveryOutputKeyBesideSchema() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  schemas:
                    ship: { type: object, properties: { imo: { type: string } } }
                """, """
                list-ships:
                  description: "List ships."
                  steps:
                    ships: { type: call, call: erp.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships"
                  outputParameters:
                    flagship: { schema: ship, precision: 2, const: "x", enum: ["a"] }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'list-ships', output 'flagship':"
                + " 'schema: ship' takes no other key in an output, found [const, enum,"
                + " precision]."), errors);
    }

    @Test
    void expandAllShouldChargeAnOutputOnlyWithTheShapeItEmits() throws Exception {
        // As an input, `grid` expands to 2 + 2^17 - 1 declarations, past the bound. As an
        // orchestrated output, it is an array of arrays: it emits `type: array` alone.
        IkanosSpec spec = mcp(binarySchemas(16) + """
                    grid:
                      type: array
                      items:
                        type: array
                        items: { schema: s0 }
                """, """
                get-grid:
                  description: "Get the grid."
                  steps:
                    grid: { type: call, call: erp.get-grid }
                  mappings:
                    - target: grid
                      value: "$.grid"
                  outputParameters:
                    grid: { schema: grid }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        OutputParameterSpec grid = tool(spec, "get-grid").getOutputParameters().get(0);
        assertEquals("array", grid.getType());
        assertNull(grid.getItems());
    }

    @Test
    void expandAllShouldStillBoundTheShapeAnOutputEmits() throws Exception {
        IkanosSpec spec = mcp(binarySchemas(20), """
                get-tree:
                  description: "Get the tree."
                  steps:
                    tree: { type: call, call: erp.get-tree }
                  mappings:
                    - target: tree
                      value: "$.tree"
                  outputParameters:
                    tree: { schema: s0 }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools': its definitions expand to more than "
                + DefinitionsExpander.MAX_EXPANDED_NODES + " parameter declarations. Expansion"
                + " is bounded, so that a document cannot multiply itself without limit."),
                errors);
    }

    /**
     * Schemas {@code s0} to {@code s<levels>}: each level is an object whose two properties use
     * the next level, and the last level is a string, so {@code s0} has 2^(levels+1) - 1
     * declarations.
     */
    private static String binarySchemas(int levels) {
        StringBuilder schemas = new StringBuilder("definitions:\n  schemas:\n");
        for (int level = 0; level < levels; level++) {
            schemas.append("    s").append(level).append(":\n")
                    .append("      type: object\n      properties:\n")
                    .append("        a: { schema: s").append(level + 1).append(" }\n")
                    .append("        b: { schema: s").append(level + 1).append(" }\n");
        }
        schemas.append("    s").append(levels).append(": { type: string }\n");
        return schemas.toString();
    }

    @Test
    void expandAllShouldBoundALongChainOfSchemasWithoutOverflowingTheStack() throws Exception {
        int length = 5_000;
        StringBuilder schemas = new StringBuilder("definitions:\n  schemas:\n");
        for (int i = 0; i < length; i++) {
            schemas.append("    s").append(i).append(": { schema: s").append(i + 1)
                    .append(" }\n");
        }
        schemas.append("    s").append(length).append(": { type: string }\n");
        IkanosSpec spec = mcp(schemas.toString(), """
                get-a:
                  description: "Get A."
                  inputParameters:
                    id: { schema: s0, description: "ID." }
                  call: erp.get-a
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        int deepest = length - DefinitionsExpander.MAX_EXPANDED_DEPTH;
        assertEquals(List.of("MCP adapter 'tools', schema 's" + deepest + "': it expands to"
                + " more than " + DefinitionsExpander.MAX_EXPANDED_DEPTH + " nested levels,"
                + " through the schemas it uses. Expansion is bounded, so that a long chain of"
                + " schemas cannot exhaust the stack."), errors);
    }

    @Test
    void expandAllShouldExpandAChainOfSchemasWithinTheDepthBound() throws Exception {
        StringBuilder schemas = new StringBuilder("definitions:\n  schemas:\n");
        for (int i = 0; i < 40; i++) {
            schemas.append("    s").append(i).append(": { schema: s").append(i + 1)
                    .append(" }\n");
        }
        schemas.append("    s40: { type: string, format: date }\n");
        IkanosSpec spec = mcp(schemas.toString(), """
                get-a:
                  description: "Get A."
                  inputParameters:
                    day: { schema: s0, description: "Day." }
                  call: erp.get-a
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        InputParameterSpec day = tool(spec, "get-a").getInputParameters().get(0);
        assertEquals("date", day.getFormat());
        assertEquals("Day.", day.getDescription());
    }

    @Test
    void expandShouldThrowWithEveryErrorWhenDefinitionsAreInvalid() throws Exception {
        IkanosSpec spec = mcp("", """
                get-a:
                  description: "Get A."
                  inputParameterSets: [key]
                  call: erp.get-a
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DefinitionsExpander.expand(spec));

        assertTrue(error.getMessage().contains("unknown input parameter set 'key'"),
                error.getMessage());
    }

    @Test
    void expandAllShouldLeaveUnitsWithoutDefinitionsUntouched() throws Exception {
        IkanosSpec spec = mcp("", """
                get-a:
                  description: "Get A."
                  inputParameters:
                    id: { type: string, description: "ID." }
                  call: erp.get-a
                """);
        InputParameterSpec before = tool(spec, "get-a").getInputParameters().get(0);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty() && result.warnings().isEmpty());
        assertTrue(before == tool(spec, "get-a").getInputParameters().get(0));
    }

    /** Number of parameter declarations in an input, nested ones included. */
    private static int count(InputParameterSpec param) {
        if (param == null) {
            return 0;
        }
        int total = 1 + count(param.getItems()) + count(param.getValues());
        for (InputParameterSpec property : param.getProperties()) {
            total += count(property);
        }
        return total;
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

    private static McpServerToolSpec tool(IkanosSpec spec, String name) {
        return ((McpServerSpec) spec.getCapability().getExposes().get(0)).getTools().get(name);
    }

    private static List<String> names(List<? extends io.ikanos.spec.util.StructureSpec<?>> specs) {
        return specs.stream().map(io.ikanos.spec.util.StructureSpec::getName).toList();
    }
}
