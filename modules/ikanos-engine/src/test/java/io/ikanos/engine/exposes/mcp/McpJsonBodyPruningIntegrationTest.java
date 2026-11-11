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
 * Integration test for #985 (YAML, then Capability, then MCP tools/call, then HTTP against a
 * local recording upstream): in a structured JSON body, an object or array emptied by absent
 * optional arguments is left out, while one the author wrote empty is sent.
 */
public class McpJsonBodyPruningIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSendOnlyTheProvidedFilterBranch() throws Exception {
        String body = callListProjects(MAPPER.createObjectNode().put("team", "ENG"));

        assertEquals("{\"query\":\"query ListProjects { projects { nodes { id } } }\","
                + "\"variables\":{\"filter\":{\"accessibleTeams\":{\"some\":{\"or\":["
                + "{\"key\":{\"eqIgnoreCase\":\"ENG\"}},{\"name\":{\"eqIgnoreCase\":\"ENG\"}}"
                + "]}}},\"first\":25},\"options\":{}}", body);
    }

    @Test
    public void handleToolCallShouldLeaveOutFilterEmptiedByAbsentArguments() throws Exception {
        String body = callListProjects(MAPPER.createObjectNode());

        assertEquals("{\"query\":\"query ListProjects { projects { nodes { id } } }\","
                + "\"variables\":{\"first\":25},\"options\":{}}", body,
                "The emptied filter is left out; the options object written empty is kept");
    }

    private String callListProjects(ObjectNode arguments) throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));
            JsonNode result = callTool(capability, "list-projects", arguments);
            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return receivedBody.get();
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
                    - namespace: linear
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        graphql:
                          path: "/graphql"
                          operations:
                            list-projects:
                              method: POST
                              body:
                                type: json
                                data:
                                  query: "query ListProjects { projects { nodes { id } } }"
                                  variables:
                                    filter:
                                      name: {containsIgnoreCase: "{{query}}"}
                                      accessibleTeams: {some: {or: [{key: {eqIgnoreCase: "{{team}}"}}, {name: {eqIgnoreCase: "{{team}}"}}]}}
                                      status: {or: [{name: {eqIgnoreCase: "{{status}}"}}, {type: {eq: "{{status}}"}}]}
                                    first: 25
                                    after: "{{cursor}}"
                                  options: {}
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: linear-tools
                      tools:
                        list-projects:
                          description: "List projects, optionally filtered"
                          inputParameters:
                            query:
                              type: string
                              required: false
                              description: "Optional text in the project name"
                            team:
                              type: string
                              required: false
                              description: "Optional team key or name"
                            status:
                              type: string
                              required: false
                              description: "Optional status name or type"
                            cursor:
                              type: string
                              required: false
                              description: "Optional pagination cursor"
                          call: linear.list-projects
                """.formatted(schemaVersion, port);
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
                        response.setEntity("{\"data\":{\"projects\":{\"nodes\":[]}}}",
                                MediaType.APPLICATION_JSON);
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
