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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.ikanos.spec.util.VersionHelper;

/**
 * Validates the MCP Apps ({@code ui}) schema additions on exposed MCP tools and resources.
 */
class McpAppsSchemaValidationTest {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpAppsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static Set<ValidationMessage> validate(String toolUi, String resourceUi)
            throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "MCP Apps"
                  description: "MCP Apps schema test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "weather-api"
                      baseUri: "https://api.weather.test"
                      resources:
                        forecast:
                          path: "/v1/forecast"
                          operations:
                            get-forecast:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      namespace: "weather"
                      port: 3001
                      resources:
                        forecast-view:
                          display: "Forecast view"
                          description: "Interactive forecast chart."
                          uri: "ui://weather/forecast"
                          location: "file:///opt/weather/ui"
                          mimeType: "text/html;profile=mcp-app"
                %s
                      tools:
                        get-forecast:
                          description: "Seven-day forecast."
                          call: "weather-api.get-forecast"
                %s
                """.formatted(IKANOS, indent(resourceUi), indent(toolUi));
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    private static String indent(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.strip().split("\n")) {
            out.append("          ").append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static final String RESOURCE_UI_FULL = """
            ui:
              csp:
                connectDomains: ["https://api.weather.test"]
                resourceDomains: ["https://*.cdn.test"]
                frameDomains: []
                baseUriDomains: []
              permissions:
                clipboardWrite: true
                camera: false
              domain: "abc.claudemcpcontent.com"
              prefersBorder: true
            """;

    @Test
    void schemaShouldAcceptToolUiAndResourceUi() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  resourceUri: "ui://weather/forecast/index.html"
                  visibility: ["model", "app"]
                """, RESOURCE_UI_FULL);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectToolUiResourceUriWithoutUiScheme() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  resourceUri: "https://weather.test/index.html"
                """, "ui: {}");
        assertFalse(errors.isEmpty(), "A non-ui:// resourceUri must be rejected");
    }

    @Test
    void schemaShouldRejectToolUiWithoutResourceUri() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  visibility: ["app"]
                """, "ui: {}");
        assertFalse(errors.isEmpty(), "ui.resourceUri is required");
    }

    @Test
    void schemaShouldRejectUnknownVisibilityValue() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  resourceUri: "ui://weather/forecast/index.html"
                  visibility: ["user"]
                """, "ui: {}");
        assertFalse(errors.isEmpty(), "visibility only accepts model and app");
    }

    @Test
    void schemaShouldRejectEmptyVisibility() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  resourceUri: "ui://weather/forecast/index.html"
                  visibility: []
                """, "ui: {}");
        assertFalse(errors.isEmpty(), "visibility must have at least one item");
    }

    @Test
    void schemaShouldRejectUnknownPermission() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  resourceUri: "ui://weather/forecast/index.html"
                """, """
                ui:
                  permissions:
                    notifications: true
                """);
        assertFalse(errors.isEmpty(), "Unknown permission keys must be rejected");
    }

    @Test
    void schemaShouldRejectUnknownResourceUiKey() throws Exception {
        Set<ValidationMessage> errors = validate("""
                ui:
                  resourceUri: "ui://weather/forecast/index.html"
                """, """
                ui:
                  html: "<p>inline</p>"
                """);
        assertFalse(errors.isEmpty(), "Inline HTML is not supported in v1");
    }
}
