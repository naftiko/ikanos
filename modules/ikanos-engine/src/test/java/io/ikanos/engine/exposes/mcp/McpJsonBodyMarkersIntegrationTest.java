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
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test for #1132 (YAML, then Capability, then MCP tools/call, then HTTP against a
 * local recording upstream): the {@code $onlyIf}, {@code $first} and {@code $keep} markers of a
 * structured JSON body, on the Notion {@code patch-page} and Gong {@code search-calls} bodies
 * that motivated them.
 */
public class McpJsonBodyMarkersIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    void handleToolCallShouldSendTheChosenIconAndTheSilentNotifications() throws Exception {
        String body = call("patch-page", MAPPER.createObjectNode().put("page_id", "p1")
                .put("icon_url", "https://example.com/i.png").put("silent", true));

        assertEquals("{\"icon\":{\"type\":\"external\",\"external\":{\"url\":"
                + "\"https://example.com/i.png\"}},\"notifications\":{\"mode\":\"silent\"}}",
                body);
    }

    @Test
    void handleToolCallShouldSendTheDefaultTemplateWithItsTimezone() throws Exception {
        String body = call("patch-page", MAPPER.createObjectNode().put("page_id", "p1")
                .put("use_default_template", true).put("timezone", "Europe/Paris")
                .put("silent", false));

        assertEquals("{\"template\":{\"type\":\"default\",\"timezone\":\"Europe/Paris\"}}", body);
    }

    @Test
    void handleToolCallShouldSendTheRequiredFilterEmptyWhenNoFilterIsGiven() throws Exception {
        String body = call("search-calls", MAPPER.createObjectNode());

        assertEquals("{\"filter\":{},\"contentSelector\":{\"exposedFields\":{\"parties\":true}}}",
                body);
    }

    @Test
    void handleToolCallShouldSendTheContentSelectorWhenIncludeContentIsTrue() throws Exception {
        String body = call("search-calls", MAPPER.createObjectNode()
                .put("fromDateTime", "2026-11-01T00:00:00Z").put("includeContent", true));

        assertEquals("{\"filter\":{\"fromDateTime\":\"2026-11-01T00:00:00Z\"},"
                + "\"contentSelector\":{\"exposedFields\":{\"parties\":true,\"content\":"
                + "{\"brief\":true,\"keyPoints\":true}}}}", body);
    }

    private String call(String tool, ObjectNode arguments) throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));
            try {
                JsonNode result = callTool(capability, tool, arguments);
                assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
                return receivedBody.get();
            } finally {
                // Releases the consumed HTTP client that the dispatch started on demand
                capability.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    private JsonNode callTool(Capability capability, String tool, ObjectNode arguments) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: notion
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        pages:
                          path: "/v1/pages/{{page_id}}"
                          operations:
                            patch-page:
                              method: PATCH
                              body:
                                type: json
                                data:
                                  icon:
                                    $first:
                                      - {$onlyIf: icon_emoji, type: emoji, emoji: "{{icon_emoji}}"}
                                      - {$onlyIf: icon_url, type: external, external: {url: "{{icon_url}}"}}
                                  template:
                                    $first:
                                      - {$onlyIf: template_id, type: template_id, template_id: "{{template_id}}", timezone: "{{timezone}}"}
                                      - {$onlyIf: use_default_template, type: default, timezone: "{{timezone}}"}
                                  notifications: {$onlyIf: silent, mode: silent}
                    - namespace: gong
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        calls:
                          path: "/v2/calls/extensive"
                          operations:
                            search-calls:
                              method: POST
                              body:
                                type: json
                                data:
                                  filter: {$keep: true, fromDateTime: "{{fromDateTime}}", callIds: "{{callIds}}"}
                                  contentSelector:
                                    exposedFields:
                                      parties: true
                                      content: {$onlyIf: includeContent, brief: true, keyPoints: true}
                                  cursor: "{{cursor}}"
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: tools
                      tools:
                        patch-page:
                          description: "Update a page"
                          inputParameters:
                            page_id: {type: string, required: true, description: "Page ID"}
                            icon_emoji: {type: string, description: "Icon emoji"}
                            icon_url: {type: string, description: "Icon URL"}
                            template_id: {type: string, description: "Template ID"}
                            use_default_template: {type: boolean, description: "Apply the default template"}
                            timezone: {type: string, description: "IANA timezone"}
                            silent: {type: boolean, description: "Skip notifications"}
                          call: notion.patch-page
                        search-calls:
                          description: "Search calls"
                          inputParameters:
                            fromDateTime: {type: string, description: "Start of the range"}
                            callIds: {type: array, items: {type: string}, description: "Call IDs"}
                            includeContent: {type: boolean, description: "Include the content"}
                            cursor: {type: string, description: "Pagination cursor"}
                          call: gong.search-calls
                """.formatted(schemaVersion, port, port);
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> body) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                return new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            body.set(request.getEntity().getText());
                        } catch (java.io.IOException e) {
                            body.set("<unreadable: " + e.getMessage() + ">");
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{}", MediaType.APPLICATION_JSON);
                    }
                };
            }
        });
        return component;
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
