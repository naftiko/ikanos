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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;

class McpServerResourceSpecTest {

    private McpServerResourceSpec spec;

    @BeforeEach
    void setUp() {
        spec = new McpServerResourceSpec();
    }

    // ── Name ──

    @Test
    void getNameShouldReturnNullByDefault() {
        assertNull(spec.getName());
    }

    @Test
    void setNameShouldStoreValue() {
        spec.setName("my-resource");
        assertEquals("my-resource", spec.getName());
    }

    // ── Label ──

    @Test
    void getLabelShouldReturnNullByDefault() {
        assertNull(spec.getLabel());
    }

    @Test
    void setLabelShouldStoreValue() {
        spec.setLabel("My Resource");
        assertEquals("My Resource", spec.getLabel());
    }

    // ── URI ──

    @Test
    void getUriShouldReturnNullByDefault() {
        assertNull(spec.getUri());
    }

    @Test
    void setUriShouldStoreValue() {
        spec.setUri("resource://my-resource/{id}");
        assertEquals("resource://my-resource/{id}", spec.getUri());
    }

    // ── Description ──

    @Test
    void getDescriptionShouldReturnNullByDefault() {
        assertNull(spec.getDescription());
    }

    @Test
    void setDescriptionShouldStoreValue() {
        spec.setDescription("A test resource");
        assertEquals("A test resource", spec.getDescription());
    }

    // ── MimeType ──

    @Test
    void getMimeTypeShouldReturnNullByDefault() {
        assertNull(spec.getMimeType());
    }

    @Test
    void setMimeTypeShouldStoreValue() {
        spec.setMimeType("application/json");
        assertEquals("application/json", spec.getMimeType());
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
        Map<String, Object> original = Map.of("key", "value");
        spec.setWith(original);
        assertEquals(Map.of("key", "value"), spec.getWith());
    }

    @Test
    void setWithShouldAcceptNull() {
        spec.setWith(Map.of("key", "value"));
        spec.setWith(null);
        assertNull(spec.getWith());
    }

    // ── Steps ──

    @Test
    void getStepsShouldReturnEmptyListByDefault() {
        assertNotNull(spec.getSteps());
        assertTrue(spec.getSteps().isEmpty());
    }

    @Test
    void getStepsShouldBeMutable() {
        OperationStepCallSpec step = new OperationStepCallSpec();
        spec.getSteps().add(step);
        assertEquals(1, spec.getSteps().size());
    }

    // ── OutputParameters ──

    @Test
    void getOutputParametersShouldReturnEmptyListByDefault() {
        assertNotNull(spec.getOutputParameters());
        assertTrue(spec.getOutputParameters().isEmpty());
    }

    @Test
    void getOutputParametersShouldBeMutable() {
        OutputParameterSpec param = new OutputParameterSpec();
        spec.getOutputParameters().add(param);
        assertEquals(1, spec.getOutputParameters().size());
    }

    // ── Location ──

    @Test
    void getLocationShouldReturnNullByDefault() {
        assertNull(spec.getLocation());
    }

    @Test
    void setLocationShouldStoreValue() {
        spec.setLocation("file:///data/reports");
        assertEquals("file:///data/reports", spec.getLocation());
    }

    // ── isStatic ──

    @Test
    void isStaticShouldReturnFalseWhenLocationIsNull() {
        assertFalse(spec.isStatic());
    }

    @Test
    void isStaticShouldReturnTrueWhenLocationIsSet() {
        spec.setLocation("file:///data");
        assertTrue(spec.isStatic());
    }

    // ── isTemplate ──

    @Test
    void isTemplateShouldReturnFalseWhenUriIsNull() {
        assertFalse(spec.isTemplate());
    }

    @Test
    void isTemplateShouldReturnFalseWhenUriHasNoPlaceholders() {
        spec.setUri("resource://my-resource/fixed");
        assertFalse(spec.isTemplate());
    }

    @Test
    void isTemplateShouldReturnTrueWhenUriContainsCurlyBracePlaceholders() {
        spec.setUri("resource://my-resource/{id}");
        assertTrue(spec.isTemplate());
    }

    @Test
    void isTemplateShouldReturnFalseWhenUriHasOnlyOpenBrace() {
        spec.setUri("resource://my-resource/{id");
        assertFalse(spec.isTemplate());
    }

    @Test
    void isTemplateShouldReturnFalseWhenUriHasOnlyCloseBrace() {
        spec.setUri("resource://my-resource/id}");
        assertFalse(spec.isTemplate());
    }
}
