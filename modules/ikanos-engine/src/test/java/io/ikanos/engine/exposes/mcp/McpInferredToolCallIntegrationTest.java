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
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test: an MCP tool without {@code call} calls the consumed operation named like the
 * tool, with its {@code with} and mapped {@code outputParameters} applied, and a capability whose
 * tool has no such operation, or several, fails to load (#988).
 */
public class McpInferredToolCallIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldCallTheConsumedOperationNamedLikeTheTool() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createUpstream(port, received);
        upstream.start();
        try {
            Capability capability = capability(port, "get-item");

            JsonNode result = callTool(capability, "get-item",
                    MAPPER.createObjectNode().put("item-id", "42"));

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            assertEquals("/items/42?format=compact", received.get(),
                    "the inferred call must reach get-item with the tool's 'with' applied");
            JsonNode output = MAPPER.readTree(result.path("content").get(0).path("text")
                    .asString());
            assertEquals("Widget", output.path("name").asString(), output.toString());
            assertFalse(output.has("internal"), "mapped outputParameters must shape the result");
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void newCapabilityShouldExposeTheInferredCallAsAnExplicitCall() throws Exception {
        Capability capability = capability(findFreePort(), "get-item");

        McpServerSpec mcp = (McpServerSpec) capability.getSpec().getCapability().getExposes()
                .get(0);
        assertEquals("catalog.get-item", mcp.getTools().get("get-item").getCall().getOperation());
    }

    @Test
    public void newCapabilityShouldFailWhenNoConsumedNamespaceHasTheToolName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> capability(findFreePort(), "get-widget"));

        assertTrue(e.getMessage().contains("MCP tool 'get-widget'"), e.getMessage());
    }

    @Test
    public void newCapabilityShouldFailWhenSeveralConsumedNamespacesHaveTheToolName() {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "catalog"
                      baseUri: "https://catalog.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: "GET"
                    - type: "http"
                      namespace: "archive"
                      baseUri: "https://archive.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 0
                      namespace: "tools"
                      tools:
                        list-items:
                          description: "List the items"
                """.formatted(IKANOS);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Capability(spec));

        assertTrue(e.getMessage().contains("MCP tool 'list-items'"), e.getMessage());
        assertTrue(e.getMessage().contains("'catalog'") && e.getMessage().contains("'archive'"),
                e.getMessage());
    }

    /** One consumed {@code get-item} operation and one MCP tool, without {@code call}. */
    private static Capability capability(int port, String toolName) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "catalog"
                      baseUri: "http://localhost:%d"
                      resources:
                        item:
                          path: "/items/{{item-id}}"
                          operations:
                            get-item:
                              method: "GET"
                              inputParameters:
                                format:
                                  in: "query"
                  exposes:
                    - type: "mcp"
                      port: 0
                      namespace: "tools"
                      tools:
                        %s:
                          description: "Get one item"
                          inputParameters:
                            item-id:
                              type: "string"
                              description: "Item identifier"
                          with:
                            format: "compact"
                          outputParameters:
                            - type: "object"
                              mapping: "$"
                              properties:
                                id:
                                  type: "string"
                                  mapping: "$.id"
                                name:
                                  type: "string"
                                  mapping: "$.name"
                """.formatted(IKANOS, port, toolName);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static JsonNode callTool(Capability capability, String tool, ObjectNode arguments) {
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

    /** An upstream that records the path and query it receives and returns one item. */
    private static Component createUpstream(int port, AtomicReference<String> received) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attachDefault(new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        String query = request.getResourceRef().getQuery();
                        received.set(request.getResourceRef().getPath()
                                + (query != null ? "?" + query : ""));
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(
                                "{\"id\":\"42\",\"name\":\"Widget\",\"internal\":\"secret\"}",
                                MediaType.APPLICATION_JSON);
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
