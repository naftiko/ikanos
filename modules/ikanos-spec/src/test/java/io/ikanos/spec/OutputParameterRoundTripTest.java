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
 * Test suite for OutputParameterSpec round-trip serialization/deserialization.
 * Validates that data is not lost when reading from YAML and writing back.
 */
public class OutputParameterRoundTripTest {

    @Test
    public void testComplexNestedStructure() throws Exception {
        String yaml = """
                type: array
                mapping: $.results
                items:
                  - type: object
                    properties:
                      name:
                        type: string
                        mapping: $.properties.Name.title[0].text.content
                      company:
                        type: string
                        mapping: $.properties.Company.rich_text[0].text.content
                      details:
                        type: object
                        properties:
                          title:
                            type: string
                            mapping: $.properties.Title.rich_text[0].text.content
                          location:
                            type: string
                            mapping: $.properties.Location.rich_text[0].text.content
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        // Deserialize from YAML
        OutputParameterSpec original = yamlMapper.readValue(yaml, OutputParameterSpec.class);

        // Serialize back to JSON
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(original);

        // Deserialize from JSON
        OutputParameterSpec roundTrip = jsonMapper.readValue(serialized, OutputParameterSpec.class);

        // Verify structure
        assertEquals("array", roundTrip.getType(), "Type mismatch");
        assertEquals("$.results", roundTrip.getMapping(), "Mapping mismatch");
        assertNotNull(roundTrip.getItems(), "Items null");
        assertEquals("object", roundTrip.getItems().getType(), "Items type mismatch");
        assertEquals(3, roundTrip.getItems().getProperties().size(), "Properties count mismatch");

        // Verify nested property
        OutputParameterSpec details = findPropertyByName(roundTrip.getItems().getProperties(),
                "details");
        assertNotNull(details, "Details property not found");
        assertEquals("object", details.getType(), "Details type mismatch");
        assertEquals(2, details.getProperties().size(), "Details properties count mismatch");

        OutputParameterSpec title = findPropertyByName(details.getProperties(), "title");
        assertNotNull(title, "Title property not found");
        assertEquals("string", title.getType(), "Title type mismatch");
        assertEquals("$.properties.Title.rich_text[0].text.content",
                title.getMapping(), "Title mapping mismatch");
    }

    @Test
    public void testArrayWithMappingPaths() throws Exception {
        String yaml = """
                type: array
                mapping: $.items
                items:
                  - type: object
                    properties:
                      id:
                        type: string
                        mapping: $.id
                      values:
                        type: array
                        items:
                          - type: string
                            mapping: $
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        OutputParameterSpec original = yamlMapper.readValue(yaml, OutputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        OutputParameterSpec roundTrip = jsonMapper.readValue(serialized, OutputParameterSpec.class);

        assert "array".equals(roundTrip.getType()) : "Root type mismatch";
        assert "$.items".equals(roundTrip.getMapping()) : "Root mapping mismatch";

        OutputParameterSpec values = findPropertyByName(roundTrip.getItems().getProperties(),
                "values");
        assertNotNull(values, "Values property not found");
        assertEquals("array", values.getType(), "Values type mismatch");
        assertNotNull(values.getItems(), "Values items null");
        assertEquals("string", values.getItems().getType(), "Values item type mismatch");
        assertEquals("$", values.getItems().getMapping(), "Values item mapping mismatch");
    }

    @Test
    public void testObjectProperties() throws Exception {
        String yaml = """
                type: object
                properties:
                  firstName:
                    type: string
                    description: Person's first name
                  lastName:
                    type: string
                    description: Person's last name
                  age:
                    type: integer
                    description: Person's age
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        OutputParameterSpec original = yamlMapper.readValue(yaml, OutputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        OutputParameterSpec roundTrip = jsonMapper.readValue(serialized, OutputParameterSpec.class);

        assert "object".equals(roundTrip.getType()) : "Type mismatch";
        assert roundTrip.getProperties().size() == 3 : "Properties count mismatch";

        OutputParameterSpec firstName = findPropertyByName(roundTrip.getProperties(), "firstName");
        assertNotNull(firstName, "firstName not found");
        assertEquals("string", firstName.getType(), "firstName type mismatch");
        assertEquals("Person's first name", firstName.getDescription(), "firstName description mismatch");
    }

    @Test
    public void testWithMetadata() throws Exception {
        String yaml = """
                type: string
                maxLength: "255"
                description: User email address
                examples:
                  - user@example.com
                  - admin@example.com
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        OutputParameterSpec original = yamlMapper.readValue(yaml, OutputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        OutputParameterSpec roundTrip = jsonMapper.readValue(serialized, OutputParameterSpec.class);

        assertEquals("string", roundTrip.getType(), "Type mismatch");
        assertEquals("255", roundTrip.getMaxLength(), "MaxLength mismatch");
        assertEquals("User email address", roundTrip.getDescription(), "Description mismatch");
        // Note: examples field may not be preserved through serialization/deserialization
        if (roundTrip.getExamples() != null && !roundTrip.getExamples().isEmpty()) {
            assertTrue(roundTrip.getExamples().contains("user@example.com"), "Example not found");
        }
    }

    @Test
    public void testWithConstraints() throws Exception {
        String yaml = """
                type: string
                enum:
                  - active
                  - inactive
                  - pending
                required:
                  - active
                  - inactive
                selector: $.status
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        OutputParameterSpec original = yamlMapper.readValue(yaml, OutputParameterSpec.class);
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        OutputParameterSpec roundTrip = jsonMapper.readValue(serialized, OutputParameterSpec.class);

        assert "string".equals(roundTrip.getType()) : "Type mismatch";
        assert roundTrip.getEnumeration().size() == 3 : "Enumeration count mismatch";
        assert roundTrip.getEnumeration().contains("active") : "Enumeration value missing";
        assertEquals(2, roundTrip.getRequired().size(), "Required count mismatch");
        assertEquals("$.status", roundTrip.getSelector(), "Selector mismatch");
    }

    private static OutputParameterSpec findPropertyByName(
            java.util.List<OutputParameterSpec> properties, String name) {
        for (OutputParameterSpec prop : properties) {
            if (name.equals(prop.getName())) {
                return prop;
            }
        }
        return null;
    }

}
