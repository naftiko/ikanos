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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import io.ikanos.spec.util.IkanosMappers;

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

        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();

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

        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();

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

        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();

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

        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();

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

        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();

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

    @Test
    public void testMcpToolInputSchemaFieldsRoundTrip() throws Exception {
        String yaml = """
                name: customer
                type: object
                description: Customer
                required: false
                properties:
                  page_size:
                    type: integer
                    minimum: 1
                    maximum: 100
                  ratio:
                    type: number
                    maximum: 0.5
                  issued_after:
                    type: string
                    format: date
                requiredProperties: [page_size]
                additionalProperties: false
                """;

        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();

        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        String serialized = jsonMapper.writeValueAsString(original);
        InputParameterSpec roundTrip = jsonMapper.readValue(serialized, InputParameterSpec.class);

        assertFalse(roundTrip.isRequired(), "Parameter-level required mismatch");
        assertEquals(java.util.List.of("page_size"), roundTrip.getRequired(),
                "Required properties mismatch");
        assertEquals(Boolean.FALSE, roundTrip.getAdditionalProperties());
        InputParameterSpec pageSize = findPropertyByName(roundTrip.getProperties(), "page_size");
        assertEquals(1, pageSize.getMinimum().intValue());
        assertEquals(100, pageSize.getMaximum().intValue());
        InputParameterSpec ratio = findPropertyByName(roundTrip.getProperties(), "ratio");
        assertEquals(0.5, ratio.getMaximum().doubleValue());
        InputParameterSpec issuedAfter =
                findPropertyByName(roundTrip.getProperties(), "issued_after");
        assertEquals("date", issuedAfter.getFormat());
    }

    @Test
    public void explicitRequiredTrueShouldSurviveYamlRoundTrip() throws Exception {
        InputParameterSpec roundTrip = yamlRoundTrip("""
                name: query
                type: string
                description: Search text
                required: true
                """);

        assertTrue(roundTrip.isRequired(), "#983: an explicit required: true must not be dropped");
    }

    @Test
    public void requiredObjectWithRequiredPropertiesShouldSurviveYamlRoundTrip() throws Exception {
        InputParameterSpec roundTrip = yamlRoundTrip("""
                name: customer
                type: object
                description: Customer
                required: true
                properties:
                  external_id:
                    type: string
                requiredProperties: [external_id]
                """);

        assertTrue(roundTrip.isRequired(), "Parameter-level required mismatch");
        assertEquals(java.util.List.of("external_id"), roundTrip.getRequired(),
                "Required properties mismatch");
    }

    @Test
    public void undeclaredRequiredShouldStayUndeclaredAfterYamlRoundTrip() throws Exception {
        InputParameterSpec roundTrip = yamlRoundTrip("""
                name: filter
                type: object
                description: Filter
                properties:
                  meta:
                    type: object
                    properties:
                      id:
                        type: string
                    required: [id]
                """);

        assertFalse(roundTrip.isRequiredOrDefault(false), "No required flag was declared");
        assertTrue(roundTrip.isRequiredOrDefault(true), "No required flag was declared");
        InputParameterSpec meta = findPropertyByName(roundTrip.getProperties(), "meta");
        assertEquals(java.util.List.of("id"), meta.getRequired(),
                "A nested schema keeps its required list");
    }

    private static InputParameterSpec yamlRoundTrip(String yaml) throws Exception {
        ObjectMapper yamlMapper = IkanosMappers.yaml();
        InputParameterSpec original = yamlMapper.readValue(yaml, InputParameterSpec.class);
        byte[] serialized = yamlMapper.writeValueAsBytes(original);
        return yamlMapper.readValue(serialized, InputParameterSpec.class);
    }

    @Test
    public void roundTripShouldKeepDefaultOfEachJsonType() throws Exception {
        ObjectMapper yamlMapper = IkanosMappers.yaml();
        ObjectMapper jsonMapper = IkanosMappers.json();
        String[] defaults = {"\"LAST_90DAYS\"", "200", "0.5", "true", "[a, b]", "{k: v}"};
        String[] expected = {"\"LAST_90DAYS\"", "200", "0.5", "true", "[\"a\",\"b\"]",
                "{\"k\":\"v\"}"};

        for (int i = 0; i < defaults.length; i++) {
            InputParameterSpec original = yamlMapper.readValue(
                    "name: x\ntype: string\nrequired: false\ndefault: " + defaults[i] + "\n",
                    InputParameterSpec.class);
            InputParameterSpec roundTrip = jsonMapper.readValue(
                    jsonMapper.writeValueAsString(original), InputParameterSpec.class);

            assertEquals(expected[i], roundTrip.getDefaultValue().toString(),
                    "Default mismatch for " + defaults[i]);
        }
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
