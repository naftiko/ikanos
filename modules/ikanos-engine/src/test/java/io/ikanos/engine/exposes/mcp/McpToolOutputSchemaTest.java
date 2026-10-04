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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;

/**
 * Unit tests for {@link McpToolOutputSchema} — derivation of the MCP tool {@code outputSchema}
 * from declared {@code outputParameters}.
 */
class McpToolOutputSchemaTest {

    @Test
    void forCallShouldDescribeSingleObjectRootWithNullableProperties() {
        OutputParameterSpec root = object(null,
                scalar("imo", "string", "$.imo_number"),
                scalar("tonnage", "number", "$.gross_tonnage"),
                scalar("active", "boolean", "$.active"));

        Map<String, Object> schema = McpToolOutputSchema.forCall(List.of(root));

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
        Map<String, Object> properties = properties(schema);
        assertEquals(List.of("imo", "tonnage", "active"), List.copyOf(properties.keySet()));
        assertEquals(List.of("string", "null"), property(properties, "imo").get("type"));
        assertEquals(List.of("number", "null"), property(properties, "tonnage").get("type"));
        assertEquals(List.of("boolean", "null"), property(properties, "active").get("type"));
    }

    @Test
    void forCallShouldRequireEveryRootPropertyBecauseTheResolverAlwaysEmitsEveryKey() {
        OutputParameterSpec root = object(null,
                scalar("imo", "string", "$.imo_number"),
                scalar("tonnage", "number", "$.gross_tonnage"));

        Map<String, Object> schema = McpToolOutputSchema.forCall(List.of(root));

        assertEquals(List.of("imo", "tonnage"), schema.get("required"),
                "unresolved mappings are emitted as null, so every key is always present");
    }

    @Test
    void forCallShouldRecurseIntoUnmappedNestedObjects() {
        OutputParameterSpec specs = object("specs",
                scalar("yearBuilt", "number", "$.year_built"));
        OutputParameterSpec root = object(null, scalar("name", "string", "$.vessel_name"), specs);

        Map<String, Object> schema = McpToolOutputSchema.forCall(List.of(root));

        Map<String, Object> specsSchema = property(properties(schema), "specs");
        assertEquals(List.of("object", "null"), specsSchema.get("type"));
        assertEquals(List.of("number", "null"),
                property(properties(specsSchema), "yearBuilt").get("type"));
        assertEquals(List.of("yearBuilt"), specsSchema.get("required"));
    }

    @Test
    void forCallShouldDescribeMappedNestedObjectWithProperties() {
        OutputParameterSpec dimensions = object("dimensions",
                scalar("length", "number", "$.length_overall"));
        dimensions.setMapping("$.dimensions");
        OutputParameterSpec root = object(null, dimensions);

        Map<String, Object> dimensionsSchema =
                property(properties(McpToolOutputSchema.forCall(List.of(root))), "dimensions");

        assertEquals(List.of("object", "null"), dimensionsSchema.get("type"));
        assertEquals(List.of("number", "null"),
                property(properties(dimensionsSchema), "length").get("type"));
        assertEquals(List.of("length"), dimensionsSchema.get("required"));
    }

    @Test
    void forCallShouldDescribeObjectItemsOfMappedArrays() {
        OutputParameterSpec crew = new OutputParameterSpec("crew", "array", null, "$.crew");
        crew.setItems(object(null, scalar("name", "string", "$.full_name")));
        OutputParameterSpec root = object(null, crew);

        Map<String, Object> crewSchema =
                property(properties(McpToolOutputSchema.forCall(List.of(root))), "crew");

        assertEquals(List.of("array", "null"), crewSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) crewSchema.get("items");
        assertEquals("object", items.get("type"),
                "the resolver always assembles an object per element, never null");
        assertEquals(List.of("string", "null"), property(properties(items), "name").get("type"));
        assertEquals(List.of("name"), items.get("required"));
    }

    @Test
    void forCallShouldDescribeScalarItemsOfMappedArrays() {
        OutputParameterSpec ids = new OutputParameterSpec("crewIds", "array", null, "$.crewIds");
        ids.setItems(scalar(null, "string", "$."));
        OutputParameterSpec root = object(null, ids);

        Map<String, Object> idsSchema =
                property(properties(McpToolOutputSchema.forCall(List.of(root))), "crewIds");

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) idsSchema.get("items");
        assertEquals(List.of("string", "null"), items.get("type"));
    }

    @Test
    void forCallShouldDescribeNestedValuesMapAsAdditionalProperties() {
        OutputParameterSpec ports = new OutputParameterSpec("ports", "object", null, "$.ports");
        ports.setValues(scalar(null, "string", "$.name"));
        OutputParameterSpec root = object(null, ports);

        Map<String, Object> portsSchema =
                property(properties(McpToolOutputSchema.forCall(List.of(root))), "ports");

        assertEquals(List.of("object", "null"), portsSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) portsSchema.get("additionalProperties");
        assertEquals(List.of("string", "null"), values.get("type"));
        assertFalse(portsSchema.containsKey("required"));
    }

    @Test
    void forCallShouldDescribeNestedStaticValueAsString() {
        OutputParameterSpec source = scalar("source", "number", null);
        source.setValue("registry");
        OutputParameterSpec root = object(null, scalar("imo", "string", "$.imo"), source);

        Map<String, Object> sourceSchema =
                property(properties(McpToolOutputSchema.forCall(List.of(root))), "source");

        assertEquals("string", sourceSchema.get("type"),
                "static values are always emitted as non-null text, whatever the declared type");
    }

    @Test
    void forCallShouldKeepDescriptions() {
        OutputParameterSpec imo = scalar("imo", "string", "$.imo_number");
        imo.setDescription("IMO number");
        OutputParameterSpec root = object(null, imo);
        root.setDescription("A ship");

        Map<String, Object> schema = McpToolOutputSchema.forCall(List.of(root));

        assertEquals("A ship", schema.get("description"));
        assertEquals("IMO number", property(properties(schema), "imo").get("description"));
    }

    @Test
    void forCallShouldReturnNullWhenRootIsAnArray() {
        OutputParameterSpec root = new OutputParameterSpec(null, "array", null, "$.");
        root.setItems(object(null, scalar("imo", "string", "$.imo_number")));

        assertNull(McpToolOutputSchema.forCall(List.of(root)));
    }

    @Test
    void forCallShouldReturnNullWhenRootIsAScalar() {
        assertNull(McpToolOutputSchema.forCall(List.of(scalar(null, "string", "$.forecast"))));
    }

    @Test
    void forCallShouldReturnNullWhenSeveralRootParametersAreDeclared() {
        assertNull(McpToolOutputSchema.forCall(List.of(
                object(null, scalar("a", "string", "$.a")),
                object(null, scalar("b", "string", "$.b")))));
    }

    @Test
    void forCallShouldReturnNullWhenNoOutputParametersAreDeclared() {
        assertNull(McpToolOutputSchema.forCall(List.of()));
        assertNull(McpToolOutputSchema.forCall(null));
    }

    @Test
    void forCallShouldReturnNullForValuesMapRoot() {
        OutputParameterSpec root = new OutputParameterSpec(null, "object", null, "$.ships");
        root.setValues(scalar(null, "string", "$.name"));

        assertNull(McpToolOutputSchema.forCall(List.of(root)));
    }

    @Test
    void forStepsShouldDescribeNamedOutputParameters() {
        StepOutputMappingSpec mapping = new StepOutputMappingSpec();
        mapping.setTarget("voyageId");
        mapping.setValue("$.get-voyage.voyageId");

        Map<String, Object> schema = McpToolOutputSchema.forSteps(
                List.of(scalar("voyageId", "string", null)), List.of(mapping));

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("string", "null"),
                property(properties(schema), "voyageId").get("type"));
    }

    @Test
    void forStepsShouldRequireNothingBecauseUnresolvedMappingsAreOmitted() {
        StepOutputMappingSpec mapping = new StepOutputMappingSpec();
        mapping.setTarget("voyageId");
        mapping.setValue("$.get-voyage.voyageId");

        Map<String, Object> schema = McpToolOutputSchema.forSteps(
                List.of(scalar("voyageId", "string", null)), List.of(mapping));

        assertFalse(schema.containsKey("required"));
    }

    @Test
    void forStepsShouldNotDescribeArrayItemsBecauseStepValuesAreCopiedUnshaped() {
        StepOutputMappingSpec mapping = new StepOutputMappingSpec();
        mapping.setTarget("crew");
        mapping.setValue("$.get-ship.crew");
        OutputParameterSpec crew = new OutputParameterSpec("crew", "array", null, null);
        crew.setItems(object(null, scalar("name", "string", null)));

        Map<String, Object> crewSchema = property(properties(
                McpToolOutputSchema.forSteps(List.of(crew), List.of(mapping))), "crew");

        assertEquals(List.of("array", "null"), crewSchema.get("type"));
        assertFalse(crewSchema.containsKey("items"));
    }

    @Test
    void resolveShouldReturnCallContractForSimpleCallTool() {
        McpServerToolSpec tool = new McpServerToolSpec("get-ship", null, "Get a ship");
        tool.setCall(new ServerCallSpec("registry.get-ship"));
        tool.setOutputParameters(List.of(object(null, scalar("imo", "string", "$.imo"))));

        McpToolOutputSchema.Contract contract = McpToolOutputSchema.resolve(tool, null);

        assertNotNull(contract);
        assertEquals(McpToolOutputSchema.Source.CALL, contract.source());
    }

    @Test
    void resolveShouldReturnNullForMockTool() {
        McpServerToolSpec tool = new McpServerToolSpec("hello", null, "Say hello");
        OutputParameterSpec greeting = new OutputParameterSpec("greeting", "string", null, null);
        greeting.setValue("Hello");
        tool.setOutputParameters(List.of(greeting));

        assertNull(McpToolOutputSchema.resolve(tool, null),
                "mock values are emitted as strings, so no typed schema is advertised");
    }

    @Test
    void resolveShouldReturnNullForRefWithoutCapability() {
        McpServerToolSpec tool = new McpServerToolSpec("get-forecast", null, "Forecast");
        tool.setRef("forecast.get-forecast");

        assertNull(McpToolOutputSchema.resolve(tool, null));
    }

    @Test
    void propertySchemaShouldLeaveUnknownTypeUnconstrained() {
        Map<String, Object> schema =
                McpToolOutputSchema.propertySchema(scalar("x", "datetime", "$.x"));

        assertTrue(schema.isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private static OutputParameterSpec scalar(String name, String type, String mapping) {
        return new OutputParameterSpec(name, type, null, mapping);
    }

    private static OutputParameterSpec object(String name, OutputParameterSpec... properties) {
        OutputParameterSpec spec = new OutputParameterSpec(name, "object", null, null);
        spec.getProperties().addAll(List.of(properties));
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> properties, String name) {
        return (Map<String, Object>) properties.get(name);
    }
}
