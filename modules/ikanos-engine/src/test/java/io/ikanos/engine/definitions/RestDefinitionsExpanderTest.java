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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.StructureSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link DefinitionsExpander} on REST adapters (#1000): input parameter sets on
 * resources and operations, schemas in orchestrated outputs, and the errors and warnings it
 * reports for them.
 */
class RestDefinitionsExpanderTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void expandAllShouldApplyOperationSetsInOrderThenOwnInputs() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    paging:
                      limit: { in: query, type: integer, description: "Maximum items." }
                      offset: { in: query, type: integer, description: "Items to skip." }
                    tracing:
                      trace-id: { in: header, type: string, description: "Trace." }
                """, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameterSets: [paging, tracing]
                      inputParameters:
                        status: { in: query, type: string, description: "Status." }
                      call: registry.list-ships
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        RestServerOperationSpec operation = operation(spec, "ships", "list-ships");
        assertEquals(List.of("limit", "offset", "trace-id", "status"),
                names(operation.getInputParameters()));
        assertEquals("query", operation.getInputParameters().get(0).getIn());
        assertTrue(operation.getInputParameterSets().isEmpty(), "Sets are merged once expanded");
    }

    @Test
    void expandAllShouldGiveAResourceTheInputsOfItsSets() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    ship-key:
                      imo: { in: path, type: string, description: "IMO number." }
                """, """
                ship:
                  path: "/ships/{{imo}}"
                  inputParameterSets: [ship-key]
                  operations:
                    get-ship:
                      method: GET
                      call: registry.get-ship
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        RestServerResourceSpec resource = resource(spec, "ship");
        assertEquals(List.of("imo"), names(resource.getInputParameters()));
        assertEquals("IMO number.", resource.getInputParameters().get(0).getDescription());
        assertTrue(resource.getInputParameterSets().isEmpty(), "Sets are merged once expanded");
    }

    @Test
    void expandAllShouldReplaceSetEntryWholeWhenOperationRedeclaresIt() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    filter:
                      status: { in: query, type: string, value: "active", description: "Status." }
                """, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameterSets: [filter]
                      inputParameters:
                        status: { in: header, type: string, description: "Status header." }
                      call: registry.list-ships
                """);

        DefinitionsExpander.expandAll(spec);

        InputParameterSpec status =
                operation(spec, "ships", "list-ships").getInputParameters().get(0);
        assertEquals("header", status.getIn());
        assertNull(status.getValue(), "No partial override: the set's entry is replaced whole");
    }

    @Test
    void expandAllShouldGiveEveryOperationItsOwnCopyOfASetEntry() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    filter:
                      status: { in: query, type: string, description: "Status." }
                """, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameterSets: [filter]
                      call: registry.list-ships
                vessels:
                  path: "/vessels"
                  operations:
                    list-vessels:
                      method: GET
                      inputParameterSets: [filter]
                      call: legacy.list-vessels
                """);

        DefinitionsExpander.expandAll(spec);
        operation(spec, "ships", "list-ships").getInputParameters().get(0)
                .setDescription("Changed.");

        assertEquals("Status.", operation(spec, "vessels", "list-vessels")
                .getInputParameters().get(0).getDescription());
    }

    @Test
    void expandAllShouldShapeOrchestratedOutputItemsWithASchema() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  schemas:
                    crew-member:
                      type: object
                      properties:
                        fullName: { type: string }
                        age: { type: integer }
                """, """
                crew:
                  path: "/crew"
                  operations:
                    list-crew:
                      method: GET
                      steps:
                        list:
                          type: call
                          call: registry.list-crew
                      mappings:
                        - target: crew
                          value: "$.list"
                      outputParameters:
                        crew:
                          type: array
                          items:
                            schema: crew-member
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        OutputParameterSpec crew = operation(spec, "crew", "list-crew").getOutputParameters()
                .get(0);
        OutputParameterSpec items = crew.getItems();
        assertNull(items.getSchema());
        assertEquals(List.of("fullName", "age"), names(items.getProperties()));
        assertEquals("number", items.getProperties().get(1).getType(),
                "Orchestrated outputs have no integer");
    }

    @Test
    void expandAllShouldRejectASchemaOnARestInput() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  schemas:
                    date-range:
                      type: object
                      properties:
                        from: { type: string }
                """, """
                calls:
                  path: "/calls"
                  operations:
                    list-calls:
                      method: GET
                      inputParameters:
                        period: { schema: date-range, description: "Period." }
                      call: registry.list-calls
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertEquals(1, result.errors().size(), result.errors().toString());
        assertTrue(result.errors().get(0).contains("REST adapter 'api', resource 'calls',"
                + " operation 'list-calls', input 'period'"), result.errors().get(0));
        assertTrue(result.errors().get(0).contains("not supported on a REST input"));
        assertTrue(result.warnings().isEmpty(),
                "A schema named by a rejected input is not also reported unused");
    }

    @Test
    void expandAllShouldRejectASchemaInARestSetEntry() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    period:
                      period: { schema: date-range, description: "Period." }
                  schemas:
                    date-range:
                      type: object
                      properties:
                        from: { type: string }
                """, """
                calls:
                  path: "/calls"
                  operations:
                    list-calls:
                      method: GET
                      inputParameterSets: [period]
                      call: registry.list-calls
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().stream().anyMatch(error -> error.contains(
                "input parameter set 'period', input 'period'")
                && error.contains("not supported on a REST input")),
                result.errors().toString());
    }

    @Test
    void expandAllShouldReportAnUnknownSetOfARestOperation() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    paging:
                      limit: { in: query, type: integer, description: "Maximum items." }
                """, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameterSets: [pagin]
                      call: registry.list-ships
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().contains("REST adapter 'api', resource 'ships', operation"
                + " 'list-ships': unknown input parameter set 'pagin'. REST adapter 'api'"
                + " declares paging."), result.errors().toString());
    }

    @Test
    void expandAllShouldReportTheSameInputFromTwoSetsOfOneResource() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    ship-key:
                      imo: { in: path, type: string, description: "IMO." }
                    legacy-key:
                      imo: { in: path, type: string, description: "Legacy IMO." }
                """, """
                ship:
                  path: "/ships/{{imo}}"
                  inputParameterSets: [ship-key, legacy-key]
                  operations:
                    get-ship:
                      method: GET
                      call: registry.get-ship
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().contains("REST adapter 'api', resource 'ship': input 'imo'"
                + " comes from both input parameter sets 'ship-key' and 'legacy-key'. Keep it in"
                + " one set, or declare it on the resource or operation."),
                result.errors().toString());
    }

    @Test
    void expandAllShouldRejectASchemaInAMappedRestOutput() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  schemas:
                    ship:
                      type: object
                      properties:
                        imo: { type: string }
                """, """
                ship:
                  path: "/ship"
                  operations:
                    get-ship:
                      method: GET
                      call: registry.get-ship
                      outputParameters:
                        - schema: ship
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith(
                "REST adapter 'api', resource 'ship', operation 'get-ship', output")
                && error.contains("only allowed in orchestrated (steps) outputs")),
                result.errors().toString());
    }

    @Test
    void expandAllShouldRejectAKeyBesideASchemaInAnOrchestratedRestOutput() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  schemas:
                    crew-member:
                      type: object
                      properties:
                        fullName: { type: string }
                """, """
                crew:
                  path: "/crew"
                  operations:
                    get-lead:
                      method: GET
                      steps:
                        lead:
                          type: call
                          call: registry.get-lead
                      mappings:
                        - target: lead
                          value: "$.lead"
                      outputParameters:
                        lead: { schema: crew-member, type: object }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().contains("REST adapter 'api', resource 'crew', operation"
                + " 'get-lead', output 'lead': 'schema: crew-member' takes no other key in an"
                + " output, found [type]."), result.errors().toString());
    }

    @Test
    void expandAllShouldRejectEveryDeclaredKeyBesideASchemaInAnOrchestratedRestOutput()
            throws Exception {
        // `format` is not a field of an output, and an `enum` that is not a list is ignored:
        // the deserializer used to drop both, so the output was accepted. Every key declared
        // beside `schema:` is kept as declared now, and rejected.
        IkanosSpec spec = rest("""
                definitions:
                  schemas:
                    crew-member:
                      type: object
                      properties:
                        fullName: { type: string }
                """, """
                crew:
                  path: "/crew"
                  operations:
                    get-lead:
                      method: GET
                      steps:
                        lead:
                          type: call
                          call: registry.get-lead
                      mappings:
                        - target: lead
                          value: "$.lead"
                      outputParameters:
                        lead: { schema: crew-member, format: date }
                        crew:
                          type: array
                          items: { schema: crew-member, pattern: "^x" }
                        deputy: { schema: crew-member, enum: lead, required: yes, items: [] }
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().contains("REST adapter 'api', resource 'crew', operation"
                + " 'get-lead', output 'lead': 'schema: crew-member' takes no other key in an"
                + " output, found [format]."), result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("found [pattern]")),
                result.errors().toString());
        // Modeled keys whose values the deserializer ignores are rejected too
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("output 'deputy'")
                && e.contains("found [enum, required, items]")), result.errors().toString());
    }

    @Test
    void expandAllShouldWarnAboutADefinitionNoResourceOrOperationUses() throws Exception {
        IkanosSpec spec = rest("""
                definitions:
                  inputParameterSets:
                    paging:
                      limit: { in: query, type: integer, description: "Maximum items." }
                """, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      call: registry.list-ships
                """);

        DefinitionsExpander.Result result = DefinitionsExpander.expandAll(spec);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(List.of("REST adapter 'api' declares the input parameter set 'paging',"
                + " which no resource or operation uses."), result.warnings());
    }

    private static IkanosSpec rest(String definitions, String resources) throws Exception {
        return IkanosMappers.yaml().readValue("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: rest
                      port: 3002
                      namespace: api
                %s      resources:
                %s""".formatted(IKANOS, definitions.indent(6), resources.indent(8)),
                IkanosSpec.class);
    }

    private static RestServerResourceSpec resource(IkanosSpec spec, String name) {
        return ((RestServerSpec) spec.getCapability().getExposes().get(0)).getResources()
                .get(name);
    }

    private static RestServerOperationSpec operation(IkanosSpec spec, String resource,
            String name) {
        return resource(spec, resource).getOperations().get(name);
    }

    private static List<String> names(List<? extends StructureSpec<?>> specs) {
        return specs.stream().map(StructureSpec::getName).toList();
    }
}
