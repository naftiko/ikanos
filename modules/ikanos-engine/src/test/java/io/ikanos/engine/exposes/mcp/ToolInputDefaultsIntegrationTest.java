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
 * Integration test: an MCP tool input {@code default} (#979) is advertised in {@code tools/list}
 * and, when a {@code tools/call} leaves the input out, reaches the upstream with its JSON type,
 * both as a query parameter and inside an inline-object JSON body, for a tool's own inputs and
 * for an aggregate flow's inputs behind {@code ref}.
 */
public class ToolInputDefaultsIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void toolsListShouldAdvertiseInputDefaults() throws Exception {
        JsonNode tools = withUpstream((capability, recorded) ->
                dispatch(capability, "tools/list", null).path("tools"));

        JsonNode size = null;
        for (JsonNode tool : tools) {
            if ("list-things".equals(tool.path("name").asString())) {
                size = tool.path("inputSchema").path("properties").path("size");
            }
        }
        assertEquals(MAPPER.readTree("200"), size.path("default"), String.valueOf(tools));
    }

    @Test
    public void toolsCallShouldSendTypedDefaultsAsQueryParameters() throws Exception {
        assertEquals("size=200&flag=true&tags=a&tags=b",
                call("list-things", MAPPER.createObjectNode()).query());
    }

    @Test
    public void toolsCallShouldSendTypedDefaultsInInlineObjectJsonBody() throws Exception {
        assertEquals(MAPPER.readTree("""
                {"size":200,"flag":true,"tags":["a","b"],"filter":{"status":"open"},"ratio":0.5}
                """), MAPPER.readTree(call("create-thing", MAPPER.createObjectNode()).body()));
    }

    @Test
    public void toolsCallShouldSendCallerValueInsteadOfDefault() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("size", 5);
        arguments.putNull("flag");

        assertEquals("size=5&flag=true&tags=a&tags=b", call("list-things", arguments).query());
    }

    @Test
    public void toolsCallShouldApplyAggregateFlowDefaultsBehindRef() throws Exception {
        assertEquals("size=25", call("ask", MAPPER.createObjectNode()).query());
    }

    @Test
    public void capabilityShouldRefuseToLoadWhenDefaultDoesNotMatchType() {
        String yaml = capabilityYaml(findFreePortUnchecked())
                .replace("default: 200", "default: \"200\"");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> capabilityFromYaml(yaml));

        assertTrue(error.getMessage().contains("'probe-mcp.list-things'"), error.getMessage());
    }

    private record Recorded(String query, String body) {
    }

    @FunctionalInterface
    private interface UpstreamScenario<T> {
        T run(Capability capability, AtomicReference<Recorded> recorded) throws Exception;
    }

    private <T> T withUpstream(UpstreamScenario<T> scenario) throws Exception {
        AtomicReference<Recorded> recorded = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, recorded);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));
            try {
                return scenario.run(capability, recorded);
            } finally {
                // Releases the consumed HTTP client that the dispatch started on demand
                capability.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    private Recorded call(String tool, ObjectNode arguments) throws Exception {
        return withUpstream((capability, recorded) -> {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", tool);
            params.set("arguments", arguments);
            JsonNode result = dispatch(capability, "tools/call", params);
            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return recorded.get();
        });
    }

    private static JsonNode dispatch(Capability capability, String method, ObjectNode params) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode body = params != null ? params.deepCopy() : request.putObject("params");
        request.set("params", body);
        body.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  aggregates:
                    - display: "Things"
                      namespace: "things"
                      flows:
                        ask:
                          description: "Ask about things."
                          inputParameters:
                            size:
                              type: integer
                              description: "Page size."
                              required: false
                              default: 25
                          call: api.list-few
                  consumes:
                    - namespace: api
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list-things:
                              method: GET
                              inputParameters:
                                size: { in: query }
                                flag: { in: query }
                                tags: { in: query }
                            list-few:
                              method: GET
                              inputParameters:
                                size: { in: query }
                            create-thing:
                              method: POST
                              body:
                                type: json
                                data:
                                  size: "{{size}}"
                                  flag: "{{flag}}"
                                  tags: "{{tags}}"
                                  filter: "{{filter}}"
                                  ratio: "{{ratio}}"
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: probe-mcp
                      tools:
                        list-things:
                          description: "List things"
                          inputParameters:
                            size:
                              type: integer
                              description: "Page size."
                              required: false
                              default: 200
                            flag:
                              type: boolean
                              description: "Flag."
                              required: false
                              default: true
                            tags:
                              type: array
                              description: "Tags."
                              required: false
                              items: { type: string }
                              default: [a, b]
                          call: api.list-things
                        create-thing:
                          description: "Create a thing"
                          inputParameters:
                            size:
                              type: integer
                              description: "Page size."
                              required: false
                              default: 200
                            flag:
                              type: boolean
                              description: "Flag."
                              required: false
                              default: true
                            tags:
                              type: array
                              description: "Tags."
                              required: false
                              items: { type: string }
                              default: [a, b]
                            filter:
                              type: object
                              description: "Filter."
                              required: false
                              default: { status: open }
                            ratio:
                              type: number
                              description: "Ratio."
                              required: false
                              default: 0.5
                          call: api.create-thing
                        ask:
                          ref: things.ask
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
                        String body = null;
                        try {
                            body = request.getEntity() != null ? request.getEntity().getText()
                                    : null;
                        } catch (java.io.IOException e) {
                            body = "<unreadable>";
                        }
                        recorded.set(new Recorded(request.getResourceRef().getQuery(), body));
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

    private static int findFreePortUnchecked() {
        try {
            return findFreePort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
