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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
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
