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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration tests for MCP Apps (SEP-1865) support on an exposed MCP server: a capability is
 * loaded end-to-end and the {@code _meta.ui} emissions are asserted on the JSON-RPC wire through
 * {@link ProtocolDispatcher}. No upstream is called.
 */
class McpAppsIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String VIEW_URI = "ui://weather/forecast/index.html";
    private static final String VIEW_HTML = "<!doctype html><html><body>forecast</body></html>";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private String viewLocation;

    @BeforeEach
    void writeView() throws Exception {
        Path dir = tempDir.resolve("forecast");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), VIEW_HTML);
        viewLocation = dir.toUri().toString();
    }

    // ── server/discover ──────────────────────────────────────────────────────────────────────────

    @Test
    void serverDiscoverShouldAdvertiseUiExtensionWhenToolDeclaresUi() throws Exception {
        McpServerAdapter adapter = load(appsCapability("text/html;profile=mcp-app", VIEW_URI));

        JsonNode capabilities = dispatch(adapter, "server/discover", "{}")
                .path("result").path("capabilities");

        assertTrue(capabilities.path("extensions").has(McpAppsMetadata.EXTENSION_ID),
                "extensions.io.modelcontextprotocol/ui should be advertised: " + capabilities);
        assertTrue(capabilities.has("resources"));
    }

    @Test
    void serverDiscoverShouldNotAdvertiseExtensionsWhenNoToolDeclaresUi() throws Exception {
        McpServerAdapter adapter = load(plainCapability());

        JsonNode capabilities = dispatch(adapter, "server/discover", "{}")
                .path("result").path("capabilities");

        assertFalse(capabilities.has("extensions"), "No extensions expected: " + capabilities);
    }

    // ── tools/list ───────────────────────────────────────────────────────────────────────────────

    @Test
    void toolsListShouldEmitMetaUiResourceUriAndVisibility() throws Exception {
        McpServerAdapter adapter = load(appsCapability("text/html;profile=mcp-app", VIEW_URI));

        JsonNode tools = dispatch(adapter, "tools/list", "{}").path("result").path("tools");

        JsonNode getForecast = toolNamed(tools, "get-forecast");
        assertEquals(VIEW_URI, getForecast.path("_meta").path("ui").path("resourceUri").asText());
        assertTrue(getForecast.path("_meta").path("ui").path("visibility").isMissingNode(),
                "Unauthored visibility must not be emitted");

        JsonNode refresh = toolNamed(tools, "refresh-forecast");
        assertEquals("app", refresh.path("_meta").path("ui").path("visibility").get(0).asText());
    }

    @Test
    void toolsListShouldOmitMetaWhenToolDeclaresNoUi() throws Exception {
        McpServerAdapter adapter = load(plainCapability());

        JsonNode tools = dispatch(adapter, "tools/list", "{}").path("result").path("tools");

        assertTrue(tools.get(0).path("_meta").isMissingNode());
    }

    // ── resources/list and resources/read ────────────────────────────────────────────────────────

    @Test
    void resourcesListShouldEmitMetaUiOnExpandedViewEntry() throws Exception {
        McpServerAdapter adapter = load(appsCapability("text/html;profile=mcp-app", VIEW_URI));

        JsonNode resources = dispatch(adapter, "resources/list", "{}")
                .path("result").path("resources");

        assertEquals(1, resources.size());
        JsonNode entry = resources.get(0);
        assertEquals(VIEW_URI, entry.path("uri").asText());
        assertEquals("text/html;profile=mcp-app", entry.path("mimeType").asText());
        assertTrue(entry.path("_meta").path("ui").path("prefersBorder").asBoolean());
        assertTrue(entry.path("_meta").path("ui").path("permissions").path("clipboardWrite")
                .isObject(), "Requested permission is an empty-object presence flag");
    }

    @Test
    void resourcesReadShouldServeHtmlAsTextWithMetaUi() throws Exception {
        McpServerAdapter adapter = load(appsCapability("text/html;profile=mcp-app", VIEW_URI));

        JsonNode content = dispatch(adapter, "resources/read",
                "{\"uri\":\"" + VIEW_URI + "\"}").path("result").path("contents").get(0);

        assertEquals(VIEW_HTML, content.path("text").asText());
        assertTrue(content.path("blob").isMissingNode());
        assertEquals("text/html;profile=mcp-app", content.path("mimeType").asText());
        assertTrue(content.path("_meta").path("ui").path("prefersBorder").asBoolean());
    }

    // ── load-time validation ─────────────────────────────────────────────────────────────────────

    @Test
    void capabilityLoadShouldFailWhenViewIsNotDeclared() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> load(appsCapability("text/html;profile=mcp-app",
                        "ui://weather/forecast/typo.html")));
        assertTrue(error.getMessage().contains("ui://weather/forecast/typo.html"));
    }

    @Test
    void capabilityLoadShouldFailWhenViewMimeTypeIsPlainHtml() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> load(appsCapability("text/html", VIEW_URI)));
        assertTrue(error.getMessage().contains("text/html;profile=mcp-app"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String appsCapability(String mimeType, String resourceUri) {
        return """
                ikanos: "%s"
                info:
                  display: "MCP Apps"
                  description: "MCP Apps integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "weather"
                      resources:
                        forecast-view:
                          display: "Forecast view"
                          description: "Interactive forecast chart."
                          uri: "ui://weather/forecast"
                          location: "%s"
                          mimeType: "%s"
                          ui:
                            prefersBorder: true
                            permissions:
                              clipboardWrite: true
                      tools:
                        get-forecast:
                          description: "Seven-day forecast."
                          ui:
                            resourceUri: "%s"
                          outputParameters:
                            - type: "string"
                              value: "sunny"
                        refresh-forecast:
                          description: "Re-fetch the forecast shown in the view."
                          ui:
                            resourceUri: "%s"
                            visibility: ["app"]
                          outputParameters:
                            - type: "string"
                              value: "sunny"
                  consumes: []
                """.formatted(IKANOS, viewLocation, mimeType, resourceUri, resourceUri);
    }

    private static String plainCapability() {
        return """
                ikanos: "%s"
                info:
                  display: "Plain MCP"
                  description: "No MCP Apps"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "plain"
                      tools:
                        ping:
                          description: "Ping."
                          outputParameters:
                            - type: "string"
                              value: "pong"
                  consumes: []
                """.formatted(IKANOS);
    }

    private McpServerAdapter load(String yaml) throws Exception {
        IkanosSpec spec = yamlMapper.readValue(yaml, IkanosSpec.class);
        return (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private static JsonNode toolNamed(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name + " in " + tools);
    }
}
