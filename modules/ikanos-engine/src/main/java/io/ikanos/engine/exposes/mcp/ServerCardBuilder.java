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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.restlet.Context;
import io.ikanos.spec.IconSpec;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpServerCardSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the MCP Server Card (SEP-2127) of an HTTP MCP server from the capability's
 * {@code info} and the {@code mcp} exposes entry, with the {@code card} block's overrides. Every
 * field is resolved and checked once, at load, into a {@link Template}: a card that would break the
 * v1 schema fails the load instead of being served, so nothing is ever truncated or guessed
 * silently.
 *
 * <p>The remote URL is {@code card.url} when set. Otherwise it is the origin the client fetched
 * the card from, followed by the adapter's {@code path} ({@link Template#render}), so neither the
 * bind address nor the machine's host name is ever published: a card must not reveal internal
 * network topology.</p>
 *
 * <p>The card describes the shape of the headers the server's {@code authentication} expects,
 * never a bound value.</p>
 */
public final class ServerCardBuilder {

    /** The extension identifier of MCP Server Cards. */
    public static final String EXTENSION_ID = "io.modelcontextprotocol/server-card";

    /** The v1 schema every card declares as {@code $schema}. */
    public static final String SCHEMA_URL =
            "https://static.modelcontextprotocol.io/schemas/v1/server-card.schema.json";

    /** The media type of a card. */
    public static final String MEDIA_TYPE = "application/mcp-server-card+json";

    /** The last path segment of a card, after the MCP endpoint path. */
    public static final String PATH_SUFFIX = "/server-card";

    static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9.-]+/[a-zA-Z0-9._-]+$");
    static final int NAME_MIN = 3;
    static final int NAME_MAX = 200;
    static final int VERSION_MAX = 255;
    static final int DESCRIPTION_MAX = 100;
    static final int TITLE_MAX = 100;

    /**
     * A version range, which the spec rejects: an operator prefix ({@code ^ ~ > < =}), an
     * alternative ({@code ||}), a hyphen range ({@code 1.0 - 2.0}) or a wildcard part
     * ({@code 1.x}, {@code *}).
     */
    static final Pattern VERSION_RANGE =
            Pattern.compile("^\\s*[\\^~><=]|\\|\\||\\s-\\s|(^|\\.)[xX*](\\.|$)");

    static final Map<String, String> REPOSITORY_SOURCES = Map.of("github.com", "github",
            "gitlab.com", "gitlab", "bitbucket.org", "bitbucket");

    private static final ObjectMapper JSON = IkanosMappers.json();

    private ServerCardBuilder() {}

    /** A rendered card: its JSON text and its strong entity tag. */
    public record ServerCard(String json, String etag) {}

    /**
     * The version both the card and {@code serverInfo.version} publish: {@code card.version},
     * else {@code info.version}, else {@code null}.
     */
    public static String resolveVersion(McpServerSpec server, InfoSpec info) {
        McpServerCardSpec card = server.getCard();
        if (card != null && card.getVersion() != null) {
            return card.getVersion();
        }
        return info != null ? info.getVersion() : null;
    }

    /**
     * Resolve and check the card of {@code server}, logging warnings to the current Restlet
     * logger.
     *
     * @throws IllegalArgumentException when the server declares no card can be built from
     */
    public static Template build(McpServerSpec server, InfoSpec info,
            List<String> protocolVersions) {
        return build(server, info, protocolVersions,
                message -> Context.getCurrentLogger().warning(message));
    }

    /** Resolve and check the card, with the warnings sent to {@code warnings}. */
    static Template build(McpServerSpec server, InfoSpec info, List<String> protocolVersions,
            Consumer<String> warnings) {
        McpServerCardSpec card = server.getCard();
        if (card == null) {
            throw new IllegalArgumentException(where(server) + " declares no 'card'");
        }
        if (server.isStdio()) {
            throw new IllegalArgumentException(where(server) + " declares 'card' with transport"
                    + " stdio; server cards are served over HTTP only. Remove 'card'.");
        }
        InfoSpec infoSpec = info != null ? info : new InfoSpec();

        ObjectNode json = JSON.createObjectNode();
        json.put("$schema", SCHEMA_URL);
        json.put("name", resolveName(server, card, infoSpec));
        json.put("version", checkVersion(server, resolveVersion(server, infoSpec)));
        String title = firstNonNull(card.getTitle(), infoSpec.getDisplay());
        if (title != null) {
            json.put("title", checkTitle(server, card, title));
        }
        json.put("description", resolveDescription(server, card));

        String websiteUrl = firstNonNull(card.getWebsiteUrl(), infoSpec.getWebsiteUrl());
        if (websiteUrl != null) {
            json.put("websiteUrl", checkHttpUrl(server, "websiteUrl", websiteUrl));
        }
        String repositoryUrl = firstNonNull(card.getRepositoryUrl(), infoSpec.getRepositoryUrl());
        if (repositoryUrl != null) {
            putRepository(json, server, repositoryUrl, warnings);
        }
        List<IconSpec> icons = card.getIcons() != null ? card.getIcons() : infoSpec.getIcons();
        if (icons != null && !icons.isEmpty()) {
            putIcons(json.putArray("icons"), icons);
        }

        if (card.requiresAuthentication() && !isRestricted(server)) {
            warnings.accept(where(server) + " sets card.authenticated: true without"
                    + " 'authentication' or 'trust: forwarded'; the card stays public.");
        }

        String fixedUrl = card.getUrl() != null ? checkHttpUrl(server, "url", card.getUrl())
                : null;
        String path = server.getPath() == null || "/".equals(server.getPath()) ? ""
                : server.getPath();
        return new Template(json, fixedUrl, path, server.getAuthentication(),
                List.copyOf(protocolVersions));
    }

    /**
     * A resolved and checked card, short of its remote URL. A card with {@code card.url} is
     * rendered once; any other is rendered for the origin of each request.
     */
    public static final class Template {

        private final ObjectNode base;
        private final String path;
        private final AuthenticationSpec authentication;
        private final List<String> protocolVersions;
        private final ServerCard fixed;

        Template(ObjectNode base, String fixedUrl, String path,
                AuthenticationSpec authentication, List<String> protocolVersions) {
            this.base = base;
            this.path = path;
            this.authentication = authentication;
            this.protocolVersions = protocolVersions;
            this.fixed = fixedUrl != null ? renderUrl(fixedUrl) : null;
        }

        /** Whether the remote URL is {@code card.url}, the same for every request. */
        public boolean isFixed() {
            return fixed != null;
        }

        /**
         * The card for a client that fetched it from {@code origin}: {@code card.url} when set,
         * else {@code <origin><path>}.
         *
         * @param origin {@code <scheme>://<host>[:<port>]}, as the client addressed the server;
         *        ignored, and may be {@code null}, when the template {@link #isFixed()}
         */
        public ServerCard render(String origin) {
            return fixed != null ? fixed : renderUrl(origin + path);
        }

        private ServerCard renderUrl(String url) {
            ObjectNode json = base.deepCopy();
            ObjectNode remote = json.putArray("remotes").addObject();
            remote.put("type", "streamable-http");
            putAuthentication(remote, url, authentication);
            ArrayNode versions = remote.putArray("supportedProtocolVersions");
            protocolVersions.forEach(versions::add);
            String text = JSON.writeValueAsString(json);
            return new ServerCard(text, etag(text));
        }
    }

    static String resolveName(McpServerSpec server, McpServerCardSpec card, InfoSpec info) {
        String name;
        if (card.getName() != null) {
            name = card.getName();
        } else if (info.getPublisher() != null) {
            name = info.getPublisher() + "/" + server.getNamespace();
        } else {
            throw new IllegalArgumentException(where(server) + " has no card name: set"
                    + " info.publisher (such as 'io.example') to name it '<publisher>/"
                    + server.getNamespace() + "', or set card.name.");
        }
        if (name.length() < NAME_MIN || name.length() > NAME_MAX
                || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(where(server) + " card name '" + name
                    + "' must be reverse-DNS with exactly one slash, such as"
                    + " 'io.example/tools', " + NAME_MIN + " to " + NAME_MAX + " characters.");
        }
        return name;
    }

    static String checkVersion(McpServerSpec server, String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(where(server) + " has no card version: set"
                    + " info.version or card.version.");
        }
        if (version.length() > VERSION_MAX) {
            throw new IllegalArgumentException(where(server) + " card version is longer than "
                    + VERSION_MAX + " characters.");
        }
        if (VERSION_RANGE.matcher(version).find()) {
            throw new IllegalArgumentException(where(server) + " card version '" + version
                    + "' is a range; a server card needs one exact version, such as '1.2.3'.");
        }
        return version;
    }

    /**
     * Whether the card is served only to the callers the MCP endpoint admits: it sets
     * {@code authenticated: true} and the adapter declares {@code authentication}, or trusts a
     * gateway ({@code trust: forwarded}), whose trusted peer check then guards the card too.
     */
    static boolean isRestricted(McpServerSpec server) {
        return server.getCard() != null && server.getCard().requiresAuthentication()
                && (server.getAuthentication() != null || (server.getTrust() != null
                        && McpTrustSpec.MODE_FORWARDED.equals(server.getTrust().getMode())));
    }

    static String checkTitle(McpServerSpec server, McpServerCardSpec card, String title) {
        if (title.isBlank() || title.length() > TITLE_MAX) {
            throw new IllegalArgumentException(where(server) + " card title must be 1 to "
                    + TITLE_MAX + " characters"
                    + (card.getTitle() == null ? "; info.display is " + title.length()
                            + " characters long, so set a shorter card.title." : "."));
        }
        return title;
    }

    static String resolveDescription(McpServerSpec server, McpServerCardSpec card) {
        String description = firstNonNull(card.getDescription(), server.getDescription());
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(where(server) + " has no card description: set"
                    + " the exposes entry's 'description' or card.description.");
        }
        description = description.strip();
        if (description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException(where(server) + " card description is "
                    + description.length() + " characters long, over the " + DESCRIPTION_MAX
                    + " a server card allows; set a shorter card.description.");
        }
        return description;
    }

    static void putRepository(ObjectNode json, McpServerSpec server, String url,
            Consumer<String> warnings) {
        String host = URI.create(checkHttpUrl(server, "repositoryUrl", url)).getHost();
        String source = host != null
                ? REPOSITORY_SOURCES.get(host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""))
                : null;
        if (source == null) {
            warnings.accept(where(server) + " repository '" + url + "' is not on github.com,"
                    + " gitlab.com or bitbucket.org; the card omits 'repository'.");
            return;
        }
        ObjectNode repository = json.putObject("repository");
        repository.put("url", url);
        repository.put("source", source);
    }

    static void putIcons(ArrayNode array, List<IconSpec> icons) {
        for (IconSpec icon : icons) {
            ObjectNode node = array.addObject();
            node.put("src", icon.getSrc());
            if (icon.getMimeType() != null) {
                node.put("mimeType", icon.getMimeType());
            }
            if (!icon.getSizes().isEmpty()) {
                ArrayNode sizes = node.putArray("sizes");
                icon.getSizes().forEach(sizes::add);
            }
            if (icon.getTheme() != null) {
                node.put("theme", icon.getTheme());
            }
        }
    }

    /**
     * Describe what the client must send for {@code authentication}, never the bound value:
     * a header for bearer, basic, digest and a header API key; a URL variable for a query API
     * key; nothing for oauth2, which clients discover through the RFC 9728 metadata, nor for an
     * open server.
     */
    static void putAuthentication(ObjectNode remote, String url,
            AuthenticationSpec authentication) {
        String type = authentication != null ? authentication.getType() : null;
        if (type == null || "oauth2".equals(type)) {
            remote.put("url", url);
            return;
        }
        if ("apikey".equals(type) && authentication instanceof ApiKeyAuthenticationSpec apiKey
                && apiKey.isQueryPlacement()) {
            remote.put("url", url + (url.contains("?") ? "&" : "?") + apiKey.getKey() + "={key}");
            ObjectNode key = remote.putObject("variables").putObject("key");
            key.put("description", "API key for this server.");
            key.put("isRequired", true);
            key.put("isSecret", true);
            return;
        }
        remote.put("url", url);
        ObjectNode header = remote.putArray("headers").addObject();
        switch (type) {
            case "bearer" -> {
                header.put("name", "Authorization");
                header.put("description", "Bearer token for this server.");
                putSecret(header);
                header.put("value", "Bearer {token}");
                putSecret(header.putObject("variables").putObject("token"));
            }
            case "apikey" -> {
                header.put("name", ((ApiKeyAuthenticationSpec) authentication).getKey());
                header.put("description", "API key for this server.");
                putSecret(header);
                header.put("value", "{key}");
                putSecret(header.putObject("variables").putObject("key"));
            }
            case "basic", "digest" -> {
                header.put("name", "Authorization");
                header.put("description", "HTTP " + ("basic".equals(type) ? "Basic" : "Digest")
                        + " credentials for this server.");
                putSecret(header);
            }
            default -> throw new IllegalArgumentException("Authentication type '" + type
                    + "' cannot be described on a server card.");
        }
    }

    private static void putSecret(ObjectNode node) {
        node.put("isRequired", true);
        node.put("isSecret", true);
    }

    static String checkHttpUrl(McpServerSpec server, String field, String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return url;
            }
        } catch (IllegalArgumentException e) {
            // reported below
        }
        throw new IllegalArgumentException(where(server) + " card " + field + " '" + url
                + "' is not an absolute http or https URL.");
    }

    /** A strong entity tag: the base64url SHA-256 of the card's bytes. */
    static String etag(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String where(McpServerSpec server) {
        return "The mcp adapter '" + server.getNamespace() + "'";
    }
}
