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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

class InputParameterDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(InputParameterSpec.class, new InputParameterDeserializer());
        mapper.registerModule(module);
    }

    // ── Basic scalar fields ──

    @Test
    void deserializeShouldMapNameField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"user-id\"}", InputParameterSpec.class);
        assertEquals("user-id", result.getName());
    }

    @Test
    void deserializeShouldMapTypeField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"type\": \"string\"}", InputParameterSpec.class);
        assertEquals("string", result.getType());
    }

    @Test
    void deserializeShouldMapInField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"in\": \"query\"}", InputParameterSpec.class);
        assertEquals("query", result.getIn());
    }

    @Test
    void deserializeShouldMapTemplateField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"template\": \"{{value}}\"}", InputParameterSpec.class);
        assertEquals("{{value}}", result.getTemplate());
    }

    @Test
    void deserializeShouldMapConstField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"const\": \"fixed\"}", InputParameterSpec.class);
        assertEquals("fixed", result.getConstant());
    }

    @Test
    void deserializeShouldMapValueField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"value\": \"default-val\"}", InputParameterSpec.class);
        assertEquals("default-val", result.getValue());
    }

    @Test
    void deserializeShouldMapSelectorField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"selector\": \"$.data.id\"}", InputParameterSpec.class);
        assertEquals("$.data.id", result.getSelector());
    }

    @Test
    void deserializeShouldMapDescriptionField() throws IOException {
        InputParameterSpec result = mapper.readValue(
                "{\"name\": \"x\", \"description\": \"A parameter\"}", InputParameterSpec.class);
        assertEquals("A parameter", result.getDescription());
    }

    // ── Items: array form ──

    @Test
    void deserializeShouldMapItemsFromArray() throws IOException {
        String json = "{\"name\": \"tags\", \"type\": \"array\", "
                + "\"items\": [{\"name\": \"tag\", \"type\": \"string\"}]}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertNotNull(result.getItems());
        assertEquals("tag", result.getItems().getName());
        assertEquals("string", result.getItems().getType());
    }

    @Test
    void deserializeShouldIgnoreItemsWhenArrayIsEmpty() throws IOException {
        String json = "{\"name\": \"tags\", \"type\": \"array\", \"items\": []}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertNull(result.getItems());
    }

    // ── Items: object form ──

    @Test
    void deserializeShouldMapItemsFromObject() throws IOException {
        String json = "{\"name\": \"tags\", \"type\": \"array\", "
                + "\"items\": {\"name\": \"tag\", \"type\": \"string\"}}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertNotNull(result.getItems());
        assertEquals("tag", result.getItems().getName());
    }

    // ── Values (map types) ──

    @Test
    void deserializeShouldMapValuesField() throws IOException {
        String json = "{\"name\": \"headers\", \"type\": \"object\", "
                + "\"values\": {\"type\": \"string\"}}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertNotNull(result.getValues());
        assertEquals("string", result.getValues().getType());
    }

    // ── Properties (object type) ──

    @Test
    void deserializeShouldMapPropertiesWithInjectedNames() throws IOException {
        String json = "{\"name\": \"address\", \"type\": \"object\", "
                + "\"properties\": {\"city\": {\"type\": \"string\"}, "
                + "\"zip\": {\"type\": \"number\"}}}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals(2, result.getProperties().size());
        assertEquals("city", result.getProperties().get(0).getName());
        assertEquals("string", result.getProperties().get(0).getType());
        assertEquals("zip", result.getProperties().get(1).getName());
        assertEquals("number", result.getProperties().get(1).getType());
    }

    // ── Required: boolean form ──

    @Test
    void deserializeShouldMapRequiredAsBoolean() throws IOException {
        String json = "{\"name\": \"x\", \"required\": true}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertTrue(result.isRequired());
    }

    @Test
    void deserializeShouldMapRequiredAsFalse() throws IOException {
        String json = "{\"name\": \"x\", \"required\": false}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertFalse(result.isRequired());
    }

    // ── Required: array form (JSON Schema object listing) ──

    @Test
    void deserializeShouldMapRequiredAsArray() throws IOException {
        String json = "{\"name\": \"obj\", \"type\": \"object\", "
                + "\"required\": [\"name\", \"email\"]}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals(2, result.getRequired().size());
        assertTrue(result.getRequired().contains("name"));
        assertTrue(result.getRequired().contains("email"));
    }

    // ── Enum ──

    @Test
    void deserializeShouldMapEnumArray() throws IOException {
        String json = "{\"name\": \"status\", \"enum\": [\"active\", \"inactive\", \"pending\"]}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals(3, result.getEnumeration().size());
        assertEquals("active", result.getEnumeration().get(0));
    }

    // ── Numeric properties ──

    @Test
    void deserializeShouldMapPrecision() throws IOException {
        String json = "{\"name\": \"amount\", \"type\": \"number\", \"precision\": 10}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals(10, result.getPrecision());
    }

    @Test
    void deserializeShouldMapScale() throws IOException {
        String json = "{\"name\": \"amount\", \"type\": \"number\", \"scale\": 2}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals(2, result.getScale());
    }

    @Test
    void deserializeShouldMapMaxLength() throws IOException {
        String json = "{\"name\": \"code\", \"type\": \"string\", \"maxLength\": \"255\"}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals("255", result.getMaxLength());
    }

    // ── Content fields ──

    @Test
    void deserializeShouldMapContentEncoding() throws IOException {
        String json = "{\"name\": \"file\", \"contentEncoding\": \"base64\"}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals("base64", result.getContentEncoding());
    }

    @Test
    void deserializeShouldMapContentCompression() throws IOException {
        String json = "{\"name\": \"file\", \"contentCompression\": \"gzip\"}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals("gzip", result.getContentCompression());
    }

    @Test
    void deserializeShouldMapContentMediaType() throws IOException {
        String json = "{\"name\": \"file\", \"contentMediaType\": \"application/pdf\"}";
        InputParameterSpec result = mapper.readValue(json, InputParameterSpec.class);
        assertEquals("application/pdf", result.getContentMediaType());
    }

    // ── Empty/minimal input ──

    @Test
    void deserializeShouldHandleEmptyObject() throws IOException {
        InputParameterSpec result = mapper.readValue("{}", InputParameterSpec.class);
        assertNull(result.getName());
        assertNull(result.getType());
    }
}
