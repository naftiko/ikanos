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
package io.naftiko.engine.exposes.mcp;

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
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;

class JettyStreamableHandlerTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void httpTransportShouldHandleGetDeleteAndPostScenarios() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = "http://" + adapter.getMcpServerSpec().getAddress() + ":"
                + adapter.getMcpServerSpec().getPort() + "/";

        try {
            HttpResponse<String> getResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, getResponse.statusCode());
            assertEquals("GET not supported", getResponse.body());

            HttpResponse<String> emptyBodyResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(""))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, emptyBodyResponse.statusCode());
            JsonNode emptyBodyError = JSON.readTree(emptyBodyResponse.body());
            assertEquals(-32700, emptyBodyError.path("error").path("code").asInt());

            HttpResponse<String> parseErrorResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString("{"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, parseErrorResponse.statusCode());
            JsonNode parseError = JSON.readTree(parseErrorResponse.body());
            assertEquals(-32700, parseError.path("error").path("code").asInt());

            HttpResponse<String> initializeResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initializeResponse.statusCode());
            String sessionId = initializeResponse.headers().firstValue("Mcp-Session-Id")
                    .orElse(null);
            assertNotNull(sessionId);
            JsonNode initializeJson = JSON.readTree(initializeResponse.body());
            assertEquals("2.0", initializeJson.path("jsonrpc").asText());
            assertEquals(1, initializeJson.path("id").asInt());

            HttpResponse<String> initializedNotification = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, initializedNotification.statusCode());
            assertTrue(initializedNotification.body() == null
                    || initializedNotification.body().isBlank());

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
    void postShouldReturnMethodNotFoundForUnknownRpcMethod() throws Exception {
        McpServerAdapter adapter = startAdapterOnFreePort();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = "http://" + adapter.getMcpServerSpec().getAddress() + ":"
                + adapter.getMcpServerSpec().getPort() + "/";

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

    private static McpServerAdapter startAdapterOnFreePort() throws Exception {
        String resourcePath = "src/test/resources/mcp/mcp-capability.yaml";
        NaftikoSpec spec = YAML.readValue(new File(resourcePath), NaftikoSpec.class);
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