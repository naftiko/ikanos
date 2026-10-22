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
 * Test for GHSA-6359-rp48-m9j6, through the full engine stack against a local stub upstream: a
 * value passed to an MCP tool must stay one JSON value in a {@code type: json} request body, so a
 * caller cannot add members to the request sent upstream (YAML, then Capability, then MCP
 * tools/call, then HTTP).
 */
public class McpJsonBodyEscapingTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldNotLetStringArgumentAddJsonMembers() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody);
        upstream.start();

        try {
            ObjectNode arguments = MAPPER.createObjectNode()
                    .put("name", "x\",\"admin\":true,\"y\":\"");
            JsonNode result = callTool(capabilityFromYaml(capabilityYaml(port)), "create-user",
                    arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            JsonNode sent = MAPPER.readTree(receivedBody.get());
            assertEquals(1, sent.size(), "GHSA-6359-rp48-m9j6: no member may be injected: " + sent);
            assertEquals("x\",\"admin\":true,\"y\":\"", sent.path("name").asString());
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldSendObjectArgumentAsJsonObject() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody);
        upstream.start();

        try {
            ObjectNode arguments = MAPPER.createObjectNode().put("name", "Acme");
            arguments.putObject("properties").put("tokens", 5).put("model", "a\"b");
            JsonNode result = callTool(capabilityFromYaml(capabilityYaml(port)), "create-user",
                    arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            JsonNode sent = MAPPER.readTree(receivedBody.get());
            assertEquals(5, sent.path("properties").path("tokens").asInt());
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
                    - namespace: upstream
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        users:
                          path: "/users"
                          operations:
                            create-user:
                              method: POST
                              body:
                                type: json
                                data: >-
                                  {"name":"{{name}}"{{#properties}},
                                  "properties":{{properties}}{{/properties}}}
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: demo
                      tools:
                        create-user:
                          description: "Create a user"
                          inputParameters:
                            name:
                              type: string
                              description: "User name"
                            properties:
                              type: object
                              required: false
                              description: "Optional free-form properties"
                          call: upstream.create-user
                """.formatted(schemaVersion, port);
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> body) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/users", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            body.set(request.getEntity().getText());
                        } catch (java.io.IOException e) {
                            body.set("<unreadable: " + e.getMessage() + ">");
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"id\":\"u1\"}", MediaType.APPLICATION_JSON);
                    }
                });
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
