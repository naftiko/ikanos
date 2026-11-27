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
package io.ikanos.engine.exposes.mcp.files;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the draft MCP files extension puts things on the wire: its identifier, its settings and
 * the {@code _meta} placement of download targets. The working group lists the placement as an
 * open question, so it is written here and nowhere else.
 */
public final class McpFiles {

    /** Identifier of the files extension, in capabilities and in result {@code _meta}. */
    public static final String EXTENSION_ID = "io.modelcontextprotocol/files";

    /** Setting of a client that accepts download targets. */
    public static final String DOWNLOAD = "download";

    /** Setting of a peer that supports out-of-band uploads. */
    public static final String UPLOAD = "upload";

    /** Member of the result {@code _meta} entry that lists the download targets. */
    public static final String DOWNLOADS = "downloads";

    /** Whether the {@code tools/call} being run declared {@code download}. */
    private static final ThreadLocal<Boolean> DOWNLOADS_ACCEPTED = new ThreadLocal<>();

    private McpFiles() {
    }

    /**
     * The settings {@code server/discover} advertises: {@code download} only, since the engine
     * does not accept uploads yet.
     */
    public static ObjectNode serverSettings(ObjectMapper mapper) {
        ObjectNode settings = mapper.createObjectNode();
        settings.putObject(DOWNLOAD);
        return settings;
    }

    /**
     * Whether a client's {@code extensions} object declares {@code setting} of the files
     * extension. A setting is declared by any value but {@code false} or {@code null}: the draft
     * sends an empty object.
     */
    public static boolean declares(JsonNode extensions, String setting) {
        JsonNode files = extensions != null ? extensions.path(EXTENSION_ID) : null;
        if (files == null || !files.isObject()) {
            return false;
        }
        JsonNode value = files.get(setting);
        return value != null && !value.isNull()
                && !(value.isBoolean() && !value.booleanValue());
    }

    /** The result {@code _meta} that lists {@code targets}. */
    public static Map<String, Object> resultMeta(List<Map<String, Object>> targets) {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put(DOWNLOADS, targets);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(EXTENSION_ID, files);
        return meta;
    }

    /** The download targets of a result serialized to JSON, or a missing node when it has none. */
    public static JsonNode downloadsOf(JsonNode result) {
        return result.path("_meta").path(EXTENSION_ID).path(DOWNLOADS);
    }

    /**
     * Runs {@code work}, a tool call, with download targets allowed or not, as the
     * {@code tools/call} request declared. A tool call run elsewhere, such as a task or a call of
     * the call meta-tool, never returns one.
     */
    public static <T> T withDownloads(boolean accepted, Supplier<T> work) {
        Boolean previous = DOWNLOADS_ACCEPTED.get();
        DOWNLOADS_ACCEPTED.set(accepted);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                DOWNLOADS_ACCEPTED.remove();
            } else {
                DOWNLOADS_ACCEPTED.set(previous);
            }
        }
    }

    /** Whether the tool call being run may return download targets. */
    public static boolean downloadsAccepted() {
        return Boolean.TRUE.equals(DOWNLOADS_ACCEPTED.get());
    }
}
