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
 * Integration test: the {@code queryParameters} list of a consumed resource and operation sends
 * each listed call parameter in the query and leaves out the absent ones, through an MCP
 * {@code tools/call} on the full engine (#987).
 */
public class ConsumedQueryParameterListIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSendListedQueryParameters() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("state", "open");
        arguments.put("page", 2);
        arguments.put("per_page", 50);

        assertEquals("per_page=50&state=open&page=2", recordedQuery(arguments),
                "#987: resource- and operation-level listed names should be sent");
    }

    @Test
    public void handleToolCallShouldLeaveOutAbsentListedQueryParameters() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("state", "closed");

        assertEquals("state=closed", recordedQuery(arguments),
                "#987: a listed name without a call parameter should be left out");
    }

    @Test
    public void capabilityShouldFailToLoadWhenANameIsAlsoInInputParameters() {
        String yaml = capabilityYaml(1, """
                              inputParameters:
                                state:
                                  in: query
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capabilityFromYaml(yaml));

        assertTrue(error.getMessage().contains(
                "api.list-issues declares 'state' in both inputParameters and queryParameters"),
                error.getMessage());
    }

    @Test
    public void capabilityShouldFailToLoadWhenANameIsListedTwice() {
        String yaml = capabilityYaml(1, "").replace("queryParameters: [state, page]",
                "queryParameters: [state, page, state]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capabilityFromYaml(yaml));

        assertTrue(error.getMessage().contains(
                "api.list-issues lists query parameter 'state' twice in queryParameters"),
                error.getMessage());
    }

    private String recordedQuery(ObjectNode arguments) throws Exception {
        AtomicReference<String> recorded = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, recorded);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port, ""));

            JsonNode result = callTool(capability, arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return recorded.get();
        } finally {
            upstream.stop();
        }
    }

    private JsonNode callTool(Capability capability, ObjectNode arguments) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "list-issues");
        params.set("arguments", arguments);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port, String operationInputParameters) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: api
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      resources:
                        issues:
                          path: "/issues"
                          queryParameters: [per_page]
                          operations:
                            list-issues:
                              method: GET
                              queryParameters: [state, page]
                %s  exposes:
                    - type: mcp
                      port: 0
                      namespace: probe-mcp
                      tools:
                        list-issues:
                          description: "List issues"
                          inputParameters:
                            state:
                              type: string
                              description: "State"
                            page:
                              type: number
                              description: "Page"
                              required: false
                            per_page:
                              type: number
                              description: "Page size"
                              required: false
                          call: api.list-issues
                """.formatted(schemaVersion, port, operationInputParameters);
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> recorded) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/v1/issues", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        recorded.set(request.getResourceRef().getQuery());
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"issues\":[]}", MediaType.APPLICATION_JSON);
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
