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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integration tests for the Skills Extension (SEP-2640) on an exposed MCP server: a capability is
 * loaded end-to-end and {@code skills/list}, {@code skills/get}, {@code resources/*} and
 * {@code server/discover} are asserted on the JSON-RPC wire through {@link ProtocolDispatcher}.
 * No upstream is called.
 */
class McpSkillsIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String SKILL_URI = "skill://fleet-ops/SKILL.md";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    @TempDir
    Path tempDir;

    private String location;

    @BeforeEach
    void writeSkillFiles() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("fleet-ops"));
        Files.writeString(dir.resolve("lookup-fallback.md"),
                "If get-ship finds nothing, call list-legacy-vessels before giving up.\n");
        Files.writeString(dir.resolve(".env"), "TOKEN=secret\n");
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/escalation.md"), "# Escalate to the registry\n");
        location = dir.toUri().toString();
    }

    // ── server/discover ──────────────────────────────────────────────────────────────────────────

    @Test
    void serverDiscoverShouldAdvertiseSkillsExtensionAndResources() throws Exception {
        JsonNode capabilities = dispatch(load(fleetCapability()), "server/discover", "{}")
                .path("result").path("capabilities");

        assertThat(capabilities.path("extensions").has(SkillHandler.EXTENSION_ID)).isTrue();
        assertThat(capabilities.path("extensions").path(SkillHandler.EXTENSION_ID)
                .path("directoryRead").isBoolean()).isTrue();
        assertThat(capabilities.path("extensions").path(SkillHandler.EXTENSION_ID)
                .path("directoryRead").asBoolean()).isTrue();
        assertThat(capabilities.has("resources")).isTrue();
        assertThat(capabilities.has("tools")).isTrue();
    }

    @Test
    void serverDiscoverShouldAppendSkillUrisToTheInstructions() throws Exception {
        String instructions = dispatch(load(fleetCapability()), "server/discover", "{}")
                .path("result").path("instructions").asString();

        assertThat(instructions).startsWith("Fleet tools.")
                .contains("Agent skills").contains("- " + SKILL_URI);
    }

    @Test
    void serverDiscoverShouldAdvertiseSkillsAndMcpAppsTogether() throws Exception {
        Path view = Files.createDirectories(tempDir.resolve("view"));
        Files.writeString(view.resolve("index.html"), "<html></html>");

        JsonNode extensions = dispatch(load(appsAndSkillsCapability(view.toUri().toString())),
                "server/discover", "{}").path("result").path("capabilities").path("extensions");

        assertThat(extensions.has(McpAppsMetadata.EXTENSION_ID)).isTrue();
        assertThat(extensions.has(SkillHandler.EXTENSION_ID)).isTrue();
    }

    @Test
    void serverDiscoverShouldNotAdvertiseToolsForSkillsOnlyServer() throws Exception {
        JsonNode capabilities = dispatch(load(skillsOnlyCapability()), "server/discover", "{}")
                .path("result").path("capabilities");

        assertThat(capabilities.has("tools")).isFalse();
        assertThat(capabilities.has("resources")).isTrue();
        assertThat(capabilities.path("extensions").has(SkillHandler.EXTENSION_ID)).isTrue();
    }

    // ── skills/list and skills/get ───────────────────────────────────────────────────────────────

    @Test
    void skillsListShouldReturnCompleteManifestWithDigestsAndSizes() throws Exception {
        McpServerAdapter adapter = load(fleetCapability());

        JsonNode result = dispatch(adapter, "skills/list", "{}").path("result");
        JsonNode entry = result.path("skills").get(0);

        assertThat(result.path("resultType").asString()).isEqualTo("complete");
        assertThat(result.has("ttlMs")).isTrue();
        assertThat(result.has("cacheScope")).isTrue();
        assertThat(entry.path("uri").asString()).isEqualTo(SKILL_URI);
        assertThat(uris(entry.path("resources"))).containsExactly(SKILL_URI,
                "skill://fleet-ops/lookup-fallback.md", "skill://fleet-ops/templates/escalation.md");
        for (JsonNode resource : entry.path("resources")) {
            byte[] bytes = readBytes(adapter, resource.path("uri").asString());
            assertThat(resource.path("digest").asString()).isEqualTo(sha256(bytes));
            assertThat(resource.path("size").asLong()).isEqualTo(bytes.length);
        }
    }

    @Test
    void skillsListFrontmatterShouldMatchTheServedSkillMd() throws Exception {
        McpServerAdapter adapter = load(fleetCapability());
        JsonNode frontmatter = dispatch(adapter, "skills/list", "{}")
                .path("result").path("skills").get(0).path("frontmatter");

        String skillMd = new String(readBytes(adapter, SKILL_URI), StandardCharsets.UTF_8);
        String yaml = skillMd.substring(4, skillMd.indexOf("\n---\n") + 1);
        Map<String, Object> parsed =
                yamlMapper.readValue(yaml, new TypeReference<Map<String, Object>>() {});

        assertThat(jsonMapper.convertValue(frontmatter,
                new TypeReference<Map<String, Object>>() {})).isEqualTo(parsed);
        assertThat(frontmatter.path("name").asString()).isEqualTo("fleet-ops");
    }

    @Test
    void skillsListShouldRejectCursor() throws Exception {
        JsonNode error = dispatch(load(fleetCapability()), "skills/list",
                "{\"cursor\":\"page-2\"}").path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void skillsGetShouldReturnTheSameEntryAsSkillsList() throws Exception {
        McpServerAdapter adapter = load(fleetCapability());

        JsonNode listed = dispatch(adapter, "skills/list", "{}")
                .path("result").path("skills").get(0);
        JsonNode result = dispatch(adapter, "skills/get", "{\"uri\":\"" + SKILL_URI + "\"}")
                .path("result");

        assertThat(result.path("skill")).isEqualTo(listed);
        assertThat(result.path("resultType").asString()).isEqualTo("complete");
    }

    @Test
    void skillsGetShouldRejectUnknownSkill() throws Exception {
        JsonNode error = dispatch(load(fleetCapability()), "skills/get",
                "{\"uri\":\"skill://unknown/SKILL.md\"}").path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void skillsGetShouldRejectMissingUri() throws Exception {
        JsonNode error = dispatch(load(fleetCapability()), "skills/get", "{}").path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void skillsListShouldWorkOnStdioTransport() throws Exception {
        String yaml = skillsOnlyCapability()
                .replace("address: \"localhost\"\n", "transport: \"stdio\"\n")
                .replace("      port: 0\n", "");

        McpServerAdapter adapter = load(yaml);
        JsonNode skills = dispatch(adapter, "skills/list", "{}").path("result").path("skills");

        assertThat(adapter.getMcpServerSpec().isStdio()).isTrue();
        assertThat(uris(skills)).containsExactly("skill://install-ikanos/SKILL.md");
    }

    @Test
    void skillsListShouldBeUnknownMethodWhenServerServesNoSkill() throws Exception {
        JsonNode error = dispatch(load(toolsOnlyCapability()), "skills/list", "{}").path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32601);
    }

    // ── resources/list and resources/read ────────────────────────────────────────────────────────

    @Test
    void resourcesListShouldIncludeSkillMdButNotSupportingFiles() throws Exception {
        JsonNode resources = dispatch(load(fleetCapability()), "resources/list", "{}")
                .path("result").path("resources");

        assertThat(uris(resources)).containsExactly(SKILL_URI);
        JsonNode entry = resources.get(0);
        assertThat(entry.path("name").asString()).isEqualTo("fleet-ops");
        assertThat(entry.path("description").asString())
                .isEqualTo("Find ships across the current and legacy registries.");
        assertThat(entry.path("mimeType").asString()).isEqualTo("text/markdown");
    }

    @Test
    void resourcesReadShouldServeGeneratedSkillMdLinkingInstructions() throws Exception {
        JsonNode content = dispatch(load(fleetCapability()), "resources/read",
                "{\"uri\":\"" + SKILL_URI + "\"}").path("result").path("contents").get(0);

        assertThat(content.path("mimeType").asString()).isEqualTo("text/markdown");
        assertThat(content.path("text").asString()).startsWith("---\nname: \"fleet-ops\"\n")
                .contains("- [lookup-fallback](lookup-fallback.md)")
                .doesNotContain("get-ship");
    }

    @Test
    void resourcesReadShouldNotServeExcludedFile() throws Exception {
        JsonNode error = dispatch(load(fleetCapability()), "resources/read",
                "{\"uri\":\"skill://fleet-ops/.env\"}").path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32602);
    }

    // ── resources/directory/read ─────────────────────────────────────────────────────────────────

    @Test
    void directoryReadShouldListDirectChildrenOfTheSkillRoot() throws Exception {
        JsonNode result = dispatch(load(fleetCapability()), "resources/directory/read",
                "{\"uri\":\"skill://fleet-ops\"}").path("result");
        JsonNode resources = result.path("resources");

        assertThat(result.path("resultType").asString()).isEqualTo("complete");
        assertThat(result.has("nextCursor")).isFalse();
        assertThat(uris(resources)).containsExactly(SKILL_URI,
                "skill://fleet-ops/lookup-fallback.md", "skill://fleet-ops/templates");
        assertThat(resources.get(1).path("name").asString()).isEqualTo("lookup-fallback.md");
        assertThat(resources.get(1).path("mimeType").asString()).isEqualTo("text/markdown");
        assertThat(resources.get(1).has("size")).isTrue();
        assertThat(resources.get(2).path("name").asString()).isEqualTo("templates");
        assertThat(resources.get(2).path("mimeType").asString())
                .isEqualTo(SkillHandler.DIRECTORY);
        assertThat(resources.get(2).has("size")).isFalse();
    }

    @Test
    void directoryReadShouldListSubdirectory() throws Exception {
        JsonNode resources = dispatch(load(fleetCapability()), "resources/directory/read",
                "{\"uri\":\"skill://fleet-ops/templates\"}").path("result").path("resources");

        assertThat(uris(resources)).containsExactly("skill://fleet-ops/templates/escalation.md");
    }

    @Test
    void directoryReadShouldRejectFileUnknownDirectoryAndCursor() throws Exception {
        McpServerAdapter adapter = load(fleetCapability());

        for (String params : List.of("{\"uri\":\"" + SKILL_URI + "\"}",
                "{\"uri\":\"skill://fleet-ops/missing\"}", "{\"uri\":\"skill://other\"}",
                "{}", "{\"uri\":\"skill://fleet-ops\",\"cursor\":\"page-2\"}")) {
            assertThat(dispatch(adapter, "resources/directory/read", params)
                    .path("error").path("code").asInt()).as(params).isEqualTo(-32602);
        }
    }

    @Test
    void directoryReadShouldBeUnknownMethodWhenServerServesNoSkill() throws Exception {
        JsonNode error = dispatch(load(toolsOnlyCapability()), "resources/directory/read",
                "{\"uri\":\"skill://fleet-ops\"}").path("error");

        assertThat(error.path("code").asInt()).isEqualTo(-32601);
    }

    // ── load-time validation ─────────────────────────────────────────────────────────────────────

    @Test
    void capabilityLoadShouldFailWhenSkillDependsOnUndeclaredTool() {
        String yaml = fleetCapability().replace("tools: [\"get-ship\"]",
                "tools: [\"get-ship\", \"list-legacy-vessels\"]");

        assertThatThrownBy(() -> load(yaml)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("list-legacy-vessels");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String fleetCapability() {
        return """
                ikanos: "%s"
                info:
                  display: "Fleet"
                  description: "Skills integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "fleet"
                      description: "Fleet tools."
                      tools:
                        get-ship:
                          description: "Retrieve a ship by IMO number."
                          outputParameters:
                            - type: "string"
                              value: "ship"
                      skills:
                        fleet-ops:
                          description: "Find ships across the current and legacy registries."
                          tools: ["get-ship"]
                          location: "%s"
                          instructions:
                            lookup-fallback: "lookup-fallback.md"
                  consumes: []
                """.formatted(IKANOS, location);
    }

    private static String skillsOnlyCapability() {
        return """
                ikanos: "%s"
                info:
                  display: "Guides"
                  description: "Skills-only server"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "guides"
                      skills:
                        install-ikanos:
                          description: "Guide a developer through installing Ikanos."
                  consumes: []
                """.formatted(IKANOS);
    }

    private static String toolsOnlyCapability() {
        return """
                ikanos: "%s"
                info:
                  display: "Plain"
                  description: "No skills"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "plain"
                      tools:
                        ping:
                          description: "Ping."
                          outputParameters:
                            - type: "string"
                              value: "pong"
                  consumes: []
                """.formatted(IKANOS);
    }

    private static String appsAndSkillsCapability(String viewLocation) {
        return """
                ikanos: "%s"
                info:
                  display: "Apps and skills"
                  description: "Both extensions"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "weather"
                      resources:
                        forecast-view:
                          description: "Forecast view."
                          uri: "ui://weather/forecast"
                          location: "%s"
                          mimeType: "text/html;profile=mcp-app"
                      tools:
                        get-forecast:
                          description: "Forecast."
                          ui:
                            resourceUri: "ui://weather/forecast/index.html"
                          outputParameters:
                            - type: "string"
                              value: "sunny"
                      skills:
                        plan-trip:
                          description: "Plan a trip around the forecast."
                          tools: ["get-forecast"]
                  consumes: []
                """.formatted(IKANOS, viewLocation);
    }

    private McpServerAdapter load(String yaml) throws Exception {
        IkanosSpec spec = yamlMapper.readValue(yaml, IkanosSpec.class);
        return (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private byte[] readBytes(McpServerAdapter adapter, String uri) throws Exception {
        JsonNode content = dispatch(adapter, "resources/read", "{\"uri\":\"" + uri + "\"}")
                .path("result").path("contents").get(0);
        return content.path("text").asString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> uris(JsonNode entries) {
        List<String> uris = new ArrayList<>();
        entries.forEach(entry -> uris.add(entry.path("uri").asString()));
        return uris;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
