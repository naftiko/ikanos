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

import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Regression test for #339: MCP tools calling operations with outputRawFormat: xml
 * should transparently convert the XML response to JSON before applying output mappings.
 */
public class McpXmlOutputIntegrationTest {

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    /**
     * When an MCP tool calls a consumes operation that returns XML
     * (outputRawFormat: xml), the output mappings should be applied
     * against the XML-to-JSON conversion, not against the raw XML text.
     */
    @Test
    public void handleToolCallShouldMapXmlResponseWhenOutputRawFormatIsXml() throws Exception {
        int port = findFreePort();
        Component server = createXmlServer(port, "/vessels",
                "<vessels>"
                + "<vessel><vesselCode>V001</vesselCode><vesselName>Sea Eagle</vesselName></vessel>"
                + "<vessel><vesselCode>V002</vesselCode><vesselName>Ocean Star</vesselName></vessel>"
                + "</vessels>");
        server.start();

        try {
            Capability capability = capabilityFromYaml("""
                    ikanos: "%s"
                    capability:
                      consumes:
                        - namespace: legacy
                          type: http
                          baseUri: "http://localhost:%d"
                          resources:
                            vessels:
                              path: "/vessels"
                              operations:
                                list-vessels:
                                  method: GET
                                  outputRawFormat: xml
                      exposes:
                        - type: mcp
                          port: 0
                          namespace: shipyard-tools
                          tools:
                            list-legacy-vessels:
                              description: "List vessels from legacy XML API"
                              call: legacy.list-vessels
                              outputParameters:
                                - type: array
                                  mapping: "$.vessel"
                                  items:
                                    type: object
                                    properties:
                                      vesselCode:
                                        type: string
                                        mapping: "$.vesselCode"
                                      name:
                                        type: string
                                        mapping: "$.vesselName"
                    """.formatted(schemaVersion, port));

            McpServerSpec mcpSpec =
                    (McpServerSpec) capability.getSpec().getCapability().getExposes().get(0);
            ToolHandler handler = new ToolHandler(capability, mcpSpec.getTools());

            McpSchema.CallToolResult result =
                    handler.handleToolCall("list-legacy-vessels", Map.of());

            assertNotNull(result, "Tool result should not be null");
            assertFalse(result.isError(), "Tool call should not be an error");
            assertNotNull(result.content(), "Result content should not be null");
            assertFalse(result.content().isEmpty(), "Result content should not be empty");

            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode mapped = mapper.readTree(text);

            assertTrue(mapped.isArray(), "Mapped result should be an array");
            assertEquals(2, mapped.size(), "Should contain two vessels");
            assertEquals("V001", mapped.get(0).path("vesselCode").asText());
            assertEquals("Sea Eagle", mapped.get(0).path("name").asText());
            assertEquals("V002", mapped.get(1).path("vesselCode").asText());
            assertEquals("Ocean Star", mapped.get(1).path("name").asText());
        } finally {
            server.stop();
        }
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static Component createXmlServer(int port, String path, String xmlBody)
            throws Exception {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach(path, new Restlet() {
                    @Override
                    public void handle(org.restlet.Request request,
                            org.restlet.Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(xmlBody, MediaType.APPLICATION_XML);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
