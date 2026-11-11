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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * Reading and writing shared {@code definitions} (#910): they must survive the YAML round trip
 * an import performs, so that an imported adapter or aggregate carries them.
 */
class DefinitionsDeserializationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private final ObjectMapper yaml = IkanosMappers.yaml();

    private static final String DOCUMENT = """
            ikanos: "%s"
            capability:
              aggregates:
                - display: "Orders"
                  namespace: orders
                  definitions:
                    schemas:
                      line: { type: object, properties: { sku: { type: string } } }
                  flows:
                    list-lines:
                      description: "List lines."
                      steps:
                        lines: { type: call, call: erp.list-lines }
                      mappings:
                        - target: lines
                          value: "$.lines"
                      outputParameters:
                        lines:
                          type: array
                          items: { schema: line }
              exposes:
                - type: mcp
                  port: 3000
                  namespace: tools
                  definitions:
                    inputParameterSets:
                      odata-list:
                        filter: { type: string, required: false, description: "Filter." }
                    schemas:
                      date-range:
                        type: object
                        properties:
                          from: { type: string }
                        required: [from]
                  tools:
                    list-orders:
                      description: "List orders."
                      inputParameterSets: [odata-list]
                      inputParameters:
                        period: { schema: date-range, description: "Period." }
                      call: erp.list-orders
            """.formatted(IKANOS);

    @Test
    void readValueShouldReadDefinitionsAndTheirUses() throws Exception {
        IkanosSpec spec = yaml.readValue(DOCUMENT, IkanosSpec.class);

        McpServerSpec mcp = (McpServerSpec) spec.getCapability().getExposes().get(0);
        InputParameterSpec filter =
                mcp.getDefinitions().getInputParameterSets().get("odata-list").get("filter");
        assertEquals("filter", filter.getName());
        assertFalse(filter.isRequired());
        assertEquals(List.of("from"),
                mcp.getDefinitions().getSchemas().get("date-range").getRequired());
        McpServerToolSpec tool = mcp.getTools().get("list-orders");
        assertEquals(List.of("odata-list"), tool.getInputParameterSets());
        assertEquals("date-range", tool.getInputParameters().get(0).getSchema());
        AggregateSpec aggregate = spec.getCapability().getAggregates().get("orders");
        assertEquals("line", aggregate.getFlows().get("list-lines").getOutputParameters().get(0)
                .getItems().getSchema());
    }

    @Test
    void writeValueShouldKeepDefinitionsAndTheirUsesAcrossARoundTrip() throws Exception {
        IkanosSpec spec = yaml.readValue(DOCUMENT, IkanosSpec.class);
        McpServerSpec original = (McpServerSpec) spec.getCapability().getExposes().get(0);

        // The adapter is not copied whole: McpServerSpec does not survive the round trip on
        // main yet (its `stdio` getter is written out), independently of definitions.
        DefinitionsSpec definitions = yaml.readValue(
                yaml.writeValueAsBytes(original.getDefinitions()), DefinitionsSpec.class);
        McpServerToolSpec tool = yaml.readValue(
                yaml.writeValueAsBytes(original.getTools().get("list-orders")),
                McpServerToolSpec.class);
        AggregateSpec aggregate = yaml.readValue(
                yaml.writeValueAsBytes(spec.getCapability().getAggregates().get("orders")),
                AggregateSpec.class);

        assertEquals(List.of("odata-list"),
                List.copyOf(definitions.getInputParameterSets().keySet()));
        assertEquals("filter",
                definitions.getInputParameterSets().get("odata-list").get("filter").getName());
        assertEquals(List.of("from"), definitions.getSchemas().get("date-range").getRequired());
        assertEquals(List.of("odata-list"), tool.getInputParameterSets());
        assertEquals("date-range", tool.getInputParameters().get(0).getSchema());
        assertEquals(List.of("line"), List.copyOf(aggregate.getDefinitions().getSchemas().keySet()));
        assertEquals("line", aggregate.getFlows().get("list-lines").getOutputParameters().get(0)
                .getItems().getSchema());
    }
}
