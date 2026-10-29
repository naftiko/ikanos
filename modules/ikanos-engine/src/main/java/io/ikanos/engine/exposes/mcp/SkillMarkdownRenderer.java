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

import java.util.LinkedHashMap;
import java.util.Map;

import io.ikanos.spec.exposes.mcp.McpServerSkillSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Renders a skill's {@code SKILL.md} from its spec.
 *
 * <p>The frontmatter always comes from the spec. The body is the authored {@code SKILL.md} body
 * when one exists in the skill's {@code location}, otherwise it is generated from the description
 * and the instruction files. The skill's {@code tools} are never rendered: clients get tool names
 * and descriptions from {@code tools/list}, so editing a tool description does not change the
 * {@code SKILL.md} digest.</p>
 *
 * <p>Rendering is deterministic (stable key order, LF line endings, no line splitting), so the
 * digest is stable across restarts and operating systems.</p>
 */
final class SkillMarkdownRenderer {

    /** Prefix of extra frontmatter fields mirrored in the {@code SKILL.md} resource {@code _meta}. */
    static final String META_PREFIX = "io.modelcontextprotocol.skills/";

    private static final String FENCE = "---";

    private static final YAMLMapper YAML = IkanosMappers.yamlBuilder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .disable(YAMLWriteFeature.SPLIT_LINES)
            .build();

    private SkillMarkdownRenderer() {}

    /** An authored {@code SKILL.md} split into its frontmatter (or {@code null}) and body. */
    record Authored(Map<String, Object> frontmatter, String body) {
    }

    /**
     * Build the frontmatter of a skill in rendering order: {@code name} and {@code description}
     * first, then the optional Agent Skills fields that are set.
     */
    static Map<String, Object> frontmatter(McpServerSkillSpec skill) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("name", skill.getName());
        fm.put("description", skill.getDescription());
        putIfSet(fm, "license", skill.getLicense());
        putIfSet(fm, "compatibility", skill.getCompatibility());
        if (skill.getMetadata() != null && !skill.getMetadata().isEmpty()) {
            fm.put("metadata", new LinkedHashMap<>(skill.getMetadata()));
        }
        putIfSet(fm, "allowed-tools", skill.getAllowedTools());
        putIfSet(fm, "argument-hint", skill.getArgumentHint());
        putIfSet(fm, "user-invocable", skill.getUserInvocable());
        putIfSet(fm, "disable-model-invocation", skill.getDisableModelInvocation());
        return fm;
    }

    /**
     * Build the {@code SKILL.md} resource {@code _meta}: the frontmatter fields beyond the Agent
     * Skills base format, under {@link #META_PREFIX}. Returns {@code null} when there are none.
     */
    static Map<String, Object> resourceMeta(McpServerSkillSpec skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfSet(meta, META_PREFIX + "argument-hint", skill.getArgumentHint());
        putIfSet(meta, META_PREFIX + "user-invocable", skill.getUserInvocable());
        putIfSet(meta, META_PREFIX + "disable-model-invocation",
                skill.getDisableModelInvocation());
        return meta.isEmpty() ? null : meta;
    }

    /**
     * Generate the body of a skill that has no authored {@code SKILL.md}: a title, the description
     * and, when declared, a list of links to the instruction files.
     */
    static String generatedBody(McpServerSkillSpec skill) {
        StringBuilder body = new StringBuilder();
        body.append("# ").append(skill.getName()).append("\n\n");
        body.append(skill.getDescription().strip()).append('\n');
        if (!skill.getInstructions().isEmpty()) {
            body.append("\n## Instructions\n\n");
            skill.getInstructions().forEach((name, path) ->
                    body.append("- [").append(name).append("](").append(path).append(")\n"));
        }
        return body.toString();
    }

    /** Render {@code SKILL.md}: the frontmatter between {@code ---} fences, then the body. */
    static String render(Map<String, Object> frontmatter, String body) {
        String yaml = normalize(YAML.writeValueAsString(frontmatter));
        String normalizedBody = normalize(body).stripLeading();
        if (!normalizedBody.endsWith("\n")) {
            normalizedBody = normalizedBody + "\n";
        }
        return FENCE + "\n" + yaml + (yaml.endsWith("\n") ? "" : "\n") + FENCE + "\n\n"
                + normalizedBody;
    }

    /**
     * Split an authored {@code SKILL.md} into its frontmatter and body. A file without a complete
     * {@code ---} fenced block at the top is all body, with a {@code null} frontmatter. A fenced
     * block that is not a YAML mapping is dropped, also with a {@code null} frontmatter.
     */
    static Authored parseAuthored(String content) {
        String text = normalize(content);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (!text.startsWith(FENCE + "\n")) {
            return new Authored(null, text);
        }
        int end = text.indexOf("\n" + FENCE, FENCE.length());
        if (end < 0) {
            return new Authored(null, text);
        }
        String yaml = text.substring(FENCE.length() + 1, end + 1);
        int bodyStart = text.indexOf('\n', end + 1 + FENCE.length());
        String body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
        Map<String, Object> frontmatter;
        try {
            frontmatter = yaml.isBlank() ? Map.of()
                    : YAML.readValue(yaml, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            frontmatter = null;
        }
        return new Authored(frontmatter, body);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void putIfSet(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
