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

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.service.MetadataService;
import io.ikanos.engine.util.BinarySize;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpFileDownloadsSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolDownloadSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The download side of the MCP files extension for one adapter that declares {@code files}.
 *
 * <p>Two sources of download targets, both only for a {@code tools/call} that declared
 * {@code download} ({@link McpFiles#downloadsAccepted()}):</p>
 * <ul>
 *   <li><b>Hosted</b>: a binary result larger than {@code inlineThreshold} is kept in a
 *   {@link DownloadStore} and listed as a {@code path} target under the MCP address, with
 *   {@code credentials: include}. The link serves the file once, to the principal that called the
 *   tool, until {@code ttl} has passed. HTTP transport only: stdio has no address to serve it
 *   from.</li>
 *   <li><b>Pass-through</b>: a tool that declares {@code download} names the properties of its
 *   mapped output that describe a file its consumed API already serves; the URL is listed as a
 *   {@code url} target with {@code credentials: omit}.</li>
 * </ul>
 * <p>Anything that does not fit, such as a full store or an output without a URL, keeps the
 * result the tool returns without the extension.</p>
 */
public final class McpFileDownloads {

    /** The path segment, under the MCP address, of hosted download links. */
    public static final String PATH_SEGMENT = "files";

    private static final Pattern LINK = Pattern.compile(".*/" + PATH_SEGMENT
            + "/([A-Za-z0-9_-]{43})$");
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final MetadataService METADATA = new MetadataService();

    private final long inlineThreshold;
    private final Duration ttl;
    private final boolean hosted;
    private final DownloadStore store;
    private final Clock clock;

    McpFileDownloads(long inlineThreshold, Duration ttl, boolean hosted, DownloadStore store,
            Clock clock) {
        this.inlineThreshold = inlineThreshold;
        this.ttl = ttl;
        this.hosted = hosted;
        this.store = store;
        this.clock = clock;
    }

    /**
     * The downloads of an adapter, or {@code null} when it does not declare {@code files}.
     *
     * @throws IllegalArgumentException when a setting is malformed, or a tool declares
     *         {@code download} on an adapter without {@code files}
     */
    public static McpFileDownloads of(McpServerSpec spec, Clock clock) {
        if (spec.getFiles() == null) {
            for (McpServerToolSpec tool : spec.getTools().values()) {
                if (tool != null && tool.getDownload() != null) {
                    throw new IllegalArgumentException("MCP tool '" + tool.getName()
                            + "' of namespace '" + spec.getNamespace() + "' declares download,"
                            + " but the exposes block declares no files");
                }
            }
            return null;
        }
        McpFileDownloadsSpec downloads = spec.getFiles().downloadsOrDefault();
        long threshold = BinarySize.parse(downloads.inlineThresholdOrDefault());
        Duration ttl;
        try {
            ttl = Duration.parse(downloads.ttlOrDefault());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("files.downloads.ttl of MCP namespace '"
                    + spec.getNamespace() + "' is not an ISO 8601 duration: "
                    + downloads.ttlOrDefault(), e);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("files.downloads.ttl of MCP namespace '"
                    + spec.getNamespace() + "' must be positive: " + downloads.ttlOrDefault());
        }
        boolean hosted = !spec.isStdio() && resendsCredential(spec.getAuthentication());
        if (!spec.isStdio() && !hosted) {
            Context.getCurrentLogger().log(Level.WARNING, "MCP namespace '"
                    + spec.getNamespace() + "' declares files, but a client cannot send its "
                    + spec.getAuthentication().getType() + " credential to a download link:"
                    + " binary results stay inline; presigned URLs still become downloads");
        }
        return new McpFileDownloads(threshold, ttl, hosted, MemoryDownloadStore.shared(), clock);
    }

    /**
     * Whether a client that follows the draft can authenticate a hosted download link. With
     * {@code credentials: include} it resends only the {@code Authorization} header of its MCP
     * requests, and keeps the query string of the MCP address: so no authentication,
     * {@code bearer}, {@code basic}, {@code oauth2} and an {@code apikey} in the query work, while
     * an {@code apikey} in another header, {@code digest} (bound to one request) and
     * {@code awsSigV4} (a signature per request) do not.
     */
    static boolean resendsCredential(AuthenticationSpec authentication) {
        if (authentication == null) {
            return true;
        }
        return switch (authentication.getType()) {
            case "bearer", "basic", "oauth2" -> true;
            case "apikey" -> authentication instanceof ApiKeyAuthenticationSpec apiKey
                    && apiKey.isQueryPlacement();
            default -> false;
        };
    }

    /** @return whether binary results can become hosted download targets on this adapter */
    public boolean hostsDownloads() {
        return hosted;
    }

    /** @return the size above which a binary result becomes a download target */
    public long inlineThreshold() {
        return inlineThreshold;
    }

    /**
     * A binary tool result as a hosted download target, or {@code null} to return it inline: the
     * request did not declare {@code download}, the transport is stdio, the adapter's
     * authentication cannot be resent to a link ({@link #resendsCredential}), the result is not
     * larger than {@code inlineThreshold}, or the store has no room for it.
     *
     * @param principal the caller of the tool, the only one the link serves
     */
    public McpSchema.CallToolResult hosted(String toolName, byte[] bytes, String mediaType,
            String principal) {
        if (!hosted || !McpFiles.downloadsAccepted() || bytes.length <= inlineThreshold) {
            return null;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        String name = fileName(toolName, mediaType);
        String token = newToken();
        if (!store.put(token, new DownloadStore.StoredDownload(principal, name, mediaType, bytes,
                expiresAt), now)) {
            Context.getCurrentLogger().log(Level.WARNING, "Download store full: returning the "
                    + bytes.length + "-byte result of tool '" + toolName + "' inline");
            return null;
        }
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("name", name);
        target.put("mimeType", mediaType);
        target.put("size", bytes.length);
        target.put("digest", Map.of("algorithm", "sha-256", "value", sha256(bytes)));
        target.put("path", "/" + PATH_SEGMENT + "/" + token);
        target.put("credentials", "include");
        target.put("expiresAt", expiresAt.toString());
        return downloadResult(target);
    }

    /**
     * A tool's mapped output as a pass-through download target, or {@code null} to return it as
     * it is: the request did not declare {@code download}, or the output does not carry an
     * {@code https} URL and a size where the tool's {@code download} says.
     */
    public McpSchema.CallToolResult passThrough(McpServerToolSpec tool, String mapped) {
        McpToolDownloadSpec download = tool.getDownload();
        if (download == null || mapped == null || !McpFiles.downloadsAccepted()) {
            return null;
        }
        JsonNode output;
        try {
            output = JSON.readTree(mapped);
        } catch (JacksonException e) {
            return unusable(tool, "it is not JSON");
        }
        if (output == null || !output.isObject()) {
            return unusable(tool, "it is not a JSON object");
        }
        String url = text(output, download.getUrl());
        if (url == null || !isHttps(url)) {
            return unusable(tool, "property '" + download.getUrl()
                    + "' is not an absolute https URL");
        }
        Long bytes = sizeOf(output.get(download.getSize()));
        if (bytes == null) {
            return unusable(tool, "property '" + download.getSize()
                    + "' is not a size in bytes");
        }
        String mimeType = text(output, download.getMimeType());
        String name = text(output, download.getName());
        String expiresAt = timestamp(tool, text(output, download.getExpiresAt()));

        Map<String, Object> target = new LinkedHashMap<>();
        if (name != null) {
            target.put("name", name);
        }
        target.put("mimeType", mimeType != null ? mimeType : "application/octet-stream");
        target.put("size", bytes);
        target.put("url", url);
        target.put("credentials", "omit");
        if (expiresAt != null) {
            target.put("expiresAt", expiresAt);
        }
        return downloadResult(target);
    }

    /**
     * The token of a hosted download link, or {@code null} when {@code path} is not one. The link
     * is {@code <MCP address>/files/<token>}, whatever the path of the MCP address.
     */
    public static String tokenOf(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = LINK.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Takes the file of a hosted link: once, by the principal it was issued to, before it
     * expires.
     */
    public Optional<DownloadStore.StoredDownload> take(String token, String principal) {
        return store.take(token, principal, clock.instant());
    }

    /** A result listing one target, described in {@code content}, with nothing else. */
    static McpSchema.CallToolResult downloadResult(Map<String, Object> target) {
        return new McpSchema.CallToolResult(List.of(McpSchema.TextContent.builder(summary(target))
                .build()), false, null, McpFiles.resultMeta(List.of(target)));
    }

    /** The text the model reads in place of the file. */
    static String summary(Map<String, Object> target) {
        Object name = target.get("name");
        return "The file" + (name != null ? " " + name : "") + " (" + target.get("mimeType")
                + ", " + target.get("size") + " bytes) is ready to download; its content is not"
                + " part of this result.";
    }

    /** The name of a hosted file: the tool name, with the media type's usual extension. */
    static String fileName(String toolName, String mediaType) {
        String extension = null;
        try {
            extension = METADATA.getExtension(MediaType.valueOf(mediaType));
        } catch (IllegalArgumentException e) {
            // an unparsable media type gets no extension
        }
        return extension != null ? toolName + "." + extension : toolName;
    }

    private static McpSchema.CallToolResult unusable(McpServerToolSpec tool, String reason) {
        Context.getCurrentLogger().log(Level.WARNING, "MCP tool '" + tool.getName()
                + "' declares download, but its output does not describe a file (" + reason
                + "): returning it as it is");
        return null;
    }

    private static String text(JsonNode output, String property) {
        if (property == null) {
            return null;
        }
        JsonNode value = output.get(property);
        return value != null && value.isString() && !value.asString().isBlank()
                ? value.asString() : null;
    }

    /**
     * An ISO 8601 timestamp normalized to UTC, as the draft types {@code expiresAt}, or
     * {@code null} when {@code value} is absent or not a timestamp: {@code expiresAt} is optional
     * and advisory, and a value a client cannot parse would make it reject the whole target.
     */
    static String timestamp(McpServerToolSpec tool, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toInstant()
                    .toString();
        } catch (DateTimeParseException e) {
            Context.getCurrentLogger().log(Level.WARNING, "MCP tool '" + tool.getName()
                    + "' gives an expiry that is not an ISO 8601 timestamp (" + value
                    + "): leaving expiresAt out of its download target");
            return null;
        }
    }

    /**
     * A size in bytes, written as a JSON integer or as a string of digits, or {@code null} when
     * {@code size} is neither or is negative.
     */
    static Long sizeOf(JsonNode size) {
        long value;
        if (size == null) {
            return null;
        } else if (size.isIntegralNumber() && size.canConvertToLong()) {
            value = size.asLong();
        } else if (size.isString()) {
            try {
                value = Long.parseLong(size.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return value >= 0 ? value : null;
    }

    static boolean isHttps(String url) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static String newToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static String sha256(byte[] bytes) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
