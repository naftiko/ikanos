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
 * Integration test: an MCP {@code array} argument mapped to a consumed query parameter with a
 * wire name is sent as a repeated parameter, the Rails/PHP {@code name[]} convention (#851).
 */
public class McpArrayQueryParameterIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSendArrayArgumentAsRepeatedQueryParameter() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, query);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));
            ObjectNode arguments = MAPPER.createObjectNode();
            arguments.putArray("status").add("active").add("pending");

            JsonNode result = callTool(capability, "list-subscriptions", arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            assertEquals("status%5B%5D=active&status%5B%5D=pending", query.get());
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
                    - namespace: lago
                      type: http
                      baseUri: "http://localhost:%d/api/v1"
                      resources:
                        subscriptions:
                          path: "/subscriptions"
                          operations:
                            list-subscriptions:
                              method: GET
                              inputParameters:
                                status:
                                  in: query
                                  wireName: "status[]"
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: lago-tools
                      tools:
                        list-subscriptions:
                          description: "List subscriptions"
                          inputParameters:
                            status:
                              type: array
                              description: "Statuses to include"
                          call: lago.list-subscriptions
                """.formatted(schemaVersion, port);
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> query) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/api/v1/subscriptions", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        query.set(request.getResourceRef().getQuery());
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"subscriptions\":[]}", MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
