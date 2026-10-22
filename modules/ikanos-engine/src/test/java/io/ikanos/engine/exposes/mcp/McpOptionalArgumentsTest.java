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
import org.restlet.routing.Router;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Test for #847, through the full engine stack against a local stub upstream: an MCP tool whose
 * arguments are all optional must work when called with no arguments. Optional parts of the
 * consumed path and body are written as Mustache sections, which must render to nothing (YAML,
 * then Capability, then MCP tools/call, then HTTP).
 */
public class McpOptionalArgumentsTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldDropOptionalQuerySectionWhenCalledWithNoArguments()
            throws Exception {
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedUri, receivedBody);
        upstream.start();

        try {
            JsonNode result = callTool(capabilityFromYaml(capabilityYaml(port)), "list-things");

            assertFalse(result.path("isError").asBoolean(), "#847: tool call failed: " + result);
            assertEquals("/things", receivedUri.get(),
                    "#847: an absent optional filter must leave no query string");
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldDropOptionalBodyMembersWhenCalledWithNoArguments()
            throws Exception {
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedUri, receivedBody);
        upstream.start();

        try {
            JsonNode result = callTool(capabilityFromYaml(capabilityYaml(port)), "preview-thing");

            assertFalse(result.path("isError").asBoolean(), "#847: tool call failed: " + result);
            assertEquals("{\"preview\":true}", receivedBody.get(),
                    "#847: absent optional members must be left out of the body");
        } finally {
            upstream.stop();
        }
    }

    private JsonNode callTool(Capability capability, String tool) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.putObject("arguments");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: upstream
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        things:
                          path: "/things{{#status}}?status={{status}}{{/status}}"
                          operations:
                            list-things:
                              method: GET
                        preview:
                          path: "/preview"
                          operations:
                            preview-thing:
                              method: POST
                              body:
                                type: json
                                data: >-
                                  {"preview":true{{#name}},"name":"{{name}}"{{/name}}{{#plan}},
                                  "plan":"{{plan}}"{{/plan}}}
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: demo
                      tools:
                        list-things:
                          description: "List things, optionally filtered by status"
                          inputParameters:
                            status:
                              type: string
                              required: false
                              description: "Optional status filter"
                          call: upstream.list-things
                        preview-thing:
                          description: "Preview a thing; every field is optional"
                          inputParameters:
                            name:
                              type: string
                              required: false
                              description: "Optional name"
                            plan:
                              type: string
                              required: false
                              description: "Optional plan code"
                          call: upstream.preview-thing
                """.formatted(schemaVersion, port);
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> uri,
            AtomicReference<String> body) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Restlet recorder = new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        uri.set(request.getResourceRef().getPath()
                                + (request.getResourceRef().getQuery() != null
                                        ? "?" + request.getResourceRef().getQuery()
                                        : ""));
                        try {
                            body.set(request.getEntity() != null
                                    ? request.getEntity().getText()
                                    : null);
                        } catch (java.io.IOException e) {
                            body.set("<unreadable: " + e.getMessage() + ">");
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
                    }
                };
                Router router = new Router(getContext());
                router.attach("/things", recorder);
                router.attach("/preview", recorder);
                return router;
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
