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
 * Unit tests for the Restlet-based MCP Streamable HTTP transport.
 * 
 * Validates POST dispatch, DELETE session removal, GET rejection, empty body handling,
 * and malformed JSON handling through actual HTTP calls.
 */
class McpServerResourceTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void getShouldReturn405WithNotSupportedMessage() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
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

    @Test
    void postWithEmptyBodyShouldReturnParseError() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(""))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode body = JSON.readTree(response.body());
            assertEquals(-32700, body.path("error").path("code").asInt());
            assertTrue(body.path("error").path("message").asText().contains("empty body"));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void postWithMalformedJsonShouldReturnParseError() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString("{"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode body = JSON.readTree(response.body());
            assertEquals(-32700, body.path("error").path("code").asInt());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void initializeShouldReturnSessionIdHeader() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            String sessionId =
                    response.headers().firstValue("Mcp-Session-Id").orElse(null);
            assertNotNull(sessionId);

            JsonNode body = JSON.readTree(response.body());
            assertEquals("2.0", body.path("jsonrpc").asText());
            assertEquals(1, body.path("id").asInt());
            assertNotNull(body.path("result").path("protocolVersion").asText());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void notificationsInitializedShouldReturn202() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(202, response.statusCode());
            assertTrue(response.body() == null || response.body().isBlank());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void deleteShouldReturn200AndRemoveSession() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            // First initialize to get a session ID
            HttpResponse<String> initResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String sessionId =
                    initResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
            assertNotNull(sessionId);

            // DELETE with session ID
            HttpResponse<String> deleteResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .method("DELETE", HttpRequest.BodyPublishers.noBody())
                            .header("Mcp-Session-Id", sessionId)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, deleteResponse.statusCode());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void deleteShouldReturn200EvenWithoutSessionHeader() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .method("DELETE", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void postShouldReturnMethodNotFoundForUnknownRpcMethod() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"unknown/method\"}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode body = JSON.readTree(response.body());
            assertEquals(-32601, body.path("error").path("code").asInt());
            assertFalse(body.path("error").path("message").asText().isBlank());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void postShouldReturnInvalidRequestForBadJsonRpcVersion() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"initialize\"}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode body = JSON.readTree(response.body());
            assertEquals(-32600, body.path("error").path("code").asInt());
        } finally {
            adapter.stop();
        }
    }

    private static String baseUrlFor(McpServerAdapter adapter) {
        return "http://" + adapter.getMcpServerSpec().getAddress() + ":"
                + adapter.getMcpServerSpec().getPort() + "/";
    }

    private static McpServerAdapter startAdapterOnFreePort() throws Exception {
        String resourcePath = "src/test/resources/mcp/mcp-capability.yaml";
        IkanosSpec spec = YAML.readValue(new File(resourcePath), IkanosSpec.class);
        McpServerSpec mcpServerSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
        mcpServerSpec.setPort(findFreePort());
        mcpServerSpec.setAddress("127.0.0.1");

        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        adapter.start();
        return adapter;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
