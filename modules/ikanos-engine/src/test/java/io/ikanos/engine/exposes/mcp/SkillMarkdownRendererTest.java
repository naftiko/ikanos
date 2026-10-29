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
package io.ikanos.engine.exposes.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.exposes.mcp.McpServerSkillSpec;

class SkillMarkdownRendererTest {

    private static McpServerSkillSpec skill() {
        McpServerSkillSpec skill = new McpServerSkillSpec();
        skill.setName("fleet-ops");
        skill.setDescription("Find ships across registries.");
        return skill;
    }

    @Test
    void renderShouldProduceFencedFrontmatterThenGeneratedBody() {
        McpServerSkillSpec skill = skill();
        skill.setMetadata(Map.of("team", "fleet"));
        skill.setUserInvocable(true);

        String markdown = SkillMarkdownRenderer.render(SkillMarkdownRenderer.frontmatter(skill),
                SkillMarkdownRenderer.generatedBody(skill));

        assertThat(markdown).isEqualTo("""
                ---
                name: "fleet-ops"
                description: "Find ships across registries."
                metadata:
                  team: "fleet"
                user-invocable: true
                ---

                # fleet-ops

                Find ships across registries.
                """);
    }

    @Test
    void frontmatterShouldPutNameAndDescriptionFirstAndSkipUnsetFields() {
        McpServerSkillSpec skill = skill();
        skill.setDisableModelInvocation(false);
        skill.setLicense("Apache-2.0");

        Map<String, Object> frontmatter = SkillMarkdownRenderer.frontmatter(skill);

        assertThat(frontmatter.keySet()).containsExactly("name", "description", "license",
                "disable-model-invocation");
    }

    @Test
    void generatedBodyShouldLinkInstructionsAndNeverListTools() {
        McpServerSkillSpec skill = skill();
        skill.setTools(List.of("get-ship"));
        Map<String, String> instructions = new LinkedHashMap<>();
        instructions.put("lookup-fallback", "guides/lookup-fallback.md");
        skill.setInstructions(instructions);

        String body = SkillMarkdownRenderer.generatedBody(skill);

        assertThat(body).contains("## Instructions\n\n- [lookup-fallback](guides/lookup-fallback.md)\n");
        assertThat(body).doesNotContain("get-ship");
    }

    @Test
    void renderShouldNormalizeLineEndingsToLf() {
        String markdown = SkillMarkdownRenderer.render(
                SkillMarkdownRenderer.frontmatter(skill()), "# Title\r\n\r\nStep one.\r\n");

        assertThat(markdown).doesNotContain("\r").endsWith("# Title\n\nStep one.\n");
    }

    @Test
    void resourceMetaShouldMirrorClientSpecificFieldsUnderExtensionPrefix() {
        McpServerSkillSpec skill = skill();
        skill.setArgumentHint("<imo>");
        skill.setDisableModelInvocation(true);

        Map<String, Object> meta = SkillMarkdownRenderer.resourceMeta(skill);

        assertThat(meta).containsExactly(
                Map.entry("io.modelcontextprotocol.skills/argument-hint", "<imo>"),
                Map.entry("io.modelcontextprotocol.skills/disable-model-invocation", true));
    }

    @Test
    void resourceMetaShouldBeNullWhenNoClientSpecificFieldIsSet() {
        assertThat(SkillMarkdownRenderer.resourceMeta(skill())).isNull();
    }

    @Test
    void parseAuthoredShouldSplitFrontmatterFromBody() {
        SkillMarkdownRenderer.Authored authored = SkillMarkdownRenderer.parseAuthored("""
                ---
                name: fleet-ops
                description: Old wording.
                ---

                # Fleet operations

                If get-ship finds nothing, try list-legacy-vessels.
                """);

        assertThat(authored.frontmatter()).containsEntry("description", "Old wording.");
        assertThat(authored.body()).isEqualTo(
                "\n# Fleet operations\n\nIf get-ship finds nothing, try list-legacy-vessels.\n");
    }

    @Test
    void parseAuthoredShouldTreatFileWithoutFenceAsBody() {
        SkillMarkdownRenderer.Authored authored =
                SkillMarkdownRenderer.parseAuthored("# Fleet operations\n");

        assertThat(authored.frontmatter()).isNull();
        assertThat(authored.body()).isEqualTo("# Fleet operations\n");
    }

    @Test
    void parseAuthoredShouldTreatUnterminatedFenceAsBody() {
        SkillMarkdownRenderer.Authored authored =
                SkillMarkdownRenderer.parseAuthored("---\nname: fleet-ops\n# Fleet operations\n");

        assertThat(authored.frontmatter()).isNull();
        assertThat(authored.body()).startsWith("---\n");
    }

    @Test
    void renderShouldReplaceAuthoredFrontmatterWithTheSpecOne() {
        SkillMarkdownRenderer.Authored authored = SkillMarkdownRenderer.parseAuthored(
                "---\nname: other\ndescription: Old wording.\n---\n\n# Fleet operations\n");

        String markdown = SkillMarkdownRenderer.render(
                SkillMarkdownRenderer.frontmatter(skill()), authored.body());

        assertThat(markdown).startsWith("---\nname: \"fleet-ops\"\n")
                .doesNotContain("Old wording").endsWith("---\n\n# Fleet operations\n");
    }
}
