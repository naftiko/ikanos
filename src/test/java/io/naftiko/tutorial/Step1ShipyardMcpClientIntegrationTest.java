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
package io.naftiko.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * End-to-end integration test for {@code step-1-shipyard-first-capability.yml} exercised
 * from a remote MCP client perspective.
 *
 * <p>The test starts a real Jetty-backed MCP server loaded from the tutorial YAML, then
 * drives the full MCP Streamable HTTP protocol handshake — exactly as an external MCP
 * client (e.g. Claude Desktop, Cursor, or the MCP Inspector) would:</p>
 *
 * <ol>
 *   <li>{@code initialize} — establishes a session and negotiates protocol version</li>
 *   <li>{@code notifications/initialized} — client confirms readiness</li>
 *   <li>{@code tools/list} — discovers the available tools</li>
 *   <li>{@code tools/call} — invokes {@code list-ships}, which makes a real outbound HTTP
 *       call to {@code https://mocks.naftiko.net/…} and returns mapped results</li>
 * </ol>
 *
 * <p>The server is bound to an ephemeral port so the test never conflicts with other
 * processes. The tutorial capability file is used as-is; only {@code address} and
 * {@code port} on the single {@code exposes} entry are overridden before construction.</p>
 */
public class Step1ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-1-shipyard-first-capability.yml";

    @BeforeEach
    public void startServer() throws Exception {
        startServerFromSpec(loadSpec(CAPABILITY_FILE));
    }

    // ── MCP protocol handshake ───────────────────────────────────────────────

    @Test
    public void initializeShouldNegotiateProtocolVersionAndReturnSessionId() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25",
                           "clientInfo":{"name":"test-client","version":"1.0"},
                           "capabilities":{}}}
                """;

        HttpResponse<String> response = http.send(buildPost(initBody), string());

        assertEquals(200, response.statusCode());

        JsonNode result = json.readTree(response.body()).path("result");
        assertEquals("2025-11-25", result.path("protocolVersion").asText(),
                "Server must confirm the requested protocol version");
        assertEquals("shipyard-tools", result.path("serverInfo").path("name").asText(),
                "Server name must match the capability namespace");
        assertNotNull(result.path("capabilities").path("tools"),
                "Capabilities block must advertise tools");

        assertTrue(response.headers().firstValue("Mcp-Session-Id").isPresent(),
                "Server must return a session ID on initialize");
    }

    @Test
    public void toolsListShouldAdvertiseListShipsTool() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        HttpResponse<String> response = http.send(
                buildPost("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """, sessionId),
                string());

        assertEquals(200, response.statusCode());

        JsonNode tools = json.readTree(response.body()).path("result").path("tools");
        assertTrue(tools.isArray(), "tools must be an array");
        assertEquals(1, tools.size(), "step-1 exposes exactly one tool");
        assertEquals("list-ships", tools.get(0).path("name").asText(),
                "Tool name must be 'list-ships'");
        assertEquals("List ships in the shipyard",
                tools.get(0).path("description").asText(),
                "Tool description must match the YAML");
    }

    // ── Tool call — hits real mocks.naftiko.net ──────────────────────────────

    @Test
    public void listShipsToolCallShouldReturnMappedShipArray() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        HttpResponse<String> response = http.send(
                buildPost("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call",
                         "params":{"name":"list-ships","arguments":{}}}
                        """, sessionId),
                string());

        assertEquals(200, response.statusCode());

        JsonNode envelope = json.readTree(response.body());
        JsonNode callResult = envelope.path("result");

        assertFalse(callResult.path("isError").asBoolean(false),
                "list-ships must not return an error. Raw response: " + envelope.toPrettyString());

        JsonNode content = callResult.path("content");
        assertTrue(content.isArray() && !content.isEmpty(),
                "result.content must be a non-empty array");

        // The tool returns a JSON array serialised as a text content item
        JsonNode ships = json.readTree(content.get(0).path("text").asText());
        assertTrue(ships.isArray(), "Parsed content must be a JSON array");
        assertTrue(ships.size() > 0, "Ship list must not be empty");

        // Validate the output-parameter mapping declared in the YAML
        JsonNode first = ships.get(0);
        assertTrue(first.has("imo"),    "Each ship must have 'imo' (mapped from imo_number)");
        assertTrue(first.has("name"),   "Each ship must have 'name' (mapped from vessel_name)");
        assertTrue(first.has("type"),   "Each ship must have 'type' (mapped from vessel_type)");
        assertTrue(first.has("flag"),   "Each ship must have 'flag' (mapped from flag_code)");
        assertTrue(first.has("status"), "Each ship must have 'status' (mapped from operational_status)");
        assertFalse(first.path("imo").asText().isBlank(),   "imo must be non-blank");
        assertFalse(first.path("name").asText().isBlank(),  "name must be non-blank");
    }

}
