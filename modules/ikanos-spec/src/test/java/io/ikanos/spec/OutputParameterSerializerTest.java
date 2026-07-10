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
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OutputParameterSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializeShouldUseValueForNamedConsumedOutputAndMappingForAnonymousOutput() throws Exception {
        OutputParameterSpec named = new OutputParameterSpec();
        named.setName("customerId");
        named.setType("string");
        named.setMapping("$.customer.id");

        JsonNode namedJson = objectMapper.readTree(objectMapper.writeValueAsString(named));
        assertEquals("$.customer.id", namedJson.get("value").asText());
        assertFalse(namedJson.has("mapping"));

        OutputParameterSpec anonymous = new OutputParameterSpec();
        anonymous.setType("string");
        anonymous.setMapping("$.customer.id");

        JsonNode anonymousJson = objectMapper.readTree(objectMapper.writeValueAsString(anonymous));
        assertEquals("$.customer.id", anonymousJson.get("mapping").asText());
        assertFalse(anonymousJson.has("value"));
    }

    @Test
    void serializeShouldWriteNestedObjectsAndSkipUnnamedPropertyKeys() throws Exception {
        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setName("payload");
        spec.setType("object");
        spec.setIn("body");
        spec.setMapping("$.payload");
        spec.setConstant("fixed");
        spec.setSelector("$.payload");
        spec.setDescription("response payload");
        spec.setPrecision(7);
        spec.setScale(3);
        spec.setMaxLength("256");
        spec.setContentEncoding("utf-8");
        spec.setContentCompression("gzip");
        spec.setContentMediaType("application/json");
        spec.getEnumeration().add("A");
        spec.getRequired().add("id");
        spec.getExamples().add("example");
        spec.getTuple().add("one");

        OutputParameterSpec items = new OutputParameterSpec();
        items.setName("item");
        items.setType("string");
        spec.setItems(items);

        OutputParameterSpec values = new OutputParameterSpec();
        values.setName("value");
        values.setType("number");
        spec.setValues(values);

        OutputParameterSpec namedProperty = new OutputParameterSpec();
        namedProperty.setName("customerId");
        namedProperty.setType("string");
        namedProperty.setMapping("$.id");
        spec.getProperties().add(namedProperty);

        OutputParameterSpec unnamedProperty = new OutputParameterSpec();
        unnamedProperty.setType("string");
        unnamedProperty.setMapping("$.ignored");
        spec.getProperties().add(unnamedProperty);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(spec));

        assertEquals("payload", json.get("name").asText());
        assertEquals("object", json.get("type").asText());
        assertFalse(json.has("in"));
        assertEquals("$.payload", json.get("value").asText());
        assertEquals("fixed", json.get("const").asText());
        assertEquals("$.payload", json.get("selector").asText());
        assertEquals("response payload", json.get("description").asText());
        assertEquals(7, json.get("precision").asInt());
        assertEquals(3, json.get("scale").asInt());
        assertEquals("256", json.get("maxLength").asText());
        assertEquals("utf-8", json.get("contentEncoding").asText());
        assertEquals("gzip", json.get("contentCompression").asText());
        assertEquals("application/json", json.get("contentMediaType").asText());
        assertEquals("A", json.get("enum").get(0).asText());
        assertEquals("id", json.get("required").get(0).asText());
        assertEquals("example", json.get("examples").get(0).asText());
        assertEquals("one", json.get("tuple").get(0).asText());
        assertEquals("item", json.get("items").get(0).get("name").asText());
        assertEquals("value", json.get("values").get("name").asText());
        assertEquals("$.id", json.get("properties").get("customerId").get("mapping").asText());
        assertFalse(json.get("properties").has("null"));
        assertFalse(json.get("properties").get("customerId").has("name"));
    }

    @Test
    void serializeShouldWriteNullWhenSpecIsNull() throws Exception {
        OutputParameterSerializer serializer = new OutputParameterSerializer();
        StringWriter writer = new StringWriter();
        JsonGenerator generator = objectMapper.getFactory().createGenerator(writer);

        serializer.serialize(null, generator, objectMapper.getSerializerProvider());
        generator.flush();

        assertEquals("null", writer.toString());
    }

    @Test
    void serializeShouldCoverPropertyWithoutNameNestedBranches() throws Exception {
        OutputParameterSpec root = new OutputParameterSpec();
        root.setName("root");
        root.setType("object");

        OutputParameterSpec prop = new OutputParameterSpec();
        prop.setName("meta");
        prop.setType("object");
        prop.setMapping("$.meta");
        prop.setConstant("c");
        prop.setSelector("$.meta");
        prop.setDescription("meta desc");
        prop.setPrecision(1);
        prop.setScale(2);
        prop.setMaxLength("3");
        prop.setContentEncoding("enc");
        prop.setContentCompression("zip");
        prop.setContentMediaType("application/json");
        prop.getEnumeration().add("E");
        prop.getRequired().add("id");
        prop.getExamples().add("ex");
        prop.getTuple().add("t");

        OutputParameterSpec nestedItem = new OutputParameterSpec();
        nestedItem.setName("item");
        nestedItem.setType("string");
        prop.setItems(nestedItem);

        OutputParameterSpec nestedValues = new OutputParameterSpec();
        nestedValues.setName("val");
        nestedValues.setType("number");
        prop.setValues(nestedValues);

        OutputParameterSpec childProp = new OutputParameterSpec();
        childProp.setName("child");
        childProp.setType("string");
        childProp.setMapping("$.child");
        prop.getProperties().add(childProp);

        root.getProperties().add(prop);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(root));
        JsonNode meta = json.path("properties").path("meta");

        assertEquals("object", meta.path("type").asText());
        assertEquals("$.meta", meta.path("mapping").asText());
        assertEquals("c", meta.path("const").asText());
        assertEquals("$.meta", meta.path("selector").asText());
        assertEquals("meta desc", meta.path("description").asText());
        assertEquals(1, meta.path("precision").asInt());
        assertEquals(2, meta.path("scale").asInt());
        assertEquals("3", meta.path("maxLength").asText());
        assertEquals("enc", meta.path("contentEncoding").asText());
        assertEquals("zip", meta.path("contentCompression").asText());
        assertEquals("application/json", meta.path("contentMediaType").asText());
        assertEquals("E", meta.path("enum").get(0).asText());
        assertEquals("id", meta.path("required").get(0).asText());
        assertEquals("ex", meta.path("examples").get(0).asText());
        assertEquals("t", meta.path("tuple").get(0).asText());
        assertEquals("item", meta.path("items").get(0).path("name").asText());
        assertEquals("val", meta.path("values").path("name").asText());
        assertEquals("$.child", meta.path("properties").path("child").path("mapping").asText());
    }
}