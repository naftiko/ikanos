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
package io.naftiko.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import io.naftiko.spec.AggregateFunctionSpec;
import io.naftiko.spec.AggregateSpec;
import io.naftiko.spec.CapabilitySpec;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.SemanticsSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import io.naftiko.spec.exposes.McpToolHintsSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.exposes.StepOutputMappingSpec;

/**
 * Unit tests for AggregateRefResolver — ref resolution, merge semantics, hints derivation.
 */
public class AggregateRefResolverTest {

    private AggregateRefResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AggregateRefResolver();
    }

    // ── buildFunctionMap ──

    @Test
    void buildFunctionMapShouldIndexByNamespaceAndName() {
        CapabilitySpec cap = new CapabilitySpec();
        AggregateSpec agg = new AggregateSpec();
        agg.setNamespace("forecast");
        agg.setLabel("Forecast");

        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-forecast");
        fn.setDescription("Get forecast");
        agg.getFunctions().add(fn);
        cap.getAggregates().add(agg);

        Map<String, AggregateFunctionSpec> map = resolver.buildFunctionMap(cap);

        assertEquals(1, map.size());
        assertTrue(map.containsKey("forecast.get-forecast"));
        assertSame(fn, map.get("forecast.get-forecast"));
    }

    @Test
    void buildFunctionMapShouldFailOnDuplicateRef() {
        CapabilitySpec cap = new CapabilitySpec();

        AggregateSpec agg1 = new AggregateSpec();
        agg1.setNamespace("data");
        agg1.setLabel("Data1");
        AggregateFunctionSpec fn1 = new AggregateFunctionSpec();
        fn1.setName("read");
        fn1.setDescription("Read1");
        agg1.getFunctions().add(fn1);

        AggregateSpec agg2 = new AggregateSpec();
        agg2.setNamespace("data");
        agg2.setLabel("Data2");
        AggregateFunctionSpec fn2 = new AggregateFunctionSpec();
        fn2.setName("read");
        fn2.setDescription("Read2");
        agg2.getFunctions().add(fn2);

        cap.getAggregates().add(agg1);
        cap.getAggregates().add(agg2);

        assertThrows(IllegalArgumentException.class, () -> resolver.buildFunctionMap(cap));
    }

    // ── lookupFunction ──

    @Test
    void lookupFunctionShouldFailOnUnknownRef() {
        Map<String, AggregateFunctionSpec> map = new HashMap<>();
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("read");
        map.put("data.read", fn);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.lookupFunction("unknown.nonexistent", map));
        assertTrue(ex.getMessage().contains("unknown.nonexistent"));
    }

    // ── MCP tool ref merge ──

    @Test
    void resolveMcpToolRefShouldInheritNameFromFunction() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec(null, null, null);
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertEquals("get-data", tool.getName());
    }

    @Test
    void resolveMcpToolRefShouldNotOverrideExplicitName() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("custom-name", null, null);
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertEquals("custom-name", tool.getName());
    }

    @Test
    void resolveMcpToolRefShouldInheritCallFromFunction() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");
        fn.setCall(new ServerCallSpec("mock-api.get-data"));

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("get-data", null, "Get data");
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertNotNull(tool.getCall());
        assertEquals("mock-api.get-data", tool.getCall().getOperation());
    }

    @Test
    void resolveMcpToolRefShouldNotOverrideExplicitCall() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");
        fn.setCall(new ServerCallSpec("mock-api.get-data"));

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("get-data", null, "Get data");
        tool.setRef("data.get-data");
        tool.setCall(new ServerCallSpec("other-api.get-data"));

        resolver.resolveMcpToolRef(tool, map);

        assertEquals("other-api.get-data", tool.getCall().getOperation());
    }

    @Test
    void resolveMcpToolRefShouldInheritInputParameters() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");
        InputParameterSpec param = new InputParameterSpec();
        param.setName("location");
        param.setType("string");
        fn.getInputParameters().add(param);

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("get-data", null, "Get data");
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertEquals(1, tool.getInputParameters().size());
        assertEquals("location", tool.getInputParameters().get(0).getName());
    }

    @Test
    void resolveMcpToolRefShouldInheritMappings() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");
        fn.getMappings().add(new StepOutputMappingSpec("result", "$.lookup.data"));

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("get-data", null, "Get data");
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertEquals(1, tool.getMappings().size());
        assertEquals("result", tool.getMappings().get(0).getTargetName());
        assertEquals("$.lookup.data", tool.getMappings().get(0).getValue());
    }

    @Test
    void resolveMcpToolRefShouldInheritDescription() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Function description");

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("get-data", null, null);
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertEquals("Function description", tool.getDescription());
    }

    @Test
    void resolveMcpToolRefShouldNotOverrideExplicitDescription() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Function description");

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        McpServerToolSpec tool = new McpServerToolSpec("get-data", null, "Tool description");
        tool.setRef("data.get-data");

        resolver.resolveMcpToolRef(tool, map);

        assertEquals("Tool description", tool.getDescription());
    }

    // ── REST operation ref merge ──

    @Test
    void resolveRestOperationRefShouldInheritNameFromFunction() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setRef("data.get-data");

        resolver.resolveRestOperationRef(op, map);

        assertEquals("get-data", op.getName());
    }

    @Test
    void resolveRestOperationRefShouldInheritCallFromFunction() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");
        fn.setCall(new ServerCallSpec("mock-api.get-data"));

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setRef("data.get-data");

        resolver.resolveRestOperationRef(op, map);

        assertNotNull(op.getCall());
        assertEquals("mock-api.get-data", op.getCall().getOperation());
    }

    @Test
    void resolveRestOperationRefShouldInheritMappings() {
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("get-data");
        fn.setDescription("Get data");
        fn.getMappings().add(new StepOutputMappingSpec("result", "$.lookup.data"));

        Map<String, AggregateFunctionSpec> map = Map.of("data.get-data", fn);

        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setRef("data.get-data");

        resolver.resolveRestOperationRef(op, map);

        assertEquals(1, op.getMappings().size());
        assertEquals("result", op.getMappings().get(0).getTargetName());
        assertEquals("$.lookup.data", op.getMappings().get(0).getValue());
    }

    // ── deriveHints ──

    @Test
    void deriveHintsShouldMapSafeToReadOnlyAndNotDestructive() {
        SemanticsSpec semantics = new SemanticsSpec(true, null, null);

        McpToolHintsSpec hints = resolver.deriveHints(semantics);

        assertEquals(true, hints.getReadOnly());
        assertEquals(false, hints.getDestructive());
        assertNull(hints.getIdempotent());
        assertNull(hints.getOpenWorld());
    }

    @Test
    void deriveHintsShouldDefaultReadOnlyFalseWhenSafeFalse() {
        SemanticsSpec semantics = new SemanticsSpec(false, null, null);

        McpToolHintsSpec hints = resolver.deriveHints(semantics);

        assertEquals(false, hints.getReadOnly());
        assertNull(hints.getDestructive());
    }

    @Test
    void deriveHintsShouldNotSetReadOnlyWhenSafeAbsent() {
        SemanticsSpec semantics = new SemanticsSpec(null, true, null);

        McpToolHintsSpec hints = resolver.deriveHints(semantics);

        assertNull(hints.getReadOnly());
        assertEquals(true, hints.getIdempotent());
    }

    @Test
    void deriveHintsShouldMapIdempotentDirectly() {
        SemanticsSpec semantics = new SemanticsSpec(null, true, null);

        McpToolHintsSpec hints = resolver.deriveHints(semantics);

        assertEquals(true, hints.getIdempotent());
    }

    @Test
    void deriveHintsShouldNotMapCacheable() {
        SemanticsSpec semantics = new SemanticsSpec(null, null, true);

        McpToolHintsSpec hints = resolver.deriveHints(semantics);

        assertNull(hints.getReadOnly());
        assertNull(hints.getIdempotent());
        assertNull(hints.getOpenWorld());
        assertNull(hints.getDestructive());
    }

    @Test
    void deriveHintsShouldNotSetOpenWorld() {
        SemanticsSpec semantics = new SemanticsSpec(true, true, true);

        McpToolHintsSpec hints = resolver.deriveHints(semantics);

        assertNull(hints.getOpenWorld());
    }

    // ── mergeHints ──

    @Test
    void mergeHintsShouldReturnDerivedWhenExplicitIsNull() {
        McpToolHintsSpec derived = new McpToolHintsSpec(true, false, null, null);

        McpToolHintsSpec result = resolver.mergeHints(derived, null);

        assertSame(derived, result);
    }

    @Test
    void explicitHintsShouldOverrideDerived() {
        McpToolHintsSpec derived = new McpToolHintsSpec(true, false, true, null);
        McpToolHintsSpec explicit = new McpToolHintsSpec(false, null, null, null);

        McpToolHintsSpec result = resolver.mergeHints(derived, explicit);

        assertEquals(false, result.getReadOnly());
        assertEquals(false, result.getDestructive()); // not overridden
        assertEquals(true, result.getIdempotent()); // not overridden
    }

    @Test
    void explicitHintsShouldMergeOpenWorldWithDerived() {
        McpToolHintsSpec derived = new McpToolHintsSpec(true, false, null, null);
        McpToolHintsSpec explicit = new McpToolHintsSpec(null, null, null, true);

        McpToolHintsSpec result = resolver.mergeHints(derived, explicit);

        assertEquals(true, result.getReadOnly()); // from derived
        assertEquals(false, result.getDestructive()); // from derived
        assertEquals(true, result.getOpenWorld()); // from explicit
    }

    // ── resolve (full pipeline) ──

    @Test
    void resolveShouldSkipWhenNoAggregates() {
        NaftikoSpec spec = new NaftikoSpec("1.0.0-alpha1", null, new CapabilitySpec());

        // Should not throw
        resolver.resolve(spec);
    }

    @Test
    void resolveShouldSkipWhenCapabilityIsNull() {
        NaftikoSpec spec = new NaftikoSpec();

        // Should not throw
        resolver.resolve(spec);
    }

    @Test
    void resolveShouldDeriveHintsOnMcpToolFromSemantics() {
        NaftikoSpec spec = buildSpecWithMcpRef(
                new SemanticsSpec(true, true, null), null);

        resolver.resolve(spec);

        McpServerSpec mcpSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
        McpServerToolSpec tool = mcpSpec.getTools().get(0);

        assertEquals(true, tool.getHints().getReadOnly());
        assertEquals(false, tool.getHints().getDestructive());
        assertEquals(true, tool.getHints().getIdempotent());
        assertNull(tool.getHints().getOpenWorld());
    }

    @Test
    void resolveShouldMergeExplicitHintsOverDerived() {
        McpToolHintsSpec explicitHints = new McpToolHintsSpec(null, null, null, true);
        NaftikoSpec spec = buildSpecWithMcpRef(
                new SemanticsSpec(true, true, null), explicitHints);

        resolver.resolve(spec);

        McpServerSpec mcpSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
        McpServerToolSpec tool = mcpSpec.getTools().get(0);

        assertEquals(true, tool.getHints().getReadOnly());
        assertEquals(false, tool.getHints().getDestructive());
        assertEquals(true, tool.getHints().getIdempotent());
        assertEquals(true, tool.getHints().getOpenWorld());
    }

    // ── helpers ──

    private NaftikoSpec buildSpecWithMcpRef(SemanticsSpec semantics,
            McpToolHintsSpec explicitHints) {
        CapabilitySpec cap = new CapabilitySpec();

        AggregateSpec agg = new AggregateSpec();
        agg.setNamespace("data");
        agg.setLabel("Data");
        AggregateFunctionSpec fn = new AggregateFunctionSpec();
        fn.setName("read");
        fn.setDescription("Read data");
        fn.setSemantics(semantics);
        fn.setCall(new ServerCallSpec("mock.read"));
        agg.getFunctions().add(fn);
        cap.getAggregates().add(agg);

        McpServerSpec mcpSpec = new McpServerSpec();
        mcpSpec.setNamespace("test-mcp");
        McpServerToolSpec tool = new McpServerToolSpec("read", null, "Read data");
        tool.setRef("data.read");
        if (explicitHints != null) {
            tool.setHints(explicitHints);
        }
        mcpSpec.getTools().add(tool);
        cap.getExposes().add(mcpSpec);

        return new NaftikoSpec("1.0.0-alpha1", null, cap);
    }

}
