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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.ikanos.spec.exposes.mcp.McpResourceUiCspSpec;
import io.ikanos.spec.exposes.mcp.McpResourceUiPermissionsSpec;
import io.ikanos.spec.exposes.mcp.McpResourceUiSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolUiSpec;

/**
 * Server-side support for the MCP Apps extension (SEP-1865, {@code io.modelcontextprotocol/ui}).
 *
 * <p>Builds the {@code _meta.ui} objects emitted on tools and resources, and validates at load
 * time that every tool's {@code ui.resourceUri} resolves to a declared static {@code ui://} view
 * served as {@code text/html;profile=mcp-app}. The iframe protocol ({@code ui/initialize},
 * {@code ui/notifications/*}, ...) runs between host and view and is not implemented here.</p>
 */
public final class McpAppsMetadata {

    /** Extension identifier advertised in {@code server/discover}. */
    public static final String EXTENSION_ID = "io.modelcontextprotocol/ui";

    /** The only MIME type hosts accept for an MCP Apps view. */
    public static final String VIEW_MIME_TYPE = "text/html;profile=mcp-app";

    /** URI scheme of an MCP Apps view. */
    public static final String UI_SCHEME = "ui://";

    private McpAppsMetadata() {}

    /**
     * Build the tool-level {@code _meta} map ({@code {"ui": {...}}}) from a tool's {@code ui}
     * block, or {@code null} when the tool declares none.
     */
    public static Map<String, Object> toolMeta(McpToolUiSpec ui) {
        if (ui == null || ui.getResourceUri() == null) {
            return null;
        }
        Map<String, Object> uiNode = new LinkedHashMap<>();
        uiNode.put("resourceUri", ui.getResourceUri());
        if (!ui.getVisibility().isEmpty()) {
            uiNode.put("visibility", List.copyOf(ui.getVisibility()));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ui", uiNode);
        return meta;
    }

    /**
     * Build the resource-level {@code _meta} map ({@code {"ui": {...}}}) from a resource's
     * {@code ui} block, or {@code null} when the block is absent or empty. Permissions authored as
     * {@code true} are emitted as empty objects (the SEP's presence-flag shape).
     */
    public static Map<String, Object> resourceMeta(McpResourceUiSpec ui) {
        if (ui == null) {
            return null;
        }
        Map<String, Object> uiNode = new LinkedHashMap<>();

        Map<String, Object> csp = cspNode(ui.getCsp());
        if (csp != null) {
            uiNode.put("csp", csp);
        }
        Map<String, Object> permissions = permissionsNode(ui.getPermissions());
        if (permissions != null) {
            uiNode.put("permissions", permissions);
        }
        if (ui.getDomain() != null) {
            uiNode.put("domain", ui.getDomain());
        }
        if (ui.getPrefersBorder() != null) {
            uiNode.put("prefersBorder", ui.getPrefersBorder());
        }

        if (uiNode.isEmpty()) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ui", uiNode);
        return meta;
    }

    static Map<String, Object> cspNode(McpResourceUiCspSpec csp) {
        if (csp == null) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        putIfNotNull(node, "connectDomains", csp.getConnectDomains());
        putIfNotNull(node, "resourceDomains", csp.getResourceDomains());
        putIfNotNull(node, "frameDomains", csp.getFrameDomains());
        putIfNotNull(node, "baseUriDomains", csp.getBaseUriDomains());
        return node.isEmpty() ? null : node;
    }

    static Map<String, Object> permissionsNode(McpResourceUiPermissionsSpec permissions) {
        if (permissions == null) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        putFlag(node, "camera", permissions.getCamera());
        putFlag(node, "microphone", permissions.getMicrophone());
        putFlag(node, "geolocation", permissions.getGeolocation());
        putFlag(node, "clipboardWrite", permissions.getClipboardWrite());
        return node.isEmpty() ? null : node;
    }

    private static void putIfNotNull(Map<String, Object> node, String key, List<String> value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private static void putFlag(Map<String, Object> node, String key, Boolean requested) {
        if (Boolean.TRUE.equals(requested)) {
            node.put(key, new LinkedHashMap<String, Object>());
        }
    }

    /** Whether at least one tool declares a {@code ui} block. */
    public static boolean anyToolDeclaresUi(Collection<McpServerToolSpec> tools) {
        if (tools == null) {
            return false;
        }
        for (McpServerToolSpec tool : tools) {
            if (tool != null && tool.getUi() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code mimeType} is {@code text/html;profile=mcp-app}, comparing type, subtype and the
     * {@code profile} parameter case-insensitively and tolerating whitespace and a quoted value.
     */
    public static boolean isViewMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String[] parts = mimeType.split(";");
        if (!"text/html".equals(parts[0].trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i].trim();
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = param.substring(0, eq).trim();
            String value = param.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if ("profile".equalsIgnoreCase(name) && "mcp-app".equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a URI uses the {@code ui://} scheme. */
    public static boolean isUiUri(String uri) {
        return uri != null && uri.startsWith(UI_SCHEME);
    }

    /**
     * Validate the MCP Apps wiring of one MCP server at load time.
     *
     * <ol>
     * <li>A resource whose {@code uri} starts with {@code ui://} must be static ({@code location}).
     * Upstream-fetched views are out of scope for v1.</li>
     * <li>Every tool {@code ui.resourceUri} must match a URI listed by {@code resources/list} (for
     * static directories, the expanded file URI).</li>
     * <li>That URI must start with {@code ui://}.</li>
     * <li>Its effective MIME type must be {@code text/html;profile=mcp-app}.</li>
     * </ol>
     *
     * @throws IllegalStateException naming the offending tool or resource
     */
    public static void validate(Collection<McpServerToolSpec> tools,
            Collection<McpServerResourceSpec> resources, ResourceHandler resourceHandler) {
        if (resources != null) {
            for (McpServerResourceSpec resource : resources) {
                if (resource != null && isUiUri(resource.getUri()) && !resource.isStatic()) {
                    throw new IllegalStateException("MCP resource '" + resource.getName()
                            + "' uses a ui:// URI but is not static. MCP Apps views must be "
                            + "served from 'location'; call/steps-backed views are not supported.");
                }
            }
        }

        if (!anyToolDeclaresUi(tools)) {
            return;
        }

        Map<String, ResourceHandler.ResourceDescriptor> byUri = new LinkedHashMap<>();
        for (ResourceHandler.ResourceDescriptor descriptor : resourceHandler.listAll()) {
            byUri.put(descriptor.uri(), descriptor);
        }

        for (McpServerToolSpec tool : tools) {
            if (tool == null || tool.getUi() == null) {
                continue;
            }
            String resourceUri = tool.getUi().getResourceUri();
            if (resourceUri == null || resourceUri.isBlank()) {
                throw new IllegalStateException(
                        "MCP tool '" + tool.getName() + "' declares 'ui' without 'resourceUri'.");
            }
            if (!isUiUri(resourceUri)) {
                throw new IllegalStateException("MCP tool '" + tool.getName()
                        + "' ui.resourceUri '" + resourceUri + "' must start with ui://.");
            }
            ResourceHandler.ResourceDescriptor descriptor = byUri.get(resourceUri);
            if (descriptor == null) {
                throw new IllegalStateException("MCP tool '" + tool.getName()
                        + "' ui.resourceUri '" + resourceUri
                        + "' does not match any declared resource. Declared resource URIs: "
                        + byUri.keySet());
            }
            if (!isViewMimeType(descriptor.mimeType())) {
                throw new IllegalStateException("MCP tool '" + tool.getName()
                        + "' ui.resourceUri '" + resourceUri + "' resolves to MIME type '"
                        + descriptor.mimeType() + "'; MCP Apps views must declare mimeType '"
                        + VIEW_MIME_TYPE + "'.");
            }
        }
    }
}
