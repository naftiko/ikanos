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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;

class McpServerToolSpecTest {

    // ── Default constructor ──

    @Test
    void defaultConstructorShouldInitializeWithNullScalars() {
        McpServerToolSpec spec = new McpServerToolSpec();
        assertNull(spec.getName());
        assertNull(spec.getLabel());
        assertNull(spec.getDescription());
    }

    @Test
    void defaultConstructorShouldInitializeEmptyLists() {
        McpServerToolSpec spec = new McpServerToolSpec();
        assertNotNull(spec.getInputParameters());
        assertTrue(spec.getInputParameters().isEmpty());
        assertNotNull(spec.getSteps());
        assertTrue(spec.getSteps().isEmpty());
        assertNotNull(spec.getMappings());
        assertTrue(spec.getMappings().isEmpty());
        assertNotNull(spec.getOutputParameters());
        assertTrue(spec.getOutputParameters().isEmpty());
    }

    // ── Parameterised constructor ──

    @Test
    void parameterisedConstructorShouldSetNameLabelDescription() {
        McpServerToolSpec spec = new McpServerToolSpec("lookup", "Lookup", "Look up a value");
        assertEquals("lookup", spec.getName());
        assertEquals("Lookup", spec.getLabel());
        assertEquals("Look up a value", spec.getDescription());
    }

    // ── Name ──

    @Test
    void setNameShouldOverwriteValue() {
        McpServerToolSpec spec = new McpServerToolSpec("old", null, null);
        spec.setName("new-name");
        assertEquals("new-name", spec.getName());
    }

    // ── Label ──

    @Test
    void setLabelShouldStoreValue() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.setLabel("My Tool");
        assertEquals("My Tool", spec.getLabel());
    }

    // ── Description ──

    @Test
    void setDescriptionShouldStoreValue() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.setDescription("Performs an action");
        assertEquals("Performs an action", spec.getDescription());
    }

    // ── Call ──

    @Test
    void getCallShouldReturnNullByDefault() {
        McpServerToolSpec spec = new McpServerToolSpec();
        assertNull(spec.getCall());
    }

    @Test
    void setCallShouldStoreValue() {
        McpServerToolSpec spec = new McpServerToolSpec();
        ServerCallSpec call = new ServerCallSpec();
        spec.setCall(call);
        assertSame(call, spec.getCall());
    }

    // ── With ──

    @Test
    void getWithShouldReturnNullByDefault() {
        McpServerToolSpec spec = new McpServerToolSpec();
        assertNull(spec.getWith());
    }

    @Test
    void setWithShouldStoreImmutableCopy() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.setWith(Map.of("param", "value"));
        assertEquals(Map.of("param", "value"), spec.getWith());
    }

    @Test
    void setWithShouldAcceptNull() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.setWith(Map.of("param", "value"));
        spec.setWith(null);
        assertNull(spec.getWith());
    }

    // ── Hints ──

    @Test
    void getHintsShouldReturnNullByDefault() {
        McpServerToolSpec spec = new McpServerToolSpec();
        assertNull(spec.getHints());
    }

    @Test
    void setHintsShouldStoreValue() {
        McpServerToolSpec spec = new McpServerToolSpec();
        McpToolHintsSpec hints = new McpToolHintsSpec();
        spec.setHints(hints);
        assertSame(hints, spec.getHints());
    }

    // ── Ref ──

    @Test
    void getRefShouldReturnNullByDefault() {
        McpServerToolSpec spec = new McpServerToolSpec();
        assertNull(spec.getRef());
    }

    @Test
    void setRefShouldStoreValue() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.setRef("my-aggregate.my-function");
        assertEquals("my-aggregate.my-function", spec.getRef());
    }

    // ── List mutability ──

    @Test
    void inputParametersShouldBeMutable() {
        McpServerToolSpec spec = new McpServerToolSpec();
        InputParameterSpec param = new InputParameterSpec();
        param.setName("p1");
        spec.setInputParameters(Map.of("p1", param));
        assertEquals(1, spec.getInputParameters().size());
    }

    @Test
    void stepsShouldBeMutable() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.getSteps().put("step1", new OperationStepCallSpec());
        assertEquals(1, spec.getSteps().size());
    }

    @Test
    void mappingsShouldBeMutable() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.getMappings().add(new StepOutputMappingSpec());
        assertEquals(1, spec.getMappings().size());
    }

    @Test
    void outputParametersShouldBeMutable() {
        McpServerToolSpec spec = new McpServerToolSpec();
        spec.getOutputParameters().add(new OutputParameterSpec());
        assertEquals(1, spec.getOutputParameters().size());
    }
}
