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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

/**
 * Unit tests for the Restlet-based MCP Streamable HTTP transport (protocol revision
 * {@code 2026-07-28}).
 *
 * <p>Validates POST dispatch, GET/DELETE rejection (protocol-level sessions and the GET stream
 * endpoint were removed in this revision), empty body handling, malformed JSON handling, and the
 * new mandatory request-metadata headers ({@code MCP-Protocol-Version}, {@code Mcp-Method},
 * {@code Mcp-Name}) through actual HTTP calls.</p>
 */
class McpServerResourceTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void getShouldReturn405WithNotSupportedMessage() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(405, response.statusCode());
                assertEquals("GET not supported", response.body());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void deleteShouldReturn405RegardlessOfSessionHeader() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> withoutHeader = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(405, withoutHeader.statusCode());

                HttpResponse<String> withStaleSessionHeader = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                                .header("Mcp-Session-Id", "some-legacy-session-id")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(405, withStaleSessionHeader.statusCode(),
                        "A stale Mcp-Session-Id header from a pre-2026-07-28 client must be ignored");
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void postWithEmptyBodyShouldReturnParseError() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(""))
                                .header("Content-Type", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals(-32700, body.path("error").path("code").asInt());
                assertTrue(body.path("error").path("message").asText().contains("empty body"));
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void postWithMalformedJsonShouldReturnParseError() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString("{"))
                                .header("Content-Type", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals(-32700, body.path("error").path("code").asInt());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void postShouldReturnMethodNotFoundForUnknownRpcMethod() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"unknown/method\"}"))
                                .header("Content-Type", "application/json")
                                .header("Mcp-Method", "unknown/method")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(404, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals(-32601, body.path("error").path("code").asInt());
                assertFalse(body.path("error").path("message").asText().isBlank());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void postShouldReturnHeaderMismatchWhenMcpMethodHeaderIsMissing() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{}}"))
                                .header("Content-Type", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals(-32020, body.path("error").path("code").asInt());
                assertTrue(body.path("error").path("message").asText().contains("Mcp-Method"));
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void postShouldReturnInvalidRequestForBadJsonRpcVersion() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\","
                                                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                                                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}"))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "tools/list")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals(-32600, body.path("error").path("code").asInt());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void serverDiscoverShouldSucceedWithValidHeaders() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                                                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                                                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}"))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "server/discover")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertFalse(response.headers().firstValue("Mcp-Session-Id").isPresent(),
                        "This revision no longer mints session IDs");

                JsonNode body = JSON.readTree(response.body());
                assertEquals("2.0", body.path("jsonrpc").asText());
                assertEquals(1, body.path("id").asInt());

                JsonNode supportedVersions = body.path("result").path("supportedVersions");
                assertTrue(supportedVersions.isArray() && !supportedVersions.isEmpty());
                assertEquals(ProtocolDispatcher.MCP_PROTOCOL_VERSION, supportedVersions.get(0).asText());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void toolsCallShouldSucceedWithValidHeaders() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                                                + "\"params\":{\"name\":\"query-database\",\"arguments\":{},"
                                                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                                                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}"))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "tools/call")
                                .header("Mcp-Name", "=?base64?cXVlcnktZGF0YWJhc2U=?=")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertNotNull(body.path("result"));
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void toolsCallShouldReturnHeaderMismatchWhenMcpNameHeaderDoesNotMatchBody() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = baseUrlFor(adapter);

            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl))
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                                                + "\"params\":{\"name\":\"query-database\",\"arguments\":{},"
                                                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                                                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}"))
                                .header("Content-Type", "application/json")
                                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                                .header("Mcp-Method", "tools/call")
                                .header("Mcp-Name", "does-not-match")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                assertEquals(-32020, body.path("error").path("code").asInt());
            } finally {
                adapter.stop();
            }
        }
    }

    private static String baseUrlFor(McpServerAdapter adapter) {
        return "http://" + adapter.getMcpServerSpec().getAddress() + ":"
                + adapter.getMcpServerSpec().getPort() + "/";
    }

    private static McpServerAdapter startAdapterOnFreePort() throws Exception {
        String resourcePath = "src/test/resources/mcp/mcp-capability.yaml";
        IkanosSpec spec = YAML.readValue(new File(resourcePath), IkanosSpec.class);
        McpServerSpec mcpServerSpec = (McpServerSpec) spec.getCapability().getExposes().getFirst();
        mcpServerSpec.setPort(findFreePort());
        mcpServerSpec.setAddress("127.0.0.1");

        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().getFirst();
        adapter.start();
        return adapter;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
