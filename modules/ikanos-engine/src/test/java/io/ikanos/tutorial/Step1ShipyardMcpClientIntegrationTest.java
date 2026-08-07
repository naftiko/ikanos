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
package io.ikanos.tutorial;

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
 * End-to-end integration test for {@code step-1-shipyard-mock.yml} exercised
 * from a remote MCP client perspective.
 *
 * <p>The test starts a real Jetty-backed MCP server loaded from the tutorial YAML, then
 * drives the MCP Streamable HTTP protocol (revision 2026-07-28) — exactly as an external MCP
 * client (e.g. Claude Desktop, Cursor, or the MCP Inspector) would:</p>
 *
 * <ol>
 *   <li>{@code server/discover} — negotiates the protocol version and discovers capabilities
 *       (this revision is stateless: there is no {@code initialize} handshake or session)</li>
 *   <li>{@code tools/list} — discovers the available tools</li>
 *   <li>{@code tools/call} — invokes {@code get-ship}, which returns a single mock ship
 *       object with mapped output fields</li>
 * </ol>
 *
 * <p>The server is bound to an ephemeral port so the test never conflicts with other
 * processes. The tutorial capability file is used as-is; only {@code address} and
 * {@code port} on the single {@code exposes} entry are overridden before construction.</p>
 */
public class Step1ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/test/resources/tutorial/step-1-shipyard-mock.yml";

    @BeforeEach
    public void startServer() throws Exception {
        startServerFromSpec(loadSpec(CAPABILITY_FILE));
    }

    // ── MCP protocol: server/discover ────────────────────────────────────────

    @Test
    public void serverDiscoverShouldAdvertiseSupportedProtocolVersionAndCapabilities() throws Exception {
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {

            String discoverBody = """
                    {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}
                    """;

            response = http.send(buildPost(discoverBody), string());
        }

        assertEquals(200, response.statusCode());

        JsonNode result = json.readTree(response.body()).path("result");
        JsonNode supportedVersions = result.path("supportedVersions");
        assertTrue(supportedVersions.isArray() && !supportedVersions.isEmpty(),
                "Server must advertise its supported protocol versions");
        assertNotNull(result.path("capabilities").path("tools"),
                "Capabilities block must advertise tools");
    }

    @Test
    public void toolsListShouldAdvertiseGetShipTool() throws Exception {
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(
                    buildPost("""
                            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                            """),
                    string());
        }

        assertEquals(200, response.statusCode());

        JsonNode tools = json.readTree(response.body()).path("result").path("tools");
        assertTrue(tools.isArray(), "tools must be an array");
        assertEquals(1, tools.size(), "step-1 exposes exactly one tool");
        assertEquals("get-ship", tools.get(0).path("name").asText(),
                "Tool name must be 'get-ship'");
        assertEquals("Retrieve a ship's details by IMO number",
                tools.get(0).path("description").asText(),
                "Tool description must match the YAML");
    }

    // ── Tool call — hits real mocks.ikanos.net ──────────────────────────────

    @Test
    public void getShipToolCallShouldReturnMappedShipObject() throws Exception {
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(
                    buildPost("""
                            {"jsonrpc":"2.0","id":3,"method":"tools/call",
                             "params":{"name":"get-ship","arguments":{"imo_number":"IMO-9321483"}}}
                            """),
                    string());
        }

        assertEquals(200, response.statusCode());

        JsonNode envelope = json.readTree(response.body());
        JsonNode callResult = envelope.path("result");

        assertFalse(callResult.path("isError").asBoolean(false),
                "get-ship must not return an error. Raw response: " + envelope.toPrettyString());

        JsonNode content = callResult.path("content");
        assertTrue(content.isArray() && !content.isEmpty(),
                "result.content must be a non-empty array");

        JsonNode ship = json.readTree(content.get(0).path("text").asText());
        assertTrue(ship.isObject(), "Parsed content must be a JSON object");
        assertEquals("IMO-9321483", ship.path("imo").asText());
        assertEquals("Northern Star", ship.path("name").asText());
        assertEquals("cargo", ship.path("type").asText());
        assertEquals("NO", ship.path("flag").asText());
        assertEquals("active", ship.path("status").asText());
    }

}
