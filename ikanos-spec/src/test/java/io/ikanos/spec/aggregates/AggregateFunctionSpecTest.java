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
package io.ikanos.spec.aggregates;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;

class AggregateFunctionSpecTest {

    private AggregateFunctionSpec spec;

    @BeforeEach
    void setUp() {
        spec = new AggregateFunctionSpec();
    }

    // ── Name ──

    @Test
    void getNameShouldReturnNullByDefault() {
        assertNull(spec.getName());
    }

    @Test
    void setNameShouldStoreValue() {
        spec.setName("list-items");
        assertEquals("list-items", spec.getName());
    }

    // ── Description ──

    @Test
    void getDescriptionShouldReturnNullByDefault() {
        assertNull(spec.getDescription());
    }

    @Test
    void setDescriptionShouldStoreValue() {
        spec.setDescription("Lists all items");
        assertEquals("Lists all items", spec.getDescription());
    }

    // ── Semantics ──

    @Test
    void getSemanticsShouldReturnNullByDefault() {
        assertNull(spec.getSemantics());
    }

    @Test
    void setSemanticsShouldStoreValue() {
        SemanticsSpec semantics = new SemanticsSpec();
        spec.setSemantics(semantics);
        assertSame(semantics, spec.getSemantics());
    }

    // ── Call ──

    @Test
    void getCallShouldReturnNullByDefault() {
        assertNull(spec.getCall());
    }

    @Test
    void setCallShouldStoreValue() {
        ServerCallSpec call = new ServerCallSpec();
        spec.setCall(call);
        assertSame(call, spec.getCall());
    }

    // ── With ──

    @Test
    void getWithShouldReturnNullByDefault() {
        assertNull(spec.getWith());
    }

    @Test
    void setWithShouldStoreImmutableCopy() {
        spec.setWith(Map.of("format", "json"));
        assertEquals(Map.of("format", "json"), spec.getWith());
    }

    @Test
    void setWithShouldAcceptNull() {
        spec.setWith(Map.of("x", "y"));
        spec.setWith(null);
        assertNull(spec.getWith());
    }

    // ── InputParameters ──

    @Test
    void getInputParametersShouldReturnEmptyListByDefault() {
        assertNotNull(spec.getInputParameters());
        assertTrue(spec.getInputParameters().isEmpty());
    }

    @Test
    void inputParametersShouldBeMutable() {
        spec.getInputParameters().add(new InputParameterSpec());
        assertEquals(1, spec.getInputParameters().size());
    }

    // ── Steps ──

    @Test
    void getStepsShouldReturnEmptyListByDefault() {
        assertNotNull(spec.getSteps());
        assertTrue(spec.getSteps().isEmpty());
    }

    @Test
    void stepsShouldBeMutable() {
        spec.getSteps().add(new OperationStepCallSpec());
        assertEquals(1, spec.getSteps().size());
    }

    // ── Mappings ──

    @Test
    void getMappingsShouldReturnEmptyListByDefault() {
        assertNotNull(spec.getMappings());
        assertTrue(spec.getMappings().isEmpty());
    }

    @Test
    void mappingsShouldBeMutable() {
        spec.getMappings().add(new StepOutputMappingSpec());
        assertEquals(1, spec.getMappings().size());
    }

    // ── OutputParameters ──

    @Test
    void getOutputParametersShouldReturnEmptyListByDefault() {
        assertNotNull(spec.getOutputParameters());
        assertTrue(spec.getOutputParameters().isEmpty());
    }

    @Test
    void outputParametersShouldBeMutable() {
        spec.getOutputParameters().add(new OutputParameterSpec());
        assertEquals(1, spec.getOutputParameters().size());
    }
}
