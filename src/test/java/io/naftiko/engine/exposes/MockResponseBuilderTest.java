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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.naftiko.spec.OutputParameterSpec;

public class MockResponseBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canBuildMockResponseShouldReturnFalseWhenNull() {
        assertFalse(MockResponseBuilder.canBuildMockResponse(null));
    }

    @Test
    void canBuildMockResponseShouldReturnFalseWhenEmpty() {
        assertFalse(MockResponseBuilder.canBuildMockResponse(List.of()));
    }

    @Test
    void canBuildMockResponseShouldReturnFalseWhenNoConstValues() {
        OutputParameterSpec param = new OutputParameterSpec("field", "string", null, null);
        assertFalse(MockResponseBuilder.canBuildMockResponse(List.of(param)));
    }

    @Test
    void canBuildMockResponseShouldReturnTrueWhenConstPresent() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setName("greeting");
        param.setType("string");
        param.setConstant("hello");
        assertTrue(MockResponseBuilder.canBuildMockResponse(List.of(param)));
    }

    @Test
    void canBuildMockResponseShouldDetectNestedConst() {
        OutputParameterSpec nested = new OutputParameterSpec();
        nested.setName("city");
        nested.setType("string");
        nested.setConstant("Paris");

        OutputParameterSpec parent = new OutputParameterSpec();
        parent.setName("address");
        parent.setType("object");
        parent.getProperties().add(nested);

        assertTrue(MockResponseBuilder.canBuildMockResponse(List.of(parent)));
    }

    @Test
    void canBuildMockResponseShouldDetectConstInArrayItems() {
        OutputParameterSpec item = new OutputParameterSpec();
        item.setName("tag");
        item.setType("string");
        item.setConstant("sample");

        OutputParameterSpec array = new OutputParameterSpec();
        array.setName("tags");
        array.setType("array");
        array.setItems(item);

        assertTrue(MockResponseBuilder.canBuildMockResponse(List.of(array)));
    }

    @Test
    void buildMockDataShouldReturnNullWhenNull() {
        assertNull(MockResponseBuilder.buildMockData(null, mapper));
    }

    @Test
    void buildMockDataShouldReturnNullWhenEmpty() {
        assertNull(MockResponseBuilder.buildMockData(List.of(), mapper));
    }

    @Test
    void buildMockDataShouldBuildStringConst() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setName("message");
        param.setType("string");
        param.setConstant("Hello, World!");

        JsonNode result = MockResponseBuilder.buildMockData(List.of(param), mapper);
        assertNotNull(result);
        assertEquals("Hello, World!", result.get("message").asText());
    }

    @Test
    void buildMockDataShouldBuildObjectWithProperties() {
        OutputParameterSpec company = new OutputParameterSpec();
        company.setName("company");
        company.setType("string");
        company.setConstant("Naftiko");

        OutputParameterSpec role = new OutputParameterSpec();
        role.setName("role");
        role.setType("string");
        role.setConstant("Engineer");

        OutputParameterSpec obj = new OutputParameterSpec();
        obj.setName("profile");
        obj.setType("object");
        obj.getProperties().add(company);
        obj.getProperties().add(role);

        JsonNode result = MockResponseBuilder.buildMockData(List.of(obj), mapper);
        assertNotNull(result);
        JsonNode profile = result.get("profile");
        assertNotNull(profile);
        assertEquals("Naftiko", profile.get("company").asText());
        assertEquals("Engineer", profile.get("role").asText());
    }

    @Test
    void buildMockDataShouldBuildArrayWithItems() {
        OutputParameterSpec item = new OutputParameterSpec();
        item.setType("string");
        item.setConstant("tag-value");

        OutputParameterSpec array = new OutputParameterSpec();
        array.setName("tags");
        array.setType("array");
        array.setItems(item);

        JsonNode result = MockResponseBuilder.buildMockData(List.of(array), mapper);
        assertNotNull(result);
        JsonNode tags = result.get("tags");
        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertEquals("tag-value", tags.get(0).asText());
    }

    @Test
    void buildParameterValueShouldReturnNullNodeForNullParam() {
        JsonNode result = MockResponseBuilder.buildParameterValue(null, mapper);
        assertTrue(result.isNull());
    }

    @Test
    void buildParameterValueShouldReturnNullNodeForTypeWithoutConst() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setName("field");
        param.setType("string");

        JsonNode result = MockResponseBuilder.buildParameterValue(param, mapper);
        assertTrue(result.isNull());
    }

    @Test
    void buildMockDataShouldUseValueAsDefaultFieldName() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setType("string");
        param.setConstant("no-name-field");

        JsonNode result = MockResponseBuilder.buildMockData(List.of(param), mapper);
        assertNotNull(result);
        assertEquals("no-name-field", result.get("value").asText());
    }

    @Test
    void buildParameterValueShouldReturnBooleanNodeForBooleanConst() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setName("active");
        param.setType("boolean");
        param.setConstant("true");

        JsonNode result = MockResponseBuilder.buildParameterValue(param, mapper);
        assertTrue(result.isBoolean());
        assertTrue(result.booleanValue());
    }

    @Test
    void buildParameterValueShouldReturnNumberNodeForNumberConst() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setName("price");
        param.setType("number");
        param.setConstant("19.99");

        JsonNode result = MockResponseBuilder.buildParameterValue(param, mapper);
        assertTrue(result.isNumber());
        assertEquals(19.99, result.doubleValue());
    }

    @Test
    void buildParameterValueShouldReturnIntegerNodeForIntegerConst() {
        OutputParameterSpec param = new OutputParameterSpec();
        param.setName("count");
        param.setType("integer");
        param.setConstant("42");

        JsonNode result = MockResponseBuilder.buildParameterValue(param, mapper);
        assertTrue(result.isNumber());
        assertEquals(42L, result.longValue());
    }

    @Test
    void constToNodeShouldDefaultToTextForUnknownType() {
        JsonNode result = MockResponseBuilder.typedStringToNode("hello", "string", mapper);
        assertTrue(result.isTextual());
        assertEquals("hello", result.asText());
    }
}
