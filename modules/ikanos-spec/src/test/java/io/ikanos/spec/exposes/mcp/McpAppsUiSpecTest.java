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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Deserialization tests for the MCP Apps {@code ui} blocks on tools and resources.
 */
class McpAppsUiSpecTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void toolSpecShouldDeserializeUiResourceUriAndVisibility() throws Exception {
        McpServerToolSpec tool = yaml.readValue("""
                name: refresh-forecast
                description: Re-fetch the forecast.
                ui:
                  resourceUri: ui://weather/forecast/index.html
                  visibility: [app]
                """, McpServerToolSpec.class);

        assertNotNull(tool.getUi());
        assertEquals("ui://weather/forecast/index.html", tool.getUi().getResourceUri());
        assertEquals(List.of("app"), tool.getUi().getVisibility());
    }

    @Test
    void toolSpecShouldLeaveUiNullWhenOmitted() throws Exception {
        McpServerToolSpec tool = yaml.readValue("""
                name: get-forecast
                description: Forecast.
                """, McpServerToolSpec.class);

        assertNull(tool.getUi());
    }

    @Test
    void toolUiShouldDefaultToEmptyVisibility() throws Exception {
        McpToolUiSpec ui = yaml.readValue("resourceUri: ui://x/index.html\n", McpToolUiSpec.class);

        assertTrue(ui.getVisibility().isEmpty(), "Unauthored visibility must stay empty");
    }

    @Test
    void resourceSpecShouldDeserializeFullUiBlock() throws Exception {
        McpServerResourceSpec resource = yaml.readValue("""
                name: forecast-view
                uri: ui://weather/forecast
                location: file:///opt/weather/ui
                mimeType: text/html;profile=mcp-app
                ui:
                  csp:
                    connectDomains: [https://api.weather.test]
                    resourceDomains: [https://cdn.test]
                  permissions:
                    clipboardWrite: true
                    camera: false
                  domain: abc.example
                  prefersBorder: true
                """, McpServerResourceSpec.class);

        McpResourceUiSpec ui = resource.getUi();
        assertNotNull(ui);
        assertEquals(List.of("https://api.weather.test"), ui.getCsp().getConnectDomains());
        assertEquals(List.of("https://cdn.test"), ui.getCsp().getResourceDomains());
        assertNull(ui.getCsp().getFrameDomains());
        assertEquals(Boolean.TRUE, ui.getPermissions().getClipboardWrite());
        assertEquals(Boolean.FALSE, ui.getPermissions().getCamera());
        assertNull(ui.getPermissions().getMicrophone());
        assertEquals("abc.example", ui.getDomain());
        assertEquals(Boolean.TRUE, ui.getPrefersBorder());
    }
}
