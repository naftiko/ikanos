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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates the names of exposed MCP tools (issue #1006): they accept letters, digits, hyphens
 * and underscores, so a capability can keep the snake_case names of the MCP server it
 * re-implements. A skill's {@code tools} list names those tools and accepts the same characters.
 */
class McpToolNameSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpToolNameSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    /** A capability exposing one MCP tool named {@code toolName}, plus {@code extra} lines. */
    private static List<Error> validate(String toolName, String extra) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Tool names"
                  description: "MCP tool name schema test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "hub"
                      baseUri: "https://hub.example.test"
                      resources:
                        repos:
                          path: "/repos"
                          operations:
                            list-repos:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      namespace: "huggingface"
                      port: 3001
                      tools:
                        "%s":
                          description: "Search repositories."
                          call: "hub.list-repos"
                %s
                """.formatted(IKANOS, toolName, extra);
        return schema.validate(YAML.readTree(yaml));
    }

    private static List<Error> validate(String toolName) throws Exception {
        return validate(toolName, "");
    }

    @Test
    void schemaShouldAcceptSnakeCaseToolName() throws Exception {
        List<Error> errors = validate("hub_repo_search");
        assertTrue(errors.isEmpty(), "#1006: snake_case tool names are valid, got: " + errors);
    }

    @Test
    void schemaShouldAcceptKebabCaseToolName() throws Exception {
        List<Error> errors = validate("hub-repo-search");
        assertTrue(errors.isEmpty(), "Existing kebab-case tool names stay valid, got: " + errors);
    }

    @Test
    void schemaShouldAcceptToolNameMixingHyphensAndUnderscores() throws Exception {
        List<Error> errors = validate("hf_fs-ls");
        assertTrue(errors.isEmpty(), "Hyphens and underscores can be mixed, got: " + errors);
    }

    @Test
    void schemaShouldRejectToolNameWithSpace() throws Exception {
        assertFalse(validate("repo search").isEmpty(), "A space is not a tool name character");
    }

    @Test
    void schemaShouldRejectToolNameWithWildcard() throws Exception {
        assertFalse(validate("repo_*").isEmpty(), "A wildcard is not a tool name character");
    }

    @Test
    void schemaShouldRejectToolNameWithDot() throws Exception {
        assertFalse(validate("repo.search").isEmpty(),
                "A dot stays out of exposed tool names: it separates namespace.name references");
    }

    @Test
    void schemaShouldAcceptSkillToolsNamingSnakeCaseTool() throws Exception {
        List<Error> errors = validate("hub_repo_search", """
                      skills:
                        hub-search:
                          description: "Find models, datasets and Spaces."
                          tools: ["hub_repo_search"]
                """);
        assertTrue(errors.isEmpty(),
                "#1006: a skill names the snake_case tool it depends on, got: " + errors);
    }

    @Test
    void schemaShouldAcceptSnakeCaseToolNamesFixture() throws Exception {
        try (InputStream in = McpToolNameSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("rules/spectral-mcp-tool-names-compliant.yaml")) {
            assertNotNull(in, "rules/spectral-mcp-tool-names-compliant.yaml must exist");
            List<Error> errors = schema.validate(YAML.readTree(in));
            assertTrue(errors.isEmpty(),
                    "The ruleset fixture for #1006 must also pass the schema, got: " + errors);
        }
    }

    @Test
    void schemaShouldRejectSkillToolsWithWildcard() throws Exception {
        List<Error> errors = validate("hub_repo_search", """
                      skills:
                        hub-search:
                          description: "Find models, datasets and Spaces."
                          tools: ["hub_*"]
                """);
        assertFalse(errors.isEmpty(), "A skill names tools exactly, not by pattern");
    }
}
