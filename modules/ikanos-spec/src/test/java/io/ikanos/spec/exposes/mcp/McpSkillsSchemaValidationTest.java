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
 * Validates the {@code skills} block of an exposed MCP server (Skills Extension, SEP-2640).
 */
class McpSkillsSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    private static final String TOOLS = """
            tools:
              get-ship:
                description: "Retrieve a ship."
                call: "registry.get-ship"
            """;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpSkillsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validate(String adapterBody) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Skills"
                  description: "Skills schema test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "registry"
                      baseUri: "https://registry.fleet.test"
                      resources:
                        ships:
                          path: "/ships/{{imo}}"
                          operations:
                            get-ship:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      namespace: "fleet"
                      port: 3001
                %s
                """.formatted(IKANOS, indent(adapterBody));
        return schema.validate(YAML.readTree(yaml));
    }

    private static String indent(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.strip().split("\n")) {
            out.append("      ").append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    @Test
    void schemaShouldAcceptSkillWithToolsAndInstructions() throws Exception {
        List<Error> errors = validate(TOOLS + """
                skills:
                  fleet-ops:
                    description: "Find ships across registries."
                    license: "Apache-2.0"
                    metadata:
                      team: "fleet"
                    allowed-tools: "get-ship"
                    user-invocable: true
                    tools: ["get-ship"]
                    location: "file:///opt/skills/fleet-ops"
                    instructions:
                      lookup-fallback: "guides/lookup-fallback.md"
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldAcceptSkillsOnlyAdapterWithoutTools() throws Exception {
        List<Error> errors = validate("""
                skills:
                  install-ikanos:
                    description: "Guide a developer through installing Ikanos."
                """);
        assertTrue(errors.isEmpty(), "A skills-only MCP adapter must be valid, got: " + errors);
    }

    @Test
    void schemaShouldRejectAdapterWithNeitherToolsNorSkills() throws Exception {
        List<Error> errors = validate("""
                description: "Nothing exposed."
                """);
        assertFalse(errors.isEmpty(), "An MCP adapter needs tools or skills");
    }

    @Test
    void schemaShouldRejectSkillWithoutDescription() throws Exception {
        List<Error> errors = validate(TOOLS + """
                skills:
                  fleet-ops:
                    tools: ["get-ship"]
                """);
        assertFalse(errors.isEmpty(), "description is required");
    }

    @Test
    void schemaShouldRejectUppercaseSkillName() throws Exception {
        List<Error> errors = validate(TOOLS + """
                skills:
                  Fleet-Ops:
                    description: "Find ships."
                """);
        assertFalse(errors.isEmpty(), "Agent Skills names are lowercase");
    }

    @Test
    void schemaShouldRejectSkillToolsDeclaredAsMap() throws Exception {
        List<Error> errors = validate(TOOLS + """
                skills:
                  fleet-ops:
                    description: "Find ships."
                    tools:
                      get-ship:
                        from:
                          sourceNamespace: "fleet"
                          action: "get-ship"
                """);
        assertFalse(errors.isEmpty(), "tools is a list of tool names, not the legacy map");
    }

    @Test
    void schemaShouldRejectInstructionPathEscapingLocation() throws Exception {
        List<Error> errors = validate(TOOLS + """
                skills:
                  fleet-ops:
                    description: "Find ships."
                    location: "file:///opt/skills/fleet-ops"
                    instructions:
                      secrets: "../.env"
                """);
        assertFalse(errors.isEmpty(), "Instruction paths stay inside the location");
    }
}
