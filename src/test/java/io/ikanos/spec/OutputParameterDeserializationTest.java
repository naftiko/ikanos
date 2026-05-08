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
 * Test deserialization of nested output parameters from YAML
 */
public class OutputParameterDeserializationTest {

  @Test
  public void testNestedOutputParameterDeserialization() throws Exception {
    // Example YAML snippet from the specification:
    String yamlSnippet = """
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
            title:
              type: string
              mapping: $.properties.Title.rich_text[0].text.content
            location:
              type: string
              mapping: $.properties.Location.rich_text[0].text.content
            owner:
              type: string
              mapping: $.properties.Owner.people[0].name
            participation_status:
              type: string
              mapping: $.properties.Participation Status.select.name
            comments:
              type: string
              mapping: $.properties.Comments.rich_text[0].text.content
        """;

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    OutputParameterSpec spec = mapper.readValue(yamlSnippet, OutputParameterSpec.class);

    // Validate the top-level structure
    assertEquals("array", spec.getType(), "Type should be 'array'");
    assertEquals("$.results", spec.getMapping(), "Mapping should be '$.results'");

    // Validate the items
    OutputParameterSpec itemsSpec = spec.getItems();
    assertNotNull(itemsSpec, "Items should not be null");
    assertEquals("object", itemsSpec.getType(), "Items type should be 'object'");

    // Validate nested properties
    assertEquals(7, itemsSpec.getProperties().size(), "Should have 7 properties");

    // Check first property
    OutputParameterSpec nameProperty = itemsSpec.getProperties().stream()
        .filter(p -> "name".equals(p.getName())).findFirst().orElse(null);
    assertNotNull(nameProperty, "name property should exist");
    assertEquals("string", nameProperty.getType(), "name type should be 'string'");
    assertEquals("$.properties.Name.title[0].text.content", nameProperty.getMapping(),
        "name mapping should match");

    // Check company property
    OutputParameterSpec companyProperty = itemsSpec.getProperties().stream()
        .filter(p -> "company".equals(p.getName())).findFirst().orElse(null);
    assertNotNull(companyProperty, "company property should exist");
    assertEquals("string", companyProperty.getType(), "company type should be 'string'");
  }

  @Test
  public void testConsumedOutputParameterUsesValueField() throws Exception {
    String yamlSnippet = """
        name: userid
        type: string
        value: $.id
        """;

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    OutputParameterSpec spec = mapper.readValue(yamlSnippet, OutputParameterSpec.class);

    assertEquals("userid", spec.getName(), "Name should be parsed");
    assertEquals("string", spec.getType(), "Type should be parsed");
    assertEquals("$.id", spec.getMapping(), "Value alias should populate mapping");
    assertNull(spec.getValue(), "Consumed output JsonPath alias should not populate value");
  }

  @Test
  public void testNamedMockOutputParameterShouldKeepStaticValue() throws Exception {
    String yamlSnippet = """
        name: message
        type: string
        value: "Hello, {{name}}!"
        """;

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    OutputParameterSpec spec = mapper.readValue(yamlSnippet, OutputParameterSpec.class);

    assertEquals("message", spec.getName(), "Name should be parsed");
    assertEquals("string", spec.getType(), "Type should be parsed");
    assertEquals("Hello, {{name}}!", spec.getValue(),
        "Named mock output should preserve static/template value");
    assertNull(spec.getMapping(), "Named mock output should not be re-routed to mapping");
  }

  @Test
  public void testNamedOutputSerializesAsValue() throws Exception {
    OutputParameterSpec spec = new OutputParameterSpec();
    spec.setName("userid");
    spec.setType("string");
    spec.setMapping("$.id");

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    String yaml = mapper.writeValueAsString(spec);

    assertTrue(yaml.contains("value: \"$.id\"") || yaml.contains("value: $.id"),
        "Named output should serialize using value field");
    assertFalse(yaml.contains("mapping:"),
        "Named output should not serialize using mapping field");
  }

  /**
   * Regression test for PR #287 review comment: when an object output parameter
   * has nested properties with {@code value} (mock mode), the deserializer must
   * call {@code setValue()} — not {@code setMapping()} — on the child properties.
   *
   * The bug occurs because the property key ("status") is injected as "name" into
   * the child node before the recursive dispatch, causing the
   * {@code (value && name)} branch to misroute the value into mapping.
   */
  @Test
  public void deserializeShouldSetValueNotMappingOnNestedPropertyWithValue() throws Exception {
    String yamlSnippet = """
        type: object
        properties:
          status:
            type: string
            value: "ok"
          greeting:
            type: string
            value: "Hello, {{name}}!"
        """;

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    OutputParameterSpec spec = mapper.readValue(yamlSnippet, OutputParameterSpec.class);

    assertEquals("object", spec.getType());

    OutputParameterSpec statusProp = spec.getProperties().stream()
        .filter(p -> "status".equals(p.getName())).findFirst().orElse(null);
    assertNotNull(statusProp, "status property should exist");
    assertEquals("string", statusProp.getType());
    assertEquals("ok", statusProp.getValue(),
        "Static value should be set via setValue(), not routed to mapping");
    assertNull(statusProp.getMapping(),
        "Mapping should be null for a static-value property");

    OutputParameterSpec greetingProp = spec.getProperties().stream()
        .filter(p -> "greeting".equals(p.getName())).findFirst().orElse(null);
    assertNotNull(greetingProp, "greeting property should exist");
    assertEquals("Hello, {{name}}!", greetingProp.getValue(),
        "Mustache template value should be set via setValue()");
    assertNull(greetingProp.getMapping(),
        "Mapping should be null for a Mustache-template-value property");
  }

  /**
   * Ensures that nested properties with {@code mapping} still deserialize
   * correctly (setMapping), and are not affected by the value-fix.
   */
  @Test
  public void deserializeShouldPreserveMappingOnNestedPropertyWithMapping() throws Exception {
    String yamlSnippet = """
        type: object
        properties:
          status:
            type: string
            mapping: "$.status"
        """;

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    OutputParameterSpec spec = mapper.readValue(yamlSnippet, OutputParameterSpec.class);

    OutputParameterSpec statusProp = spec.getProperties().stream()
        .filter(p -> "status".equals(p.getName())).findFirst().orElse(null);
    assertNotNull(statusProp, "status property should exist");
    assertEquals("$.status", statusProp.getMapping(),
        "Mapping should be preserved for properties using mapping");
    assertNull(statusProp.getValue(),
        "Value should be null for a mapped property");
  }

  /**
   * Regression test: consumed output parameters (with explicit name + value in YAML)
   * must still route value to mapping for backward compatibility.
   */
  @Test
  public void deserializeShouldRoutValueToMappingForConsumedOutputParameter() throws Exception {
    String yamlSnippet = """
        name: userid
        type: string
        value: "$.id"
        """;

    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    OutputParameterSpec spec = mapper.readValue(yamlSnippet, OutputParameterSpec.class);

    assertEquals("userid", spec.getName());
    assertEquals("$.id", spec.getMapping(),
        "Consumed output parameter value should be routed to mapping");
  }

}
