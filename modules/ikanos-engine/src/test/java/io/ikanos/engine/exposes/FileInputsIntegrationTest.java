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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * File inputs ({@code type: file}, experimental) end to end, over real HTTP: one aggregate flow
 * takes a file, exposed as an MCP tool (the file inline in the draft MCP files extension's file
 * object) and as a REST operation (the file as a {@code multipart/form-data} part), and sends it
 * to a consumed API as a {@code multipart/form-data} part or as a binary body.
 */
class FileInputsIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String FILES = "io.modelcontextprotocol/files";
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, (byte) 0xFF,
            '\r', '\n', (byte) 0x80};

    private final List<Received> received = new ArrayList<>();
    private Capability capability;
    private Component upstream;
    private HttpClient http;
    private String mcpUrl;
    private String restUrl;
    private int nextId = 1;

    private record Received(String path, MediaType type, byte[] body) {
    }

    @BeforeEach
    void start() throws Exception {
        int upstreamPort = freePort();
        upstream = upstream(upstreamPort);
        upstream.start();

        int mcpPort = freePort();
        int restPort = freePort();
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  aggregates:
                  - namespace: documents
                    flows:
                      import-report:
                        description: Import a lab report into the records system.
                        inputParameters:
                          report:
                            type: file
                            accept: ["application/pdf", ".csv"]
                            maxSize: 1KiB
                            required: true
                            description: The report to import.
                          label:
                            type: string
                            description: A label for the report.
                        call: records.upload
                      store-raw:
                        description: Store the raw bytes of a report.
                        inputParameters:
                          report:
                            type: file
                            required: true
                            description: The report to store.
                        call: records.store
                  consumes:
                  - namespace: records
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      reports:
                        path: /reports
                        operations:
                          upload:
                            method: POST
                            body:
                              type: multipartForm
                              data:
                              - name: label
                                value: "{{label}}"
                              - name: file
                                value: "{{report.data}}"
                                encoding: base64
                                filename: "{{report.name}}"
                                contentType: "{{report.mimeType}}"
                      raw:
                        path: /raw
                        operations:
                          store:
                            method: PUT
                            body:
                              type: binary
                              data: "{{report.data}}"
                              contentType: "{{report.mimeType}}"
                  exposes:
                  - type: mcp
                    address: 127.0.0.1
                    port: %d
                    namespace: documents-mcp
                    files:
                      uploads:
                        maxInlineBytes: 2KiB
                    tools:
                      import-report:
                        description: Import a lab report into the records system.
                        ref: documents.import-report
                      store-raw:
                        description: Store the raw bytes of a report.
                        ref: documents.store-raw
                      ping:
                        description: Check that the server answers.
                        outputParameters:
                          status:
                            type: string
                            value: pong
                  - type: rest
                    address: 127.0.0.1
                    port: %d
                    namespace: documents-api
                    resources:
                      reports:
                        path: /reports
                        operations:
                          import:
                            method: POST
                            ref: documents.import-report
                """.formatted(VersionHelper.getSchemaVersion(), upstreamPort, mcpPort, restPort),
                IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
        http = HttpClient.newHttpClient();
        mcpUrl = "http://127.0.0.1:" + mcpPort + "/";
        restUrl = "http://127.0.0.1:" + restPort + "/reports";
    }

    @AfterEach
    void stop() throws Exception {
        http.close();
        capability.stop();
        upstream.stop();
    }

    // ── MCP ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void serverDiscoverShouldAdvertiseTheInlineLimitButNotUpload() throws Exception {
        JsonNode files = rpc("server/discover", null, JSON.createObjectNode(), false)
                .path("capabilities").path("extensions").path(FILES);

        assertEquals(2048, files.path("maxInlineBytes").asInt(), files.toString());
        assertFalse(files.has("upload"), files.toString());
    }

    @Test
    void toolsListShouldHideToolsThatTakeAFileFromAClientWithoutTheExtension()
            throws Exception {
        JsonNode tools = rpc("tools/list", null, JSON.createObjectNode(), false).path("tools");

        assertEquals(List.of("ping"), names(tools));
    }

    @Test
    void toolsListShouldMarkTheFileArgumentForAClientThatDeclaresTheExtension()
            throws Exception {
        JsonNode tools = rpc("tools/list", null, JSON.createObjectNode(), true).path("tools");

        assertEquals(List.of("import-report", "store-raw", "ping"), names(tools));
        JsonNode report = tools.get(0).path("inputSchema").path("properties").path("report");
        assertEquals("object", report.path("type").asString());
        assertEquals(JSON.readTree("{\"accept\":[\"application/pdf\",\".csv\"],\"maxSize\":1024}"),
                report.path("x-mcp-file"));
        assertEquals(JSON.readTree("[\"id\",\"mimeType\",\"size\"]"), report.path("required"));
        assertEquals("string", tools.get(0).path("inputSchema").path("properties").path("label")
                .path("type").asString());
    }

    @Test
    void inlineFileShouldReachTheConsumedApiAsAMultipartPart() throws Exception {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", fileArgument("file-1", "q3.pdf", "application/pdf", PDF));
        arguments.put("label", "Q3");

        JsonNode result = callTool("import-report", arguments);

        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertMultipartUpload(single());
    }

    @Test
    void inlineFileShouldReachTheConsumedApiAsABinaryBody() throws Exception {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", fileArgument("file-1", "q3.pdf", "application/pdf", PDF));

        JsonNode result = callTool("store-raw", arguments);

        assertFalse(result.path("isError").asBoolean(), result.toString());
        Received raw = single();
        assertEquals("/raw", raw.path());
        assertTrue(MediaType.APPLICATION_PDF.equals(raw.type(), true), String.valueOf(raw.type()));
        assertArrayEquals(PDF, raw.body());
    }

    @Test
    void inlineFileWithAMatchingDigestShouldBeAccepted() throws Exception {
        ObjectNode file = fileArgument("file-1", "q3.pdf", "application/pdf", PDF);
        file.putObject("digest").put("algorithm", "sha-256")
                .put("value", FileInputs.digestOf(PDF));
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", file);

        JsonNode result = callTool("store-raw", arguments);

        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertArrayEquals(PDF, single().body());
    }

    @Test
    void fileOfATypeTheInputDoesNotAcceptShouldBeAnErrorWithoutCallingTheApi()
            throws Exception {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", fileArgument("file-1", "logo.png", "image/png", PDF));

        JsonNode result = callTool("import-report", arguments);

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(received.isEmpty(), "The tool must not run");
    }

    @Test
    void fileWithoutInlineDataShouldBeAnErrorWithoutCallingTheApi() throws Exception {
        ObjectNode file = fileArgument("file-1", "q3.pdf", "application/pdf", PDF);
        file.remove("data");
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", file);

        JsonNode result = callTool("import-report", arguments);

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(result.path("content").get(0).path("text").asString().contains("inline"),
                result.toString());
        assertTrue(received.isEmpty(), "The tool must not run");
    }

    @Test
    void inlineFilesOverTheLimitShouldBeAnErrorWithoutCallingTheApi() throws Exception {
        byte[] big = new byte[3000];
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set("report", fileArgument("file-1", "big.bin", "application/octet-stream",
                big));

        JsonNode result = callTool("store-raw", arguments);

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(received.isEmpty(), "The tool must not run");
    }

    // ── REST ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void multipartUploadShouldReachTheConsumedApiAsAMultipartPart() throws Exception {
        HttpResponse<String> response = postMultipart("q3.pdf", "application/pdf", PDF);

        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        assertMultipartUpload(single());
    }

    @Test
    void multipartUploadOfATypeTheInputDoesNotAcceptShouldBeRefused() throws Exception {
        HttpResponse<String> response = postMultipart("logo.png", "image/png", PDF);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(received.isEmpty(), "The flow must not run");
    }

    @Test
    void multipartUploadLargerThanMaxSizeShouldBeRefused() throws Exception {
        HttpResponse<String> response = postMultipart("big.pdf", "application/pdf",
                new byte[2048]);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(received.isEmpty(), "The flow must not run");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private void assertMultipartUpload(Received upload) {
        assertEquals("/reports", upload.path());
        assertTrue(MediaType.MULTIPART_FORM_DATA.equals(upload.type(), true),
                String.valueOf(upload.type()));
        String boundary = upload.type().getParameters().getFirstValue("boundary");
        byte[] expected = concat(
                ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                        + "Q3\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"q3.pdf\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8),
                PDF,
                ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, upload.body(),
                "Got:\n" + new String(upload.body(), StandardCharsets.ISO_8859_1));
    }

    private Received single() {
        assertEquals(1, received.size(), "Expected one upstream call");
        return received.get(0);
    }

    private static ObjectNode fileArgument(String id, String name, String mimeType,
            byte[] bytes) {
        ObjectNode file = JSON.createObjectNode();
        file.put("id", id);
        file.put("name", name);
        file.put("mimeType", mimeType);
        file.put("size", bytes.length);
        file.put("data", Base64.getEncoder().encodeToString(bytes));
        return file;
    }

    private static List<String> names(JsonNode tools) {
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asString()));
        return names;
    }

    private JsonNode callTool(String name, ObjectNode arguments) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return rpc("tools/call", name, params, true);
    }

    private JsonNode rpc(String method, String name, ObjectNode params, boolean files)
            throws Exception {
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        if (files) {
            // An inline-only client declares the extension with empty settings
            meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
                    .putObject(FILES);
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", nextId++);
        body.put("method", method);
        body.set("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(mcpUrl))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = JSON.readTree(response.body()).path("result");
        assertFalse(result.isMissingNode(), response.body());
        return result;
    }

    private HttpResponse<String> postMultipart(String fileName, String mimeType, byte[] bytes)
            throws Exception {
        String boundary = "client-boundary-7f3a";
        byte[] body = concat(
                ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                        + "Q3\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"report\"; filename=\""
                        + fileName + "\"\r\n"
                        + "Content-Type: " + mimeType + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8),
                bytes,
                ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(restUrl))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Component upstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                Restlet recorder = new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            java.io.InputStream in = request.getEntity() != null
                                    ? request.getEntity().getStream() : null;
                            byte[] bytes = in != null ? in.readAllBytes() : new byte[0];
                            synchronized (received) {
                                received.add(new Received(
                                        request.getResourceRef().getPath(),
                                        request.getEntity() != null
                                                ? request.getEntity().getMediaType() : null,
                                        bytes));
                            }
                        } catch (Exception e) {
                            response.setStatus(Status.SERVER_ERROR_INTERNAL);
                            return;
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new StringRepresentation("{\"stored\": true}",
                                MediaType.APPLICATION_JSON));
                    }
                };
                router.attach("/reports", recorder);
                router.attach("/raw", recorder);
                return router;
            }
        });
        return component;
    }

    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
