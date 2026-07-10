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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Verifies that {@code unevaluatedProperties: false} on {@code MappedOutputParameter} variants
 * correctly rejects spurious properties (e.g. {@code name}) that are not declared in the schema.
 *
 * <p>Regression guard for the fix that moved {@code unevaluatedProperties: false} from inside
 * {@code allOf[1]} to the root level of each {@code MappedOutputParameter*} variant, ensuring
 * validators propagate the constraint correctly across {@code $ref} + {@code oneOf} boundaries.
 */
class SchemaUnevaluatedPropertiesTest {

  private static JsonSchema schema;
  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  @BeforeAll
  static void loadSchema() throws Exception {
    InputStream schemaStream =
        SchemaUnevaluatedPropertiesTest.class
            .getClassLoader()
            .getResourceAsStream("schemas/ikanos-schema.json");
    JsonNode schemaNode = new ObjectMapper().readTree(schemaStream);
    schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
  }

  @Test
  void validateShouldAcceptCapabilityWithValidMappedOutputParameters() throws Exception {
    JsonNode doc = loadFixture("capabilities/capability-valid.yml");
    Set<ValidationMessage> errors = schema.validate(doc);
    assertTrue(errors.isEmpty(),
        "Expected valid capability to pass schema validation, but got errors:\n" + errors);
  }

  @Test
  void validateShouldRejectMappedOutputParameterWithSpuriousNameProperty() throws Exception {
    JsonNode doc = loadFixture("capabilities/capability-spurious-name.yml");
    Set<ValidationMessage> errors = schema.validate(doc);
    assertFalse(errors.isEmpty(),
        "Expected capability with spurious 'name' on MappedOutputParameter to fail schema validation");
  }

  private JsonNode loadFixture(String classpathResource) throws Exception {
    InputStream stream =
        SchemaUnevaluatedPropertiesTest.class.getClassLoader().getResourceAsStream(classpathResource);
    return YAML_MAPPER.readTree(stream);
  }
}
