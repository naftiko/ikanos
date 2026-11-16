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
package io.ikanos.engine.exposes.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.routing.Router;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call meta-tool keeps the image, audio and resource blocks of the tools it calls in its own
 * {@code content}, so a model gets an image as an image, as a direct call returns it.
 */
class CallToolBinaryContentTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};

    private Component upstream;
    private McpServerAdapter adapter;

    @BeforeEach
    void startUpstream() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, port);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/charts/render", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new ByteArrayRepresentation(PNG, MediaType.IMAGE_PNG));
                    }
                });
                return router;
            }
        });
        upstream.start();

        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: charts
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      chart:
                        path: /charts/render
                        operations:
                          render:
                            method: GET
                            outputRawFormat: binary
                            outputMediaType: "image/png"
                  exposes:
                  - type: mcp
                    transport: stdio
                    namespace: charts-mcp
                    tools:
                      render-chart:
                        description: Render a chart.
                        hints:
                          readOnly: true
                        call: charts.render
                """.formatted(VersionHelper.getSchemaVersion(), port), IkanosSpec.class);
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().getFirst();
    }

    @AfterEach
    void stopUpstream() throws Exception {
        upstream.stop();
    }

    @Test
    void callToolShouldKeepTheImageBlockOfACalledTool() {
        ToolsCallHandler handler = new ToolsCallHandler(adapter, List.of(), List.of(), List.of());
        ObjectNode arguments = JSON.createObjectNode();
        arguments.putArray("calls").addObject().put("name", "render-chart");

        JsonNode result = handler.callTool(adapter.getToolSearchCatalog(), arguments);

        assertFalse(result.path("isError").asBoolean(), result.toString());
        JsonNode content = result.path("content");
        assertEquals("text", content.get(0).path("type").asString());
        assertEquals("image", content.get(1).path("type").asString(), content.toString());
        assertEquals("image/png", content.get(1).path("mimeType").asString());
    }
}
