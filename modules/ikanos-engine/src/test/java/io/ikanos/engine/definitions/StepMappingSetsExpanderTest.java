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

import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.engine.util.StepExecutionContext;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.StepOutputMappingSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for mapping sets in steps-mode {@code mappings} (#1150): an entry that lists
 * {@code mappingSets} roots their paths with its {@code target} and {@code value}, and expands to
 * one entry per leaf path.
 */
class StepMappingSetsExpanderTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String DEFINITIONS = """
            definitions:
              schemas:
                ship:
                  type: object
                  properties:
                    imo: { type: string }
                    flag: { type: string }
                    specs:
                      type: object
                      properties:
                        tonnage: { type: number }
              mappingSets:
                ship-from-registry:
                  imo: "$.imo_number"
                  flag: "$.flag_state"
                  specs: "$.specs"
                  specs.tonnage: "$.gross_tonnage"
                ship-from-ais:
                  flag: "$.ais.flag"
            """;

    @Test
    void expandAllShouldExpandAMappingSetIntoOneEntryPerLeafPath() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings().stream()
                .filter(warning -> warning.contains("ship-from-registry")).toList());
        assertEquals("[{\"target\":\"flagship.imo\",\"value\":\"$.ships.flagship.imo_number\"},"
                + "{\"target\":\"flagship.flag\",\"value\":\"$.ships.flagship.flag_state\"},"
                + "{\"target\":\"flagship.specs.tonnage\",\"value\":"
                + "\"$.ships.flagship.specs.gross_tonnage\"}]", mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldLetALaterSetWinAndAnExplicitEntryWinLast() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship.imo
                      value: "$.ships.lead_imo"
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry, ship-from-ais]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.imo\",\"value\":\"$.ships.lead_imo\"},"
                + "{\"target\":\"flagship.flag\",\"value\":\"$.ships.flagship.ais.flag\"},"
                + "{\"target\":\"flagship.specs.tonnage\",\"value\":"
                + "\"$.ships.flagship.specs.gross_tonnage\"}]", mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldExpandATargetThatIsNotAnOutputWithoutCheckingItsPaths()
            throws Exception {
        // A target may be an input of a next step: there is no output to check its paths against
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: lead
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-ais]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"lead.flag\",\"value\":\"$.ships.flagship.ais.flag\"}]",
                mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldRejectAPathThatTheTargetedOutputDoesNotHave() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry]
                  outputParameters:
                    flagship:
                      type: object
                      properties:
                        imo: { type: string }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-fleet', mapping 'flagship': output"
                + " 'flagship' has no path [flag (mapping set 'ship-from-registry'),"
                + " specs.tonnage (mapping set 'ship-from-registry')]."), errors);
    }

    @Test
    void expandAllShouldRejectAPathThroughArrayItems() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  mappingSets:
                    crew-names:
                      crew: "$.crew"
                      crew[].fullName: "$.name"
                """, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [crew-names]
                  outputParameters:
                    flagship: { type: object }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-fleet', mapping 'flagship':"
                + " [crew[].fullName (mapping set 'crew-names')] bind array items, which a"
                + " steps-mode mapping cannot reach: it sets each target whole. Map the array"
                + " whole, or bind its items in a call-mode output."), errors);
    }

    @Test
    void expandAllShouldRejectAnUnknownMappingSetInAStepMapping() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-crm]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-fleet', mapping 'flagship': unknown"
                + " mapping set 'ship-from-crm'. MCP adapter 'tools' declares ship-from-registry,"
                + " ship-from-ais."), errors);
    }

    @Test
    void expandAllShouldRejectAStepMappingSetWithoutAJsonPathValue() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "flagship"
                      mappingSets: [ship-from-registry]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-fleet', mapping 'flagship':"
                + " 'mappingSets' needs 'target' and a 'value' JSONPath ('$.<step>...'), the roots"
                + " of the sets' paths."), errors);
    }

    @Test
    void expandAllShouldExpandAStepMappingSetInAFlow() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: fleet
                      definitions:
                        mappingSets:
                          ship-from-ais:
                            flag: "$.ais.flag"
                      flows:
                        get-fleet:
                          description: "Get the fleet."
                          steps:
                            ships: { type: call, call: registry.list-ships }
                          mappings:
                            - target: flagship
                              value: "$.ships.flagship"
                              mappingSets: [ship-from-ais]
                          outputParameters:
                            flagship:
                              type: object
                              properties:
                                flag: { type: string }
                """.formatted(IKANOS));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);
        AggregateFlowSpec flow = spec.getCapability().getAggregates().values().iterator().next()
                .getFlows().get("get-fleet");

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.flag\",\"value\":\"$.ships.flagship.ais.flag\"}]",
                IkanosMappers.json().writeValueAsString(flow.getMappings()));
    }

    @Test
    void expandAllShouldExpandAStepMappingSetInARestOperation() throws Exception {
        IkanosSpec spec = read("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: rest
                      port: 3001
                      namespace: api
                      definitions:
                        mappingSets:
                          ship-from-ais:
                            flag: "$.ais.flag"
                      resources:
                        fleet:
                          path: "/fleet"
                          operations:
                            get-fleet:
                              method: GET
                              steps:
                                ships: { type: call, call: registry.list-ships }
                              mappings:
                                - target: flagship
                                  value: "$.ships.flagship"
                                  mappingSets: [ship-from-ais]
                              outputParameters:
                                flagship:
                                  type: object
                                  properties:
                                    flag: { type: string }
                """.formatted(IKANOS));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);
        RestServerSpec rest = (RestServerSpec) spec.getCapability().getExposes().get(0);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.flag\",\"value\":\"$.ships.flagship.ais.flag\"}]",
                IkanosMappers.json().writeValueAsString(rest.getResources().get("fleet")
                        .getOperations().get("get-fleet").getMappings()));
    }

    @Test
    void expandAllShouldExpandTheSameAfterAYamlRoundTrip() throws Exception {
        // Imports deep-copy adapters and aggregates through the serializers
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry, ship-from-ais]
                  outputParameters:
                    flagship: { schema: ship }
                """);
        IkanosSpec copy = read(IkanosMappers.yaml().writeValueAsString(spec));

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(copy);
        DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals(mappings(spec, "get-fleet"), mappings(copy, "get-fleet"));
    }

    @Test
    void expandAllShouldComposeARelativeRootAsNothing() throws Exception {
        // "$" and "$." select the object itself, as in a call-mode output
        IkanosSpec spec = mcp("""
                definitions:
                  mappingSets:
                    flat-position:
                      self: "$."
                      position: "$"
                      position.lat: "$.latitude"
                """, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [flat-position]
                  outputParameters:
                    flagship: { type: object }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.self\",\"value\":\"$.ships.flagship\"},"
                + "{\"target\":\"flagship.position.lat\",\"value\":"
                + "\"$.ships.flagship.latitude\"}]", mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldLetAnExplicitEntryWinOverAnExpandedAncestorOrDescendant()
            throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship.specs
                      value: "$.ships.specs"
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.specs\",\"value\":\"$.ships.specs\"},"
                + "{\"target\":\"flagship.imo\",\"value\":\"$.ships.flagship.imo_number\"},"
                + "{\"target\":\"flagship.flag\",\"value\":\"$.ships.flagship.flag_state\"}]",
                mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldRejectARootWithoutAStepName() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$."
                      mappingSets: [ship-from-ais]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', tool 'get-fleet', mapping 'flagship':"
                + " 'mappingSets' needs 'target' and a 'value' JSONPath ('$.<step>...'), the roots"
                + " of the sets' paths."), errors);
    }

    @Test
    void expandAllShouldOnlyResolveStepMappingSetsOfAUnitWithoutSteps() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-ship:
                  description: "Get a ship."
                  call: registry.get-ship
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-crm]
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of("MCP adapter 'tools', tool 'get-ship', mapping 'flagship': unknown"
                + " mapping set 'ship-from-crm'. MCP adapter 'tools' declares ship-from-registry,"
                + " ship-from-ais."), result.errors());
        assertEquals("[{\"target\":\"flagship\",\"value\":\"$.ships.flagship\","
                + "\"mappingSets\":[\"ship-from-crm\"]}]", mappings(spec, "get-ship"));
    }

    @Test
    void expandedEntriesShouldResolveLikeTheInlineEntriesAtRuntime() throws Exception {
        // The runtime reads the composed values: a "$." set value and nested paths included
        IkanosSpec spec = mcp("""
                definitions:
                  mappingSets:
                    ship-from-registry:
                      imo: "$.imo_number"
                      position: "$."
                      position.lat: "$.latitude"
                      specs: "$.specs"
                      specs.tonnage: "$.gross_tonnage"
                """, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-from-registry]
                  outputParameters:
                    flagship: { type: object }
                """);
        DefinitionsExpander.expandAll(spec);
        List<StepOutputMappingSpec> inline = List.of(
                new StepOutputMappingSpec("flagship.imo", "$.ships.flagship.imo_number"),
                new StepOutputMappingSpec("flagship.position.lat", "$.ships.flagship.latitude"),
                new StepOutputMappingSpec("flagship.specs.tonnage",
                        "$.ships.flagship.specs.gross_tonnage"));
        StepExecutionContext context = new StepExecutionContext();
        context.storeStepOutput("ships", IkanosMappers.json().readTree("""
                {"flagship": {"imo_number": "9321483", "latitude": 51.9,
                  "specs": {"gross_tonnage": 151000}}}
                """));
        OperationStepExecutor executor = new OperationStepExecutor(null);
        McpServerToolSpec tool = ((McpServerSpec) spec.getCapability().getExposes().get(0))
                .getTools().get("get-fleet");

        String expanded = executor.resolveStepMappings(tool.getMappings(), context);

        assertEquals(executor.resolveStepMappings(inline, context), expanded);
        assertEquals("{\"flagship\":{\"imo\":\"9321483\",\"position\":{\"lat\":51.9},"
                + "\"specs\":{\"tonnage\":151000}}}", expanded);
    }

    @Test
    void expandAllShouldLetAnExplicitEntryWinOverAnExpandedAncestor() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  mappingSets:
                    ship-specs:
                      imo: "$.imo_number"
                      specs: "$.specs"
                """, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship.specs.tonnage
                      value: "$.ships.override"
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-specs]
                  outputParameters:
                    flagship: { type: object }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.specs.tonnage\",\"value\":\"$.ships.override\"},"
                + "{\"target\":\"flagship.imo\",\"value\":\"$.ships.flagship.imo_number\"}]",
                mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldRejectARootThatIsNotAStepPath() throws Exception {
        for (String root : List.of("$", "$ships")) {
            IkanosSpec spec = mcp(DEFINITIONS, """
                    get-fleet:
                      description: "Get the fleet."
                      steps:
                        ships: { type: call, call: registry.list-ships }
                      mappings:
                        - target: flagship
                          value: "%s"
                          mappingSets: [ship-from-ais]
                      outputParameters:
                        flagship: { schema: ship }
                    """.formatted(root));

            assertEquals(List.of("MCP adapter 'tools', tool 'get-fleet', mapping 'flagship':"
                    + " 'mappingSets' needs 'target' and a 'value' JSONPath ('$.<step>...'), the"
                    + " roots of the sets' paths."), DefinitionsExpander.expandAll(spec).errors(),
                    root);
        }
    }

    @Test
    void expandAllShouldDropTheTrailingDotOfARoot() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships."
                      mappingSets: [ship-from-ais]
                  outputParameters:
                    flagship: { schema: ship }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.flag\",\"value\":\"$.ships.ais.flag\"}]",
                mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldReportAnEmptySetValueInAStepMappingWithoutFailing() throws Exception {
        IkanosSpec spec = mcp("""
                definitions:
                  mappingSets:
                    ship-blank:
                      imo: ""
                """, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: [ship-blank]
                  outputParameters:
                    flagship: { type: object }
                """);

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        assertEquals(List.of("MCP adapter 'tools', mapping set 'ship-blank' binds [imo] to a value"
                + " that is not a JSONPath (starting with '$'). A static 'value' is declared"
                + " inline."), errors);
    }

    @Test
    void expandAllShouldReadASingleMappingSetNameInAStepMapping() throws Exception {
        IkanosSpec spec = mcp(DEFINITIONS, """
                get-fleet:
                  description: "Get the fleet."
                  steps:
                    ships: { type: call, call: registry.list-ships }
                  mappings:
                    - target: flagship
                      value: "$.ships.flagship"
                      mappingSets: ship-from-ais
                  outputParameters:
                    flagship: { schema: ship }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(List.of(), result.errors());
        assertEquals("[{\"target\":\"flagship.flag\",\"value\":\"$.ships.flagship.ais.flag\"}]",
                mappings(spec, "get-fleet"));
    }

    @Test
    void expandAllShouldChargeTheEntriesOfAMappingSetOnEachStepUse() throws Exception {
        // A set of 5,000 paths the output lacks, listed by 30 steps-mode entries: each use is
        // charged the set's entries before they are checked, so the budget stops the expansion
        // before the "has no path" errors multiply
        StringBuilder set = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            set.append("p").append(i).append(": \"$.p").append(i).append("\"\n");
        }
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            tools.append("""
                    get-fleet-%d:
                      description: "Get the fleet."
                      steps:
                        ships: { type: call, call: registry.list-ships }
                      mappings:
                        - target: flagship
                          value: "$.ships.flagship"
                          mappingSets: [wide]
                      outputParameters:
                        flagship:
                          type: object
                          properties:
                            imo: { type: string }
                    """.formatted(i));
        }
        IkanosSpec spec = mcp("""
                definitions:
                  mappingSets:
                    wide:
                %s""".formatted(set.toString().indent(6)), tools.toString());

        List<String> errors = DefinitionsExpander.expandAll(spec).errors();

        long noPath = errors.stream().filter(error -> error.contains("has no path")).count();
        assertTrue(noPath <= DefinitionsExpander.MAX_EXPANDED_NODES / 5000, "" + noPath);
        assertEquals(1, errors.stream().filter(error -> error.equals("MCP adapter 'tools': its"
                + " definitions expand to more than " + DefinitionsExpander.MAX_EXPANDED_NODES
                + " parameter declarations. Expansion is bounded, so that a document cannot"
                + " multiply itself without limit.")).count(), "" + errors.size());
    }

    private static String mappings(IkanosSpec spec, String tool) throws Exception {
        McpServerToolSpec unit = ((McpServerSpec) spec.getCapability().getExposes().get(0))
                .getTools().get(tool);
        return IkanosMappers.json().writeValueAsString(unit.getMappings());
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
