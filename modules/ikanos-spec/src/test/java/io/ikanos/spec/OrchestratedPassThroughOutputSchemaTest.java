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
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * Schema tests for pass-through orchestrated output parameters (#859): an orchestrated
 * {@code object} without {@code properties}, or {@code array} without {@code items}, returns the
 * mapped value as is.
 */
class OrchestratedPassThroughOutputSchemaTest {

  private static Schema schema;

  @BeforeAll
  static void loadSchema() throws Exception {
    InputStream schemaStream = OrchestratedPassThroughOutputSchemaTest.class.getClassLoader()
        .getResourceAsStream("schemas/ikanos-schema.json");
    JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
    schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
        .getSchema(schemaNode);
  }

  @Test
  void validateShouldAcceptOrchestratedObjectOutputWithoutProperties() throws Exception {
    List<Error> errors = schema.validate(capability("""
        page-properties:
          type: "object"
        """));
    assertTrue(errors.isEmpty(), "#859: an open object output must be valid, got:\n" + errors);
  }

  @Test
  void validateShouldAcceptOrchestratedArrayOutputWithoutItems() throws Exception {
    List<Error> errors = schema.validate(capability("""
        page-properties:
          type: "array"
        """));
    assertTrue(errors.isEmpty(), "#859: an open array output must be valid, got:\n" + errors);
  }

  @Test
  void validateShouldStillAcceptOrchestratedObjectOutputWithProperties() throws Exception {
    List<Error> errors = schema.validate(capability("""
        page-properties:
          type: "object"
          properties:
            id:
              type: "string"
        """));
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void validateShouldRejectOrchestratedArrayOutputWithEmptyItems() throws Exception {
    List<Error> errors = schema.validate(capability("""
        page-properties:
          type: "array"
          items: {}
        """));
    assertFalse(errors.isEmpty(), "An empty items declaration must still be rejected");
  }

  @Test
  void validateShouldRejectUnknownPropertyOnOrchestratedObjectOutput() throws Exception {
    List<Error> errors = schema.validate(capability("""
        page-properties:
          type: "object"
          mapping: "$.page.properties"
        """));
    assertFalse(errors.isEmpty(), "Orchestrated outputs take no mapping; it must be rejected");
  }

  private static JsonNode capability(String outputParameter) throws Exception {
    String yaml = """
        ikanos: "%s"
        capability:
          consumes:
            - type: "http"
              namespace: "notion"
              baseUri: "https://api.example.com"
              resources:
                page:
                  path: "/pages/{{page_id}}"
                  operations:
                    get-page:
                      method: "GET"
                      inputParameters:
                        page_id:
                          in: "path"
          exposes:
            - type: "mcp"
              port: 3000
              namespace: "tools"
              tools:
                fetch-page:
                  description: "Fetch a page"
                  inputParameters:
                    page_id:
                      type: "string"
                      description: "ID of the page"
                  steps:
                    page:
                      type: "call"
                      call: "notion.get-page"
                      with:
                        page_id: "{{page_id}}"
                  mappings:
                    - target: "page-properties"
                      value: "$.page.properties"
                  outputParameters:
        %s""".formatted(VersionHelper.getSchemaVersion(), outputParameter.indent(12));
    return IkanosMappers.yaml().readTree(yaml);
  }
}
