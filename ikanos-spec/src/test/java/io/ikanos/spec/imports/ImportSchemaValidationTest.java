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
package io.ikanos.spec.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates that the bundled {@code ikanos-schema.json} (Draft 2020-12) accepts the unified
 * import directive ({@code from}/{@code import}/{@code as}/{@code description}) in all four
 * sections (consumes/exposes/aggregates/binds), accepts the new top-level standalone document
 * shapes (exposes/aggregates), and rejects malformed import entries.
 *
 * <p>See {@code blueprints/unified-import-mechanism.md} §16 (Phase 1) and the
 * {@code $defs/ImportEntry} fragment of the schema.</p>
 */
class ImportSchemaValidationTest {

    private static JsonSchema schema;
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ImportSchemaValidationTest.class
                .getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            JsonNode schemaNode = JSON.readTree(in);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            schema = factory.getSchema(schemaNode);
        }
    }

    private Set<ValidationMessage> validateYaml(String yaml) throws Exception {
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    // ----- happy paths: import entries in all four sections -----

    @Test
    @DisplayName("schema accepts an import entry inside 'capability.consumes'")
    void schemaShouldAcceptImportEntryInConsumes() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            info:
              label: "demo"
              description: "demo"
            capability:
              consumes:
                - from: "./shared/notion.consumes.yml"
                  import: "notion"
                  as: "notion-shared"
                  description: "Standard Notion adapter."
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts an import entry inside 'capability.exposes'")
    void schemaShouldAcceptImportEntryInExposes() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            info:
              label: "demo"
              description: "demo"
            capability:
              exposes:
                - from: "./shared/maritime.exposes.yml"
                  import: "maritime-rest"
                  as: "fleet-maritime-rest"
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts an import entry inside 'capability.aggregates'")
    void schemaShouldAcceptImportEntryInAggregates() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            info:
              label: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "api"
                  baseUri: "https://api.example.com"
              aggregates:
                - from: "./shared/maritime-aggregates.yml"
                  import: "crew-resolver"
                  as: "fleet-crew-resolver"
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts an import entry inside top-level 'binds'")
    void schemaShouldAcceptImportEntryInBinds() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            binds:
              - from: "./shared/prod-secrets.binds.yml"
                import: "secrets"
                as: "prod-secrets"
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    // ----- new top-level standalone document shapes -----

    @Test
    @DisplayName("schema accepts an 'ikanos + exposes' standalone document")
    void schemaShouldAcceptStandaloneExposesDocument() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            exposes:
              - type: "rest"
                namespace: "shared-rest"
                port: 3003
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts an 'ikanos + aggregates' standalone document")
    void schemaShouldAcceptStandaloneAggregatesDocument() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            aggregates:
              - label: "Crew Resolver"
                namespace: "crew-resolver"
                functions:
                  - name: "find-by-id"
                    description: "Look up a crew member by id."
                    call: "api.lookup"
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    // ----- error cases -----

    @Test
    @DisplayName("schema rejects an import entry missing the 'from' property")
    void schemaShouldRejectImportEntryMissingFrom() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            info:
              label: "demo"
              description: "demo"
            capability:
              consumes:
                - import: "notion"
                  as: "notion-shared"
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(), "Expected validation errors for missing 'from'");
    }

    @Test
    @DisplayName("schema rejects an import entry missing the 'import' property")
    void schemaShouldRejectImportEntryMissingImport() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            info:
              label: "demo"
              description: "demo"
            capability:
              consumes:
                - from: "./shared/notion.consumes.yml"
                  as: "notion-shared"
            """;
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(), "Expected validation errors for missing 'import'");
    }

    @Test
    @DisplayName("schema's ImportEntry definition exists with required from/import and disallows extras")
    void schemaImportEntryDefinitionShouldBeWellFormed() throws Exception {
        try (InputStream in = ImportSchemaValidationTest.class
                .getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            JsonNode root = JSON.readTree(in);
            JsonNode importEntry = root.path("$defs").path("ImportEntry");
            assertFalse(importEntry.isMissingNode(), "$defs/ImportEntry must be defined");

            JsonNode required = importEntry.path("required");
            assertTrue(required.isArray(), "ImportEntry.required must be an array");
            boolean hasFrom = false;
            boolean hasImport = false;
            for (JsonNode r : required) {
                if ("from".equals(r.asText())) {
                    hasFrom = true;
                }
                if ("import".equals(r.asText())) {
                    hasImport = true;
                }
            }
            assertTrue(hasFrom, "ImportEntry.required must contain 'from'");
            assertTrue(hasImport, "ImportEntry.required must contain 'import'");

            assertEquals(
                false,
                importEntry.path("additionalProperties").asBoolean(true),
                "ImportEntry must set additionalProperties: false"
            );
        }
    }
}
