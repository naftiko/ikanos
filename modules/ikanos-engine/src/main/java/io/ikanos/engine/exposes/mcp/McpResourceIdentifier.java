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
import java.util.ArrayList;
import java.util.List;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

/**
 * Checks that an MCP adapter's {@code authentication.resource} can be the audience its clients'
 * tokens are bound to.
 *
 * <p>A client that follows MCP authorization, and MCP enterprise-managed authorization in
 * particular (Claude's auto-connect), asks for a token bound to the Protected Resource Metadata
 * {@code resource} only when it is the MCP endpoint's URL or a parent of it, and to the endpoint's
 * URL otherwise. The adapter checks the token's audience against {@code resource} (unless an
 * explicit {@code audience} is set), so a {@code resource} that is neither refuses every such
 * token with {@code Invalid audience}.</p>
 *
 * <p>Only the paths are compared: the public host is unknown to the adapter, behind a gateway in
 * particular. An adapter without {@code path} answers on any path, so it is not checked.</p>
 */
public final class McpResourceIdentifier {

    private McpResourceIdentifier() {}

    /**
     * The warning for an MCP adapter whose {@code resource} path is neither its endpoint's path
     * nor a parent of it, or {@code null} when there is nothing to warn about.
     */
    public static String warning(McpServerSpec adapter) {
        if (adapter == null || adapter.isStdio() || adapter.getPath() == null
                || !(adapter.getAuthentication() instanceof OAuth2AuthenticationSpec oauth2)
                || oauth2.getResource() == null || oauth2.getAudience() != null) {
            return null;
        }
        String resourcePath;
        try {
            resourcePath = URI.create(oauth2.getResource()).getRawPath();
        } catch (IllegalArgumentException e) {
            return null; // The schema refuses it.
        }
        if (isSameOrParent(resourcePath, adapter.getPath())) {
            return null;
        }
        return "MCP adapter '" + adapter.getNamespace() + "': authentication.resource '"
                + oauth2.getResource() + "' is neither the endpoint path '" + adapter.getPath()
                + "' nor a parent of it. A client that follows MCP authorization, such as Claude"
                + " with enterprise-managed authorization, then binds its tokens to the endpoint"
                + " URL instead, and this adapter refuses them as 'Invalid audience'. Set"
                + " resource to the endpoint's public URL or a parent of it, unless a gateway"
                + " serves the endpoint under that path.";
    }

    /** The warnings of every MCP adapter of a document, for {@code ikanos validate}. */
    public static List<String> findWarnings(IkanosSpec spec) {
        List<String> warnings = new ArrayList<>();
        for (ServerSpec adapter : AccessControl.exposesOf(spec)) {
            if (adapter instanceof McpServerSpec mcp) {
                String warning = warning(mcp);
                if (warning != null) {
                    warnings.add(warning);
                }
            }
        }
        return warnings;
    }

    /**
     * Whether {@code parent} is {@code path} or one of its ancestors, segment by segment: an
     * empty path and {@code /} are the root, and a trailing {@code /} is ignored, so
     * {@code /mcp} is a parent of {@code /mcp/v2} but not of {@code /mcpx}.
     */
    static boolean isSameOrParent(String parent, String path) {
        String ancestor = trim(parent);
        String endpoint = trim(path);
        return ancestor.isEmpty() || endpoint.equals(ancestor)
                || endpoint.startsWith(ancestor + "/");
    }

    /** The path without its trailing {@code /}, the root as an empty string. */
    private static String trim(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.startsWith("/") ? path : "/" + path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
