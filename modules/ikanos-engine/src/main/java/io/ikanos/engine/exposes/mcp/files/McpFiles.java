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

    /** Server setting: the largest total decoded size of the inline files of one request. */
    public static final String MAX_INLINE_BYTES = "maxInlineBytes";

    /** Keyword that marks a file argument in a tool's {@code inputSchema}. */
    public static final String FILE_KEYWORD = "x-mcp-file";

    /** Member of the result {@code _meta} entry that lists the download targets. */
    public static final String DOWNLOADS = "downloads";

    /** Whether the {@code tools/call} being run declared {@code download}. */
    private static final ThreadLocal<Boolean> DOWNLOADS_ACCEPTED = new ThreadLocal<>();

    private McpFiles() {
    }

    /** Member of an {@code upload_required} result that lists the upload targets. */
    public static final String UPLOADS = "uploads";

    /**
     * The settings {@code server/discover} advertises: {@code download}, the inline upload limit,
     * and {@code upload} when a tool takes files out of band. {@code upload} announces the
     * {@code upload_required} result, so it is left out when no tool can return one: inline
     * files need only {@code maxInlineBytes}.
     *
     * @param maxInlineBytes the largest total decoded size of the inline files of one request
     * @param upload whether at least one tool takes files out of band
     */
    public static ObjectNode serverSettings(ObjectMapper mapper, long maxInlineBytes,
            boolean upload) {
        ObjectNode settings = mapper.createObjectNode();
        if (upload) {
            settings.putObject(UPLOAD);
        }
        settings.putObject(DOWNLOAD);
        settings.put(MAX_INLINE_BYTES, maxInlineBytes);
        return settings;
    }

    /**
     * An upload target with {@code credentials: omit}: a presigned URL that carries its own
     * authorization, sent to with {@code PUT} (the default method, so left out).
     *
     * @param expiresAt when the URL expires, or {@code null}
     */
    public static ObjectNode uploadTarget(ObjectMapper mapper, String fileId, String url,
            Map<String, String> headers, java.time.Instant expiresAt) {
        ObjectNode target = mapper.createObjectNode();
        target.put("fileId", fileId);
        target.put("url", url);
        if (headers != null && !headers.isEmpty()) {
            ObjectNode sent = target.putObject("headers");
            headers.forEach(sent::put);
        }
        target.put("credentials", "omit");
        if (expiresAt != null) {
            target.put("expiresAt", expiresAt.toString());
        }
        return target;
    }

    /**
     * An upload target hosted on the adapter: a {@code path} under the MCP address, sent to
     * with {@code PUT} (the default method, so left out) and {@code credentials: include}, so
     * the client authenticates it as it does its MCP requests.
     *
     * @param path the target path, {@code /files/<token>}
     * @param mimeType the declared media type, listed as the {@code Content-Type} to send
     */
    public static ObjectNode hostedUploadTarget(ObjectMapper mapper, String fileId, String path,
            String mimeType, java.time.Instant expiresAt) {
        ObjectNode target = mapper.createObjectNode();
        target.put("fileId", fileId);
        target.put("path", path);
        target.putObject("headers").put("Content-Type", mimeType);
        target.put("credentials", "include");
        target.put("expiresAt", expiresAt.toString());
        return target;
    }

    /**
     * The {@code requiredCapabilities} of a {@code -32021} error for a call that sends a file
     * out of band without declaring {@code upload}.
     */
    public static ObjectNode uploadCapability(ObjectMapper mapper) {
        ObjectNode required = mapper.createObjectNode();
        required.putObject("extensions").putObject(EXTENSION_ID).putObject(UPLOAD);
        return required;
    }

    /**
     * The schema of a file argument in a tool's {@code inputSchema}: the draft's
     * {@code FileInput} object, marked with {@link #FILE_KEYWORD}.
     *
     * @param description the argument description, or {@code null}
     * @param accept the accepted media types and extensions, may be empty
     * @param maxSize the largest file in bytes, or {@code null}
     */
    public static Map<String, Object> fileInputSchema(String description, List<String> accept,
            Long maxSize) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (description != null) {
            schema.put("description", description);
        }
        Map<String, Object> descriptor = new LinkedHashMap<>();
        if (accept != null && !accept.isEmpty()) {
            descriptor.put("accept", List.copyOf(accept));
        }
        if (maxSize != null) {
            descriptor.put("maxSize", maxSize);
        }
        schema.put(FILE_KEYWORD, descriptor);

        Map<String, Object> idSchema = new LinkedHashMap<>();
        idSchema.put("type", "string");
        idSchema.put("minLength", 1);
        idSchema.put("maxLength", 128);
        Map<String, Object> sizeSchema = new LinkedHashMap<>();
        sizeSchema.put("type", "integer");
        sizeSchema.put("minimum", 0);
        Map<String, Object> digestProperties = new LinkedHashMap<>();
        digestProperties.put("algorithm", Map.of("const", "sha-256"));
        digestProperties.put("value", Map.of("type", "string"));
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("type", "object");
        digest.put("properties", digestProperties);
        digest.put("required", List.of("algorithm", "value"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", idSchema);
        properties.put("name", Map.of("type", "string"));
        properties.put("mimeType", Map.of("type", "string"));
        properties.put("size", sizeSchema);
        properties.put("digest", digest);
        properties.put("data", Map.of("type", "string"));
        schema.put("properties", properties);
        schema.put("required", List.of("id", "mimeType", "size"));
        return schema;
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
