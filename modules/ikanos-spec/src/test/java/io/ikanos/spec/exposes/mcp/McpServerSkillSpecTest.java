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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link McpServerSkillSpec} and the {@code skills} map of {@link McpServerSpec}.
 */
class McpServerSkillSpecTest {

    private final ObjectMapper yaml = IkanosMappers.yaml();
    private final ObjectMapper json = IkanosMappers.json();

    private static final String ADAPTER = """
            type: mcp
            namespace: fleet
            port: 3001
            tools:
              get-ship:
                description: Retrieve a ship.
            skills:
              fleet-ops:
                description: Find ships across registries.
                license: Apache-2.0
                compatibility: Requires network access.
                metadata:
                  team: fleet
                allowed-tools: get-ship
                argument-hint: "<imo>"
                user-invocable: true
                disable-model-invocation: false
                location: file:///opt/skills/fleet-ops
                tools: [get-ship]
                instructions:
                  lookup-fallback: guides/lookup-fallback.md
              install-ikanos:
                description: Guide a developer through installing Ikanos.
            """;

    @Test
    void deserializeShouldSetSkillNameFromMapKey() throws Exception {
        McpServerSpec spec = yaml.readValue(ADAPTER, McpServerSpec.class);

        assertEquals(List.of("fleet-ops", "install-ikanos"), List.copyOf(spec.getSkills().keySet()));
        assertEquals("fleet-ops", spec.getSkills().get("fleet-ops").getName());
    }

    @Test
    void deserializeShouldReadEveryFrontmatterField() throws Exception {
        McpServerSkillSpec skill = yaml.readValue(ADAPTER, McpServerSpec.class)
                .getSkills().get("fleet-ops");

        assertEquals("Find ships across registries.", skill.getDescription());
        assertEquals("Apache-2.0", skill.getLicense());
        assertEquals("Requires network access.", skill.getCompatibility());
        assertEquals(Map.of("team", "fleet"), skill.getMetadata());
        assertEquals("get-ship", skill.getAllowedTools());
        assertEquals("<imo>", skill.getArgumentHint());
        assertTrue(skill.getUserInvocable());
        assertFalse(skill.getDisableModelInvocation());
        assertEquals("file:///opt/skills/fleet-ops", skill.getLocation());
    }

    @Test
    void deserializeShouldReadToolsAsNameListAndInstructionsAsMap() throws Exception {
        McpServerSkillSpec skill = yaml.readValue(ADAPTER, McpServerSpec.class)
                .getSkills().get("fleet-ops");

        assertEquals(List.of("get-ship"), skill.getTools());
        assertEquals(Map.of("lookup-fallback", "guides/lookup-fallback.md"),
                skill.getInstructions());
    }

    @Test
    void deserializeShouldLeaveToolsAndInstructionsEmptyForGuidanceOnlySkill() throws Exception {
        McpServerSkillSpec skill = yaml.readValue(ADAPTER, McpServerSpec.class)
                .getSkills().get("install-ikanos");

        assertTrue(skill.getTools().isEmpty());
        assertTrue(skill.getInstructions().isEmpty());
    }

    @Test
    void serializeShouldWriteKebabCaseKeysAndOmitEmptyCollections() throws Exception {
        McpServerSpec spec = yaml.readValue(ADAPTER, McpServerSpec.class);

        JsonNode skills = json.valueToTree(spec).path("skills");

        assertEquals("get-ship", skills.path("fleet-ops").path("allowed-tools").asString());
        assertTrue(skills.path("fleet-ops").path("disable-model-invocation").isBoolean());
        assertFalse(skills.path("install-ikanos").has("tools"));
        assertFalse(skills.path("install-ikanos").has("instructions"));
    }

    @Test
    void setSkillsShouldIgnoreNull() {
        McpServerSpec spec = new McpServerSpec();
        McpServerSkillSpec skill = new McpServerSkillSpec();
        skill.setName("fleet-ops");
        spec.setSkills(Map.of("fleet-ops", skill));

        spec.setSkills(null);

        assertEquals(1, spec.getSkills().size());
    }
}
