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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.spec.exposes.mcp.McpResourceUiCspSpec;
import io.ikanos.spec.exposes.mcp.McpResourceUiPermissionsSpec;
import io.ikanos.spec.exposes.mcp.McpResourceUiSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolUiSpec;

/**
 * Unit tests for {@link McpAppsMetadata}.
 */
class McpAppsMetadataTest {

    private static final String VIEW_URI = "ui://weather/forecast/index.html";

    @TempDir
    Path tempDir;

    // ── toolMeta ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void toolMetaShouldReturnNullWhenUiIsAbsent() {
        assertNull(McpAppsMetadata.toolMeta(null));
    }

    @Test
    void toolMetaShouldEmitResourceUriOnlyWhenVisibilityIsNotAuthored() {
        Map<String, Object> meta = McpAppsMetadata.toolMeta(new McpToolUiSpec(VIEW_URI));

        assertEquals(Map.of("ui", Map.of("resourceUri", VIEW_URI)), meta);
    }

    @Test
    void toolMetaShouldEmitVisibilityWhenAuthored() {
        McpToolUiSpec ui = new McpToolUiSpec(VIEW_URI);
        ui.setVisibility(List.of("app"));

        Map<String, Object> meta = McpAppsMetadata.toolMeta(ui);

        assertEquals(Map.of("ui", Map.of("resourceUri", VIEW_URI, "visibility", List.of("app"))),
                meta);
    }

    // ── resourceMeta ─────────────────────────────────────────────────────────────────────────────

    @Test
    void resourceMetaShouldReturnNullWhenUiIsEmpty() {
        assertNull(McpAppsMetadata.resourceMeta(null));
        assertNull(McpAppsMetadata.resourceMeta(new McpResourceUiSpec()));
    }

    @Test
    void resourceMetaShouldEmitRequestedPermissionsAsEmptyObjectsAndSkipFalseOnes() {
        McpResourceUiPermissionsSpec permissions = new McpResourceUiPermissionsSpec();
        permissions.setClipboardWrite(true);
        permissions.setCamera(false);
        McpResourceUiSpec ui = new McpResourceUiSpec();
        ui.setPermissions(permissions);

        Map<String, Object> meta = McpAppsMetadata.resourceMeta(ui);

        assertEquals(Map.of("ui", Map.of("permissions", Map.of("clipboardWrite", Map.of()))),
                meta);
    }

    @Test
    void resourceMetaShouldEmitCspDomainBorderVerbatim() {
        McpResourceUiCspSpec csp = new McpResourceUiCspSpec();
        csp.setResourceDomains(List.of("https://cdn.test"));
        csp.setFrameDomains(List.of());
        McpResourceUiSpec ui = new McpResourceUiSpec();
        ui.setCsp(csp);
        ui.setDomain("abc.example");
        ui.setPrefersBorder(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> uiNode =
                (Map<String, Object>) McpAppsMetadata.resourceMeta(ui).get("ui");

        assertEquals(Map.of("resourceDomains", List.of("https://cdn.test"), "frameDomains",
                List.of()), uiNode.get("csp"));
        assertEquals("abc.example", uiNode.get("domain"));
        assertEquals(Boolean.FALSE, uiNode.get("prefersBorder"));
        assertFalse(uiNode.containsKey("permissions"));
    }

    // ── isViewMimeType ───────────────────────────────────────────────────────────────────────────

    @Test
    void isViewMimeTypeShouldAcceptCaseAndWhitespaceVariants() {
        assertTrue(McpAppsMetadata.isViewMimeType("text/html;profile=mcp-app"));
        assertTrue(McpAppsMetadata.isViewMimeType("TEXT/HTML; Profile=MCP-APP"));
        assertTrue(McpAppsMetadata.isViewMimeType("text/html; charset=utf-8; profile=\"mcp-app\""));
    }

    @Test
    void isViewMimeTypeShouldRejectPlainHtmlAndOtherProfiles() {
        assertFalse(McpAppsMetadata.isViewMimeType(null));
        assertFalse(McpAppsMetadata.isViewMimeType("text/html"));
        assertFalse(McpAppsMetadata.isViewMimeType("text/html;profile=other"));
        assertFalse(McpAppsMetadata.isViewMimeType("text/plain;profile=mcp-app"));
    }

    // ── validate ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void validateShouldAcceptToolLinkedToStaticViewWithMcpAppMimeType() throws Exception {
        Map<String, McpServerResourceSpec> resources = viewResource("text/html;profile=mcp-app");
        List<McpServerToolSpec> tools = List.of(uiTool(VIEW_URI));

        assertDoesNotThrow(() -> McpAppsMetadata.validate(tools, resources.values(),
                new ResourceHandler(null, resources, "weather")));
    }

    @Test
    void validateShouldFailWhenResourceUriDoesNotResolve() throws Exception {
        Map<String, McpServerResourceSpec> resources = viewResource("text/html;profile=mcp-app");
        List<McpServerToolSpec> tools = List.of(uiTool("ui://weather/forecast/missing.html"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpAppsMetadata.validate(tools, resources.values(),
                        new ResourceHandler(null, resources, "weather")));
        assertTrue(error.getMessage().contains("get-forecast"));
        assertTrue(error.getMessage().contains("ui://weather/forecast/missing.html"));
    }

    @Test
    void validateShouldFailWhenViewMimeTypeLacksMcpAppProfile() throws Exception {
        Map<String, McpServerResourceSpec> resources = viewResource(null);
        List<McpServerToolSpec> tools = List.of(uiTool(VIEW_URI));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpAppsMetadata.validate(tools, resources.values(),
                        new ResourceHandler(null, resources, "weather")));
        assertTrue(error.getMessage().contains("text/html;profile=mcp-app"));
    }

    @Test
    void validateShouldFailWhenResourceUriUsesAnotherScheme() throws Exception {
        Map<String, McpServerResourceSpec> resources = viewResource("text/html;profile=mcp-app");
        List<McpServerToolSpec> tools = List.of(uiTool("data://weather/forecast/index.html"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpAppsMetadata.validate(tools, resources.values(),
                        new ResourceHandler(null, resources, "weather")));
        assertTrue(error.getMessage().contains("must start with ui://"));
    }

    @Test
    void validateShouldFailWhenUiResourceIsNotStatic() {
        McpServerResourceSpec dynamic = new McpServerResourceSpec();
        dynamic.setName("dynamic-view");
        dynamic.setUri("ui://weather/dynamic");
        Map<String, McpServerResourceSpec> resources = Map.of("dynamic-view", dynamic);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpAppsMetadata.validate(List.of(), resources.values(),
                        new ResourceHandler(null, resources, "weather")));
        assertTrue(error.getMessage().contains("dynamic-view"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private Map<String, McpServerResourceSpec> viewResource(String mimeType) throws Exception {
        Path dir = tempDir.resolve("forecast");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), "<!doctype html><title>x</title>");

        McpServerResourceSpec spec = new McpServerResourceSpec();
        spec.setName("forecast-view");
        spec.setUri("ui://weather/forecast");
        spec.setLocation(dir.toUri().toString());
        spec.setMimeType(mimeType);
        Map<String, McpServerResourceSpec> map = new LinkedHashMap<>();
        map.put(spec.getName(), spec);
        return map;
    }

    private static McpServerToolSpec uiTool(String resourceUri) {
        McpServerToolSpec tool = new McpServerToolSpec("get-forecast", null, "Forecast");
        tool.setUi(new McpToolUiSpec(resourceUri));
        return tool;
    }
}
