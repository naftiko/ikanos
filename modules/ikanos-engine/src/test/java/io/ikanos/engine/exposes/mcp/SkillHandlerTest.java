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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.engine.exposes.mcp.SkillManifest.SkillFile;
import io.ikanos.spec.exposes.mcp.McpServerSkillSpec;

class SkillHandlerTest {

    @TempDir
    Path tempDir;

    private static McpServerSkillSpec skill(String name) {
        McpServerSkillSpec skill = new McpServerSkillSpec();
        skill.setName(name);
        skill.setDescription("Find ships across registries.");
        return skill;
    }

    private static SkillHandler handler(McpServerSkillSpec... skills) {
        Map<String, McpServerSkillSpec> map = new LinkedHashMap<>();
        for (McpServerSkillSpec skill : skills) {
            map.put(skill.getName(), skill);
        }
        return new SkillHandler(map, Set.of("get-ship"), List.of());
    }

    private Path location(String... files) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("skill"));
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "content of " + file);
        }
        return root;
    }

    private static List<String> paths(SkillManifest manifest) {
        return manifest.files().stream().map(SkillFile::path).toList();
    }

    // ── manifest ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void constructorShouldSnapshotGuidanceOnlySkillAsSingleSkillMd() {
        SkillManifest manifest = handler(skill("install-ikanos")).list().get(0);

        assertThat(manifest.uri()).isEqualTo("skill://install-ikanos/SKILL.md");
        assertThat(paths(manifest)).containsExactly("SKILL.md");
        assertThat(manifest.files().get(0).mimeType()).isEqualTo("text/markdown");
    }

    @Test
    void constructorShouldPublishLocationFilesSortedAfterSkillMd() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location("guides/lookup.md", "README.md", "data.json").toUri().toString());

        SkillManifest manifest = handler(skill).list().get(0);

        assertThat(paths(manifest)).containsExactly("SKILL.md", "README.md", "data.json",
                "guides/lookup.md");
        assertThat(manifest.file("skill://fleet-ops/guides/lookup.md")).isNotNull();
    }

    @Test
    void digestShouldBeSha256OfTheServedBytes() {
        SkillFile skillMd = handler(skill("fleet-ops")).list().get(0).files().get(0);

        assertThat(skillMd.digest()).matches("sha256:[0-9a-f]{64}")
                .isEqualTo(SkillHandler.digest(skillMd.bytes()));
        assertThat(skillMd.size()).isEqualTo(skillMd.bytes().length);
    }

    @Test
    void digestShouldMatchKnownSha256Vector() {
        assertThat(SkillHandler.digest("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(
                "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void skillMdShouldNotDependOnTheToolsTheSkillDependsOn() {
        McpServerSkillSpec withTool = skill("fleet-ops");
        withTool.setTools(List.of("get-ship"));

        String withToolDigest = handler(withTool).list().get(0).files().get(0).digest();
        String withoutToolDigest = handler(skill("fleet-ops")).list().get(0).files().get(0).digest();

        assertThat(withToolDigest).isEqualTo(withoutToolDigest);
    }

    @Test
    void constructorShouldKeepAuthoredBodyAndReplaceItsFrontmatter() throws Exception {
        Path root = location();
        Files.writeString(root.resolve("SKILL.md"),
                "---\nname: fleet-ops\ndescription: Old.\n---\n\n# Fleet\n\nTry legacy too.\n");
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(root.toUri().toString());

        SkillManifest manifest = handler(skill).list().get(0);
        String skillMd = new String(manifest.files().get(0).bytes(), StandardCharsets.UTF_8);

        assertThat(paths(manifest)).containsExactly("SKILL.md");
        assertThat(skillMd).contains("description: \"Find ships across registries.\"")
                .doesNotContain("Old.").endsWith("# Fleet\n\nTry legacy too.\n");
    }

    // ── publication rules ────────────────────────────────────────────────────────────────────────

    @Test
    void constructorShouldNotPublishDotfilesDotDirectoriesOrBackups() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location("guide.md", ".env", ".git/config", "guide.md~", "guide.md.bak",
                ".guide.md.swp", "guide.swo", "#guide.md#").toUri().toString());

        SkillManifest manifest = handler(skill).list().get(0);

        assertThat(paths(manifest)).containsExactly("SKILL.md", "guide.md");
    }

    @Test
    void constructorShouldRejectSymbolicLink() throws Exception {
        Path root = location("guide.md");
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links cannot be created on this platform: " + e);
        }
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(root.toUri().toString());

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic link").hasMessageContaining("link.txt");
    }

    @Test
    void constructorShouldRejectFileNameThatCannotBeAUri() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location("my notes.md").toUri().toString());

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("my notes.md");
    }

    @Test
    void constructorShouldSkipSkillOverTheFileLimit() throws Exception {
        String[] files = new String[SkillHandler.MAX_FILES];
        for (int i = 0; i < files.length; i++) {
            files[i] = "f" + i + ".txt";
        }
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location(files).toUri().toString());

        assertThat(handler(skill).isEmpty()).isTrue();
    }

    @Test
    void constructorShouldServeSkillAtTheFileLimit() throws Exception {
        String[] files = new String[SkillHandler.MAX_FILES - 1];
        for (int i = 0; i < files.length; i++) {
            files[i] = "f" + i + ".txt";
        }
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location(files).toUri().toString());

        assertThat(handler(skill).list().get(0).files()).hasSize(SkillHandler.MAX_FILES);
    }

    @Test
    void isExcludedShouldMatchOnlyUnpublishedNames() {
        assertThat(List.of(".env", ".git", "a~", "a.bak", "a.swp", "a.swo", "#a#"))
                .allMatch(SkillHandler::isExcluded);
        assertThat(List.of("README.md", "a.md", "#", "a#b", "notes.backup"))
                .noneMatch(SkillHandler::isExcluded);
    }

    // ── load-time validation ─────────────────────────────────────────────────────────────────────

    @Test
    void constructorShouldRejectUnknownTool() {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setTools(List.of("list-legacy-vessels"));

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("list-legacy-vessels").hasMessageContaining("get-ship");
    }

    @Test
    void constructorShouldRejectInvalidSkillName() {
        assertThatThrownBy(() -> handler(skill("Fleet--Ops")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Fleet--Ops");
    }

    @Test
    void constructorShouldRejectSkillWithoutDescription() {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setDescription(" ");

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    void constructorShouldRejectInstructionsWithoutLocation() {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setInstructions(Map.of("guide", "guide.md"));

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("location");
    }

    @Test
    void constructorShouldRejectMissingInstructionFile() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location("guide.md").toUri().toString());
        skill.setInstructions(Map.of("guide", "missing.md"));

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.md");
    }

    @Test
    void constructorShouldRejectInstructionPointingToExcludedFile() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location("guide.md.bak").toUri().toString());
        skill.setInstructions(Map.of("guide", "guide.md.bak"));

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excluded from publication");
    }

    @Test
    void constructorShouldRejectLocationThatIsNotADirectory() {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(tempDir.resolve("absent").toUri().toString());

        assertThatThrownBy(() -> handler(skill)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a readable directory");
    }

    @Test
    void constructorShouldRejectResourceUsingTheSkillScheme() {
        Map<String, McpServerSkillSpec> skills = Map.of("fleet-ops", skill("fleet-ops"));

        assertThatThrownBy(() -> new SkillHandler(skills, Set.of(),
                List.of("skill://fleet-ops/SKILL.md")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reserved");
    }

    @Test
    void constructorShouldAcceptResourceUsingTheSkillSchemeWhenNoSkillIsDeclared() {
        assertThat(new SkillHandler(Map.of(), Set.of(), List.of("skill://x/y")).isEmpty())
                .isTrue();
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void readShouldServeTextFileAsUtf8Text() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(location("guide.md").toUri().toString());

        ResourceHandler.ResourceContent content =
                handler(skill).read("skill://fleet-ops/guide.md");

        assertThat(content.text).isEqualTo("content of guide.md");
        assertThat(content.mimeType).isEqualTo("text/markdown");
        assertThat(content.blob).isNull();
    }

    @Test
    void readShouldServeBinaryFileAsBase64Blob() throws Exception {
        Path root = location();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        Files.write(root.resolve("diagram.png"), png);
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(root.toUri().toString());

        ResourceHandler.ResourceContent content =
                handler(skill).read("skill://fleet-ops/diagram.png");

        assertThat(content.blob).isEqualTo(Base64.getEncoder().encodeToString(png));
        assertThat(content.text).isNull();
    }

    @Test
    void readShouldServeSnapshotEvenAfterFileChangesOnDisk() throws Exception {
        Path root = location("guide.md");
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setLocation(root.toUri().toString());
        SkillHandler handler = handler(skill);

        Files.writeString(root.resolve("guide.md"), "edited after startup");

        assertThat(handler.read("skill://fleet-ops/guide.md").text)
                .isEqualTo("content of guide.md");
    }

    @Test
    void readShouldAttachMetaToSkillMdOnly() throws Exception {
        McpServerSkillSpec skill = skill("fleet-ops");
        skill.setArgumentHint("<imo>");
        skill.setLocation(location("guide.md").toUri().toString());
        SkillHandler handler = handler(skill);

        assertThat(handler.read("skill://fleet-ops/SKILL.md").meta)
                .containsEntry("io.modelcontextprotocol.skills/argument-hint", "<imo>");
        assertThat(handler.read("skill://fleet-ops/guide.md").meta).isNull();
    }

    @Test
    void readShouldRejectUnknownUri() {
        SkillHandler handler = handler(skill("fleet-ops"));

        assertThatThrownBy(() -> handler.read("skill://fleet-ops/.env"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getShouldRejectUriThatIsNotASkillMd() {
        SkillHandler handler = handler(skill("fleet-ops"));

        assertThatThrownBy(() -> handler.get("skill://other/SKILL.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill://other/SKILL.md");
    }
}
