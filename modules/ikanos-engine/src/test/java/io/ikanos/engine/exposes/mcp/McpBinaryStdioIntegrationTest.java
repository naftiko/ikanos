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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Phase 6 of the binary-content blueprint (capability-binary-content.md §11.3): proves that the
 * stdio MCP transport round-trips a 1&nbsp;MiB JPEG without corruption.
 *
 * <p>The bytes flow upstream HTTP &rarr; {@code ToolHandler} (base64) &rarr; {@code CallToolResult}
 * &rarr; JSON-RPC frame written to the stdio {@code OutputStream}. We then decode the base64 from
 * the framed response and assert byte-for-byte equality with the original payload.</p>
 */
public class McpBinaryStdioIntegrationTest {

    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void stdioShouldRoundTripOneMiBJpegWithoutCorruption() throws Exception {
        // A 1 MiB payload with a JPEG SOI marker; content is opaque to the transport.
        byte[] jpeg = new byte[1024 * 1024];
        new Random(42).nextBytes(jpeg);
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[jpeg.length - 2] = (byte) 0xFF;
        jpeg[jpeg.length - 1] = (byte) 0xD9;

        int port = findFreePort();
        Component upstream = createBinaryServer(port, jpeg, MediaType.IMAGE_JPEG);
        upstream.start();

        try {
            Capability capability = capabilityFromYaml(binaryStdioCapabilityYaml(port));
            McpServerAdapter adapter =
                    (McpServerAdapter) capability.getServerAdapters().get(0);
            assertTrue(adapter.getMcpServerSpec().isStdio(),
                    "capability must use the stdio transport");

            ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);

            String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"},"
                    + "\"capabilities\":{}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"get-photo\",\"arguments\":{\"id\":\"p-1\"}}}\n";

            ByteArrayInputStream in =
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StdioJsonRpcHandler handler = new StdioJsonRpcHandler(dispatcher, in, out);
            handler.run();

            String output = out.toString(StandardCharsets.UTF_8);
            String[] lines = output.strip().split("\\n");
            // initialize + tools/call (the notification produces no response line).
            assertEquals(2, lines.length, "expected initialize + tools/call responses");

            ObjectMapper mapper = new ObjectMapper();
            JsonNode callResponse = mapper.readTree(lines[1]);
            assertEquals(2, callResponse.path("id").asInt());

            JsonNode result = callResponse.path("result");
            assertEquals(Boolean.FALSE, result.path("isError").asBoolean(),
                    "tool call should succeed: " + callResponse);
            JsonNode content = result.path("content").get(0);
            assertEquals("image", content.path("type").asText());
            assertEquals("image/jpeg", content.path("mimeType").asText());

            byte[] decoded = Base64.getDecoder().decode(content.path("data").asText());
            assertEquals(jpeg.length, decoded.length, "round-tripped length must match");
            assertArrayEquals(jpeg, decoded, "round-tripped bytes must match exactly");
        } finally {
            upstream.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private String binaryStdioCapabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: photos
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      photoBytes:
                        path: /photos/binary
                        operations:
                          download:
                            method: GET
                            outputRawFormat: binary
                            outputMediaType: "image/jpeg"
                  exposes:
                  - type: mcp
                    transport: stdio
                    namespace: photos
                    tools:
                      get-photo:
                        description: Get photo bytes
                        call: photos.download
                        inputParameters:
                          id:
                            type: string
                            description: Photo id
                            required: true
                """.formatted(schemaVersion, port);
    }

    private static Component createBinaryServer(int port, byte[] bytes, MediaType mediaType) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/photos/binary", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new ByteArrayRepresentation(bytes, mediaType));
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
