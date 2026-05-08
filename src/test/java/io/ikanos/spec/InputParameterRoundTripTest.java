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

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for InputParameterSpec round-trip serialization/deserialization.
 * Validates that data is not lost when reading from YAML and writing back.
 */
public class InputParameterRoundTripTest {

    @Test
    public void testHeaderParameter() throws Exception {
        String yaml = """
                name: Authorization
                type: string
                in: header
                description: Bearer token
                const: Bearer ${token}
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        InputParameterSpec roundTrip = jsonMapper.readValue(serialized, InputParameterSpec.class);

        assertEquals("Authorization", roundTrip.getName(), "Name mismatch");
        assertEquals("string", roundTrip.getType(), "Type mismatch");
        assertEquals("header", roundTrip.getIn(), "In mismatch");
        assertEquals("Bearer ${token}", roundTrip.getConstant(), "Constant mismatch");
    }

    @Test
    public void testPathParameter() throws Exception {
        String yaml = """
                name: userId
                type: string
                in: path
                description: User ID parameter
                template: "{{userId}}"
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        InputParameterSpec roundTrip = jsonMapper.readValue(serialized, InputParameterSpec.class);

        assertEquals("userId", roundTrip.getName(), "Name mismatch");
        assertEquals("path", roundTrip.getIn(), "In mismatch");
        assertEquals("{{userId}}", roundTrip.getTemplate(), "Template mismatch");
    }

    @Test
    public void testQueryParameter() throws Exception {
        String yaml = """
                name: filter
                type: object
                in: query
                properties:
                  status:
                    type: string
                    enum:
                      - active
                      - inactive
                  limit:
                    type: integer
                    precision: 10
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        InputParameterSpec roundTrip = jsonMapper.readValue(serialized, InputParameterSpec.class);

        assertEquals("filter", roundTrip.getName(), "Name mismatch");
        assertEquals("object", roundTrip.getType(), "Type mismatch");
        assertEquals("query", roundTrip.getIn(), "In mismatch");
        assertEquals(2, roundTrip.getProperties().size(), "Properties count mismatch");

        InputParameterSpec status = findPropertyByName(roundTrip.getProperties(), "status");
        assertNotNull(status, "Status property not found");
        assertEquals(2, status.getEnumeration().size(), "Enumeration count mismatch");
    }

    @Test
    public void testComplexNestedStructure() throws Exception {
        String yaml = """
                name: requestBody
                type: object
                in: body
                properties:
                  user:
                    type: object
                    properties:
                      name:
                        type: string
                      email:
                        type: string
                  metadata:
                    type: object
                    properties:
                      tags:
                        type: array
                        items:
                          - type: string
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        InputParameterSpec roundTrip = jsonMapper.readValue(serialized, InputParameterSpec.class);

        assertEquals("requestBody", roundTrip.getName(), "Name mismatch");
        assertEquals("object", roundTrip.getType(), "Type mismatch");
        assertEquals("body", roundTrip.getIn(), "In mismatch");
        assertEquals(2, roundTrip.getProperties().size(), "Properties count mismatch");

        // Verify nested structure
        InputParameterSpec user = findPropertyByName(roundTrip.getProperties(), "user");
        assertNotNull(user, "User property not found");
        assertEquals(2, user.getProperties().size(), "User properties count mismatch");

        InputParameterSpec metadata = findPropertyByName(roundTrip.getProperties(), "metadata");
        assertNotNull(metadata, "Metadata property not found");

        InputParameterSpec tags = findPropertyByName(metadata.getProperties(), "tags");
        assertNotNull(tags, "Tags property not found");
        assertEquals("array", tags.getType(), "Tags type mismatch");
        assertNotNull(tags.getItems(), "Tags items null");
        assertEquals("string", tags.getItems().getType(), "Tags item type mismatch");
    }

    @Test
    public void testWithInAndTemplate() throws Exception {
        String yaml = """
                type: string
                in: path
                template: "/users/{{id}}/profile"
                description: User profile path
                selector: $.path
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        InputParameterSpec roundTrip = jsonMapper.readValue(serialized, InputParameterSpec.class);

        assertEquals("string", roundTrip.getType(), "Type mismatch");
        assertEquals("path", roundTrip.getIn(), "In mismatch");
        assertEquals("/users/{{id}}/profile", roundTrip.getTemplate(), "Template mismatch");
        assertEquals("User profile path", roundTrip.getDescription(), "Description mismatch");
        assertEquals("$.path", roundTrip.getSelector(), "Selector mismatch");
    }

    private static InputParameterSpec findPropertyByName(
            java.util.List<InputParameterSpec> properties, String name) {
        for (InputParameterSpec prop : properties) {
            if (name.equals(prop.getName())) {
                return prop;
            }
        }
        return null;
    }

}
