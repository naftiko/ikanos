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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of one skill as served over MCP (SEP-2640), taken once at adapter start.
 *
 * <p>Served bytes come from this snapshot, never from disk, so a file edited after startup cannot
 * stop matching the digest published by {@code skills/list}.</p>
 *
 * @param name        the skill name, equal to the frontmatter {@code name}
 * @param uri         the {@code skill://<name>/SKILL.md} URI
 * @param frontmatter the {@code SKILL.md} frontmatter, in rendering order
 * @param meta        the {@code SKILL.md} resource {@code _meta}, or {@code null}
 * @param files       every published file, {@code SKILL.md} first, then by path
 */
public record SkillManifest(String name, String uri, Map<String, Object> frontmatter,
        Map<String, Object> meta, List<SkillFile> files) {

    public SkillManifest {
        frontmatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
        meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<>(meta)) : null;
        files = List.copyOf(files);
    }

    /** @return the frontmatter {@code description}. */
    public String description() {
        return (String) frontmatter.get("description");
    }

    /** @return the published file with this URI, or {@code null}. */
    public SkillFile file(String fileUri) {
        for (SkillFile file : files) {
            if (file.uri().equals(fileUri)) {
                return file;
            }
        }
        return null;
    }

    /**
     * One published file of a skill.
     *
     * @param path     the path relative to the skill root, with {@code /} separators
     * @param uri      the {@code skill://} URI of the file
     * @param mimeType the media type, derived from the file extension
     * @param bytes    the raw bytes, as hashed and served
     * @param digest   {@code sha256:} followed by 64 lowercase hex characters
     */
    public record SkillFile(String path, String uri, String mimeType, byte[] bytes,
            String digest) {

        /** @return the byte length of the file. */
        public long size() {
            return bytes.length;
        }
    }
}
