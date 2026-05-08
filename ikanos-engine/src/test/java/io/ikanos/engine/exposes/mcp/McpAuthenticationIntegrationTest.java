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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration tests for MCP server authentication. Validates end-to-end HTTP behavior with bearer
 * and API key authentication through actual HTTP calls against a running MCP server.
 */
class McpAuthenticationIntegrationTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void bearerAuthShouldRejectRequestWithoutToken() throws Exception {
        McpServerAdapter adapter = startBearerProtectedServer();
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

            assertEquals(401, response.statusCode(),
                    "Request without bearer token should be rejected");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void bearerAuthShouldRejectRequestWithWrongToken() throws Exception {
        McpServerAdapter adapter = startBearerProtectedServer();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer wrong-token")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode(),
                    "Request with wrong bearer token should be rejected");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void bearerAuthShouldAcceptRequestWithCorrectToken() throws Exception {
        McpServerAdapter adapter = startBearerProtectedServer();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer mcp-secret-token-123")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(),
                    "Request with correct bearer token should be accepted");

            JsonNode body = JSON.readTree(response.body());
            assertNotNull(body.path("result").path("protocolVersion").asText(),
                    "Initialize response should contain protocolVersion");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void apiKeyAuthShouldRejectRequestWithoutKey() throws Exception {
        McpServerAdapter adapter = startApiKeyProtectedServer();
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

            assertEquals(401, response.statusCode(),
                    "Request without API key should be rejected");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void apiKeyAuthShouldAcceptRequestWithCorrectKey() throws Exception {
        McpServerAdapter adapter = startApiKeyProtectedServer();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                            .header("Content-Type", "application/json")
                            .header("X-MCP-Key", "abc-key-456")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(),
                    "Request with correct API key should be accepted");

            JsonNode body = JSON.readTree(response.body());
            assertNotNull(body.path("result").path("protocolVersion").asText(),
                    "Initialize response should contain protocolVersion");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void bearerAuthShouldProtectAllMethods() throws Exception {
        McpServerAdapter adapter = startBearerProtectedServer();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = baseUrlFor(adapter);

        try {
            // GET without token should be rejected
            HttpResponse<String> getResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, getResponse.statusCode(),
                    "GET without token should be rejected");

            // DELETE without token should be rejected
            HttpResponse<String> deleteResponse = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl))
                            .method("DELETE", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, deleteResponse.statusCode(),
                    "DELETE without token should be rejected");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void noAuthShouldAllowAllRequests() throws Exception {
        McpServerAdapter adapter = startUnprotectedServer();
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

            assertEquals(200, response.statusCode(),
                    "Unprotected server should accept all requests");
        } finally {
            adapter.stop();
        }
    }

    private McpServerAdapter startBearerProtectedServer() throws Exception {
        String schemaVersion = VersionHelper.getSchemaVersion();
        String yaml = """
                ikanos: "%s"
                info:
                  label: "MCP Auth Test"
                  description: "Bearer auth integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "auth-test-mcp"
                      description: "Test MCP server with bearer auth"
                      authentication:
                        type: "bearer"
                        token: "mcp-secret-token-123"
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                  consumes: []
                """.formatted(schemaVersion, findFreePort());

        return startAdapter(yaml);
    }

    private McpServerAdapter startApiKeyProtectedServer() throws Exception {
        String schemaVersion = VersionHelper.getSchemaVersion();
        String yaml = """
                ikanos: "%s"
                info:
                  label: "MCP Auth Test"
                  description: "API key auth integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "auth-test-mcp"
                      description: "Test MCP server with API key auth"
                      authentication:
                        type: "apikey"
                        key: "X-MCP-Key"
                        value: "abc-key-456"
                        placement: "header"
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                  consumes: []
                """.formatted(schemaVersion, findFreePort());

        return startAdapter(yaml);
    }

    private McpServerAdapter startUnprotectedServer() throws Exception {
        String schemaVersion = VersionHelper.getSchemaVersion();
        String yaml = """
                ikanos: "%s"
                info:
                  label: "MCP Auth Test"
                  description: "No auth integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "auth-test-mcp"
                      description: "Test MCP server without auth"
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          outputParameters:
                            - type: "string"
                              value: "ok"
                  consumes: []
                """.formatted(schemaVersion, findFreePort());

        return startAdapter(yaml);
    }

    private McpServerAdapter startAdapter(String yaml) throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        adapter.start();
        return adapter;
    }

    private static String baseUrlFor(McpServerAdapter adapter) {
        return "http://" + adapter.getMcpServerSpec().getAddress() + ":"
                + adapter.getMcpServerSpec().getPort() + "/";
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
