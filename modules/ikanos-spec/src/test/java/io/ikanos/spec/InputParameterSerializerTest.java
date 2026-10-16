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
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.IkanosMappers;

class InputParameterSerializerTest {

    private final ObjectMapper objectMapper = IkanosMappers.json();

    @Test
    void serializeShouldWriteAllSupportedFieldsAndNestedStructures() throws Exception {
        InputParameterSpec spec = new InputParameterSpec();
        spec.setName("payload");
        spec.setType("object");
        spec.setIn("body");
        spec.setTemplate("{{payload}}");
        spec.setConstant("fixed");
        spec.setValue("{{name}}");
        spec.setSelector("$.payload");
        spec.setDescription("request payload");
        spec.setPrecision(4);
        spec.setScale(2);
        spec.setMaxLength("128");
        spec.setContentEncoding("utf-8");
        spec.setContentCompression("gzip");
        spec.setContentMediaType("application/json");
        spec.getEnumeration().add("A");
        spec.getRequired().add("id");
        spec.getExamples().add("example");
        spec.getTuple().add("one");

        InputParameterSpec items = new InputParameterSpec();
        items.setName("item");
        items.setType("string");
        spec.setItems(items);

        InputParameterSpec values = new InputParameterSpec();
        values.setName("value");
        values.setType("number");
        spec.setValues(values);

        InputParameterSpec namedProperty = new InputParameterSpec();
        namedProperty.setName("customerId");
        namedProperty.setType("string");
        spec.getProperties().add(namedProperty);

        InputParameterSpec unnamedProperty = new InputParameterSpec();
        unnamedProperty.setType("string");
        spec.getProperties().add(unnamedProperty);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(spec));

        assertEquals("payload", json.get("name").asString());
        assertEquals("object", json.get("type").asString());
        assertEquals("body", json.get("in").asString());
        assertEquals("{{payload}}", json.get("template").asString());
        assertEquals("fixed", json.get("const").asString());
        assertEquals("{{name}}", json.get("value").asString());
        assertEquals("$.payload", json.get("selector").asString());
        assertEquals("request payload", json.get("description").asString());
        assertEquals(4, json.get("precision").asInt());
        assertEquals(2, json.get("scale").asInt());
        assertEquals("128", json.get("maxLength").asString());
        assertEquals("utf-8", json.get("contentEncoding").asString());
        assertEquals("gzip", json.get("contentCompression").asString());
        assertEquals("application/json", json.get("contentMediaType").asString());
        assertEquals("A", json.get("enum").get(0).asString());
        assertEquals("id", json.get("required").get(0).asString());
        assertEquals("example", json.get("examples").get(0).asString());
        assertEquals("one", json.get("tuple").get(0).asString());
        assertEquals("item", json.get("items").get(0).get("name").asString());
        assertEquals("value", json.get("values").get("name").asString());
        assertEquals("string", json.get("properties").get("customerId").get("type").asString());
        assertFalse(json.get("properties").has("null"));
        assertFalse(json.get("properties").get("customerId").has("name"));
    }

    @Test
    void serializeShouldWriteNullWhenSpecIsNull() throws Exception {
        InputParameterSerializer serializer = new InputParameterSerializer();
        StringWriter writer = new StringWriter();
        JsonGenerator generator = objectMapper.createGenerator(writer);

        serializer.serialize(null, generator, objectMapper._serializationContext());
        generator.flush();

        assertEquals("null", writer.toString());
    }

    @Test
    void serializeShouldCoverPropertyWithoutNameNestedBranches() throws Exception {
        InputParameterSpec root = new InputParameterSpec();
        root.setName("root");
        root.setType("object");

        InputParameterSpec prop = new InputParameterSpec();
        prop.setName("meta");
        prop.setType("object");
        prop.setIn("query");
        prop.setTemplate("{{x}}");
        prop.setConstant("c");
        prop.setValue("v");
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

        InputParameterSpec nestedItem = new InputParameterSpec();
        nestedItem.setName("item");
        nestedItem.setType("string");
        prop.setItems(nestedItem);

        InputParameterSpec nestedValues = new InputParameterSpec();
        nestedValues.setName("val");
        nestedValues.setType("number");
        prop.setValues(nestedValues);

        InputParameterSpec childProp = new InputParameterSpec();
        childProp.setName("child");
        childProp.setType("string");
        prop.getProperties().add(childProp);

        root.getProperties().add(prop);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(root));
        JsonNode meta = json.path("properties").path("meta");

        assertEquals("object", meta.path("type").asString());
        assertEquals("query", meta.path("in").asString());
        assertEquals("{{x}}", meta.path("template").asString());
        assertEquals("c", meta.path("const").asString());
        assertEquals("v", meta.path("value").asString());
        assertEquals("$.meta", meta.path("selector").asString());
        assertEquals("meta desc", meta.path("description").asString());
        assertEquals(1, meta.path("precision").asInt());
        assertEquals(2, meta.path("scale").asInt());
        assertEquals("3", meta.path("maxLength").asString());
        assertEquals("enc", meta.path("contentEncoding").asString());
        assertEquals("zip", meta.path("contentCompression").asString());
        assertEquals("application/json", meta.path("contentMediaType").asString());
        assertEquals("E", meta.path("enum").get(0).asString());
        assertEquals("id", meta.path("required").get(0).asString());
        assertEquals("ex", meta.path("examples").get(0).asString());
        assertEquals("t", meta.path("tuple").get(0).asString());
        assertEquals("item", meta.path("items").get(0).path("name").asString());
        assertEquals("val", meta.path("values").path("name").asString());
        assertEquals("string", meta.path("properties").path("child").path("type").asString());
    }
}