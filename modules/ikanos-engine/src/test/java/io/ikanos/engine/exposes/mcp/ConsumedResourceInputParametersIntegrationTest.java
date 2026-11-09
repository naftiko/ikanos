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
 * Integration test: {@code inputParameters} declared on a consumed HTTP resource are sent by every
 * operation of that resource, both fixed values and caller arguments (#909).
 */
public class ConsumedResourceInputParametersIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSendResourceLevelQueryParameters() throws Exception {
        assertEquals("res-query=from-resource&%24filter=a+eq+1&op-query=from-operation",
                recordedRequest("list-things", "a eq 1").query(),
                "#909: the resource's query parameters should be sent with the operation's");
    }

    @Test
    public void handleToolCallShouldSendResourceLevelHeader() throws Exception {
        assertEquals("from-resource", recordedRequest("list-things", "a eq 1").header(),
                "#909: the resource's header should be sent");
    }

    @Test
    public void handleToolCallShouldSendResourceLevelParametersForOperationWithoutParameters()
            throws Exception {
        assertEquals("res-query=from-resource&%24filter=b+eq+2",
                recordedRequest("list-other", "b eq 2").query(),
                "#909: an operation with no parameters of its own should send the resource's");
    }

    private record Recorded(String query, String header) {
    }

    private Recorded recordedRequest(String tool, String filter) throws Exception {
        AtomicReference<Recorded> recorded = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, recorded);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));
            ObjectNode arguments = MAPPER.createObjectNode();
            arguments.put("filter", filter);

            JsonNode result = callTool(capability, tool, arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return recorded.get();
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
                    - namespace: api
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      resources:
                        things:
                          path: "/things"
                          inputParameters:
                            res-query:
                              in: query
                              value: "from-resource"
                            X-Res-Header:
                              in: header
                              value: "from-resource"
                            filter:
                              in: query
                              wireName: "$filter"
                          operations:
                            list-things:
                              method: GET
                              inputParameters:
                                op-query:
                                  in: query
                                  value: "from-operation"
                            list-other:
                              method: GET
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: probe-mcp
                      tools:
                        list-things:
                          description: "List things"
                          inputParameters:
                            filter:
                              type: string
                              description: "Filter"
                          call: api.list-things
                        list-other:
                          description: "List other things"
                          inputParameters:
                            filter:
                              type: string
                              description: "Filter"
                          call: api.list-other
                """.formatted(schemaVersion, port);
    }

    private static Component createRecordingUpstream(int port,
            AtomicReference<Recorded> recorded) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/v1/things", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        recorded.set(new Recorded(request.getResourceRef().getQuery(),
                                request.getHeaders().getFirstValue("X-Res-Header", true)));
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"things\":[]}", MediaType.APPLICATION_JSON);
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
