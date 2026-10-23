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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integration test: a consumed {@code baseUri} built from a binding reaches the bound host, a tool
 * argument with the same name cannot redirect the call, and an unbound placeholder fails the
 * capability at load time (#745).
 */
class HttpClientBaseUriBindingIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();
    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @TempDir
    Path tempDir;

    @Test
    void toolCallShouldUseBaseUriFromBindingAndIgnoreSameNamedArgument() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        int port = findFreePort();
        Component upstream = createUpstream(port, hits);
        upstream.start();
        try {
            Path binds = tempDir.resolve("env.yaml");
            Files.writeString(binds, "LAGO_API_URL: \"http://localhost:" + port + "/api/v1\"\n");
            Capability capability = load(capabilityYaml(binds.toUri().toString(),
                    "\"{{LAGO_API_URL}}\""));

            ObjectNode arguments = MAPPER.createObjectNode();
            arguments.put("LAGO_API_URL", "http://169.254.169.254/latest");
            JsonNode result = callTool(capability, "list-plans", arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            assertEquals(1, hits.get(), "the bound upstream must receive the call");
        } finally {
            upstream.stop();
        }
    }

    @Test
    void capabilityShouldFailAtLoadWhenBaseUriPlaceholderIsNotBound() throws Exception {
        Path binds = tempDir.resolve("env.yaml");
        Files.writeString(binds, "LAGO_API_URL: \"https://api.getlago.com/api/v1\"\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(capabilityYaml(binds.toUri().toString(),
                        "\"https://{{LAGO_HOST}}/api/v1\"")));
        assertTrue(error.getMessage().contains("LAGO_HOST"), error.getMessage());
    }

    private String capabilityYaml(String bindLocation, String baseUri) {
        return """
                ikanos: "%s"
                binds:
                  - namespace: "lago-env"
                    location: "%s"
                    keys:
                      LAGO_API_URL: "LAGO_API_URL"
                capability:
                  consumes:
                    - namespace: lago
                      type: http
                      baseUri: %s
                      resources:
                        plans:
                          path: "/plans"
                          operations:
                            list-plans:
                              method: GET
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: lago-tools
                      tools:
                        list-plans:
                          description: "List plans"
                          inputParameters:
                            LAGO_API_URL:
                              type: string
                              description: "Unused; must not change the host"
                              required: false
                          call: lago.list-plans
                """.formatted(schemaVersion, bindLocation, baseUri);
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

    private static Component createUpstream(int port, AtomicInteger hits) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/api/v1/plans", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        hits.incrementAndGet();
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"plans\":[]}", MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static Capability load(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
