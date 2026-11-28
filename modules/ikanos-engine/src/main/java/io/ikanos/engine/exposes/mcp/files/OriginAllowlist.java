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
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

/**
 * The origins that the {@code credentials: omit} targets of an MCP adapter may point to
 * ({@code files.allowedOrigins}, experimental): the presigned URLs of a flow's {@code upload} and
 * the URLs of a tool's {@code download}. The client sends such a target no credential, and its
 * host approves the tool call before it sees the target, so a target outside the list is refused.
 *
 * <p>An origin is compared as {@code scheme://host:port}, scheme and host in lower case, with the
 * default port of the scheme made explicit.</p>
 */
public final class OriginAllowlist {

    private final Set<String> origins;

    OriginAllowlist(Set<String> origins) {
        this.origins = Set.copyOf(origins);
    }

    /**
     * The allowlist of an adapter, or {@code null} when it declares none: any target is then
     * allowed.
     *
     * @throws IllegalArgumentException when an entry is not an {@code http} or {@code https}
     *         origin
     */
    public static OriginAllowlist of(McpServerSpec spec) {
        if (spec.getFiles() == null || spec.getFiles().getAllowedOrigins().isEmpty()) {
            return null;
        }
        List<String> declared = spec.getFiles().getAllowedOrigins();
        Set<String> origins = new LinkedHashSet<>();
        for (String entry : declared) {
            String origin = entry != null ? originOf(entry.endsWith("/")
                    ? entry.substring(0, entry.length() - 1) : entry, true) : null;
            if (origin == null) {
                throw new IllegalArgumentException("files.allowedOrigins of MCP namespace '"
                        + spec.getNamespace() + "' lists '" + entry
                        + "', which is not an origin such as https://storage.example.com");
            }
            origins.add(origin);
        }
        return new OriginAllowlist(origins);
    }

    /** Whether {@code url} is an absolute URL on one of the allowed origins. */
    public boolean allows(String url) {
        String origin = url != null ? originOf(url, false) : null;
        return origin != null && origins.contains(origin);
    }

    /**
     * The origin of an absolute {@code http} or {@code https} URL, or {@code null} when it is
     * not one or carries user info.
     *
     * @param bare whether the URL must be a bare origin, with no path, query or fragment
     */
    static String originOf(String url, boolean bare) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return null;
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            return null;
        }
        if (bare && ((uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null || uri.getRawFragment() != null)) {
            return null;
        }
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
