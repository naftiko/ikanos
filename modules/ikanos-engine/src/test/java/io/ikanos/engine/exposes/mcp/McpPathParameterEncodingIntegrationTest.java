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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test proving that a caller-supplied path parameter stays one path segment of the
 * upstream request (YAML, then Capability, then MCP tools/call, then HTTP), so that it cannot
 * redirect a tool to another endpoint of the upstream API (GHSA-rvg4-2rxw-mc64).
 *
 * <p>The upstream is a raw socket that records the request line exactly as sent: an HTTP server
 * framework would normalize {@code ..} segments before the test could see them.</p>
 */
public class McpPathParameterEncodingIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldKeepDotSegmentsInsidePathParameter() throws Exception {
        String requestLine = callTrashPage("x/../../databases/DB-ID");

        assertEquals("PATCH /v1/pages/x%2F..%2F..%2Fdatabases%2FDB-ID HTTP/1.1", requestLine,
                "A page_id must not turn 'trash a page' into 'trash a database'");
    }

    @Test
    public void handleToolCallShouldKeepQueryDelimiterInsidePathParameter() throws Exception {
        String requestLine = callTrashPage("U?archived=true");

        assertEquals("PATCH /v1/pages/U%3Farchived=true HTTP/1.1", requestLine,
                "A page_id must not add a query string to the upstream request");
    }

    private String callTrashPage(String pageId) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            CompletableFuture<String> requestLine =
                    CompletableFuture.supplyAsync(() -> acceptOneRequest(upstream));
            Capability capability = capabilityFromYaml(capabilityYaml(upstream.getLocalPort()));

            JsonNode result = callTool(capability, "trash-page", pageId);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return requestLine.get(10, TimeUnit.SECONDS);
        }
    }

    private static String acceptOneRequest(ServerSocket server) {
        try (Socket socket = server.accept()) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            String header;
            long contentLength = 0;
            boolean chunked = false;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(header.substring(15).trim());
                } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                }
            }
            if (chunked) {
                String size;
                while ((size = reader.readLine()) != null && Integer.parseInt(size.trim(), 16) > 0) {
                    reader.skip(Integer.parseInt(size.trim(), 16));
                    reader.readLine();
                }
                reader.readLine();
            } else {
                reader.skip(contentLength);
            }
            String body = "{\"object\":\"page\",\"id\":\"p1\"}";
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return requestLine;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private JsonNode callTool(Capability capability, String tool, String pageId) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.putObject("arguments").put("page_id", pageId);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: notion
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      resources:
                        page:
                          path: "/pages/{{page_id}}"
                          operations:
                            trash-page:
                              method: PATCH
                              body:
                                type: json
                                data: '{"in_trash":true}'
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: notion-tools
                      tools:
                        trash-page:
                          description: "Move a page to the trash"
                          inputParameters:
                            page_id:
                              type: string
                              description: "ID of the page"
                          call: notion.trash-page
                          hints:
                            destructive: true
                """.formatted(schemaVersion, port);
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }
}
