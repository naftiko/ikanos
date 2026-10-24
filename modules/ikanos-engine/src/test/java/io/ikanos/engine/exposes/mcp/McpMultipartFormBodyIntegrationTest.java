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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * Integration test proving that an MCP tool backed by a consumed operation with a
 * {@code multipartForm} body sends a real {@code multipart/form-data} payload to the upstream API
 * (YAML, then Capability, then MCP tools/call, then HTTP), as file upload APIs require (#857).
 */
public class McpMultipartFormBodyIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSendMultipartFormDataMediaTypeWithBoundary() throws Exception {
        Recorded recorded = callUpload("aGVsbG8=", "x");

        assertTrue(MediaType.MULTIPART_FORM_DATA.equals(recorded.type.get(), true),
                "The upstream must receive multipart/form-data, got " + recorded.type.get());
        assertTrue(recorded.type.get().getParameters().getFirstValue("boundary") != null,
                "The media type must carry the boundary");
    }

    @Test
    public void handleToolCallShouldSendTextAndDecodedFileParts() throws Exception {
        byte[] file = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF};
        Recorded recorded = callUpload(Base64.getEncoder().encodeToString(file), "Logo");

        String boundary = recorded.type.get().getParameters().getFirstValue("boundary");
        byte[] expected = concat(
                ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8),
                file,
                ("\r\n--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"caption\"\r\n\r\n"
                        + "Logo\r\n"
                        + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, recorded.body.get(),
                "Expected one decoded file part and one text part, got:\n"
                        + new String(recorded.body.get(), StandardCharsets.ISO_8859_1));
    }

    private Recorded callUpload(String content, String caption) throws Exception {
        Recorded recorded = new Recorded();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, recorded);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));
            ObjectNode arguments = MAPPER.createObjectNode();
            arguments.put("upload_id", "u1");
            arguments.put("content", content);
            arguments.put("caption", caption);

            JsonNode result = callTool(capability, "send-file", arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return recorded;
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
                    - namespace: files
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        send:
                          path: "/file_uploads/{{upload_id}}/send"
                          operations:
                            send-file:
                              method: POST
                              body:
                                type: multipartForm
                                data:
                                  - name: "file"
                                    value: "{{content}}"
                                    filename: "logo.png"
                                    contentType: "image/png"
                                    encoding: "base64"
                                  - name: "caption"
                                    value: "{{caption}}"
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: file-tools
                      tools:
                        send-file:
                          description: "Upload a file"
                          inputParameters:
                            upload_id:
                              type: string
                              description: "Upload ID"
                            content:
                              type: string
                              description: "File content, base64-encoded"
                            caption:
                              type: string
                              description: "Caption"
                          call: files.send-file
                """.formatted(schemaVersion, port);
    }

    private static Component createRecordingUpstream(int port, Recorded recorded) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/file_uploads/u1/send", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            recorded.type.set(request.getEntity().getMediaType());
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            request.getEntity().write(out);
                            recorded.body.set(out.toByteArray());
                        } catch (java.io.IOException e) {
                            recorded.body.set(new byte[0]);
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"id\":\"u1\",\"status\":\"uploaded\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
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

    private static final class Recorded {
        final AtomicReference<byte[]> body = new AtomicReference<>();
        final AtomicReference<MediaType> type = new AtomicReference<>();
    }
}
