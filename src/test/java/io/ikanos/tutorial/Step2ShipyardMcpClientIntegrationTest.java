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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;



/**
 * End-to-end integration test for {@code step-2-shipyard-wiring.yml} exercised
 * from a remote MCP client perspective.
 *
 * <p>Step 2 introduces two new mechanics on top of step 1:</p>
 * <ul>
 *   <li><strong>Optional query parameter</strong> — {@code list-ships} accepts an optional
 *       {@code status} input that becomes a {@code ?status=…} query string on the upstream
 *       call, letting the agent filter the ship list server-side.</li>
 *   <li><strong>{@code with}-resolved path parameter</strong> — {@code get-ship} accepts
 *       an {@code imo} input, maps it to {@code imo_number} via
 *       {@code with: {imo_number: "{{imo}}"}}, and substitutes it into the path template
 *       {@code /ships/{{imo_number}}} to call {@code GET /ships/{imo}}.</li>
 * </ul>
 *
 * <p>The same full MCP Streamable HTTP handshake used in
 * {@link Step1ShipyardMcpClientIntegrationTest} is applied here: initialize → tools/list →
 * tools/call. The Jetty server is loaded directly from the tutorial YAML with only
 * {@code address} and {@code port} overridden to bind on an ephemeral localhost port.</p>
 *
 * <p>All {@code tools/call} invocations hit the real
 * {@code https://mocks.ikanos.net/…} mock endpoint.</p>
 */
public class Step2ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-2-shipyard-wiring.yml";

    @BeforeEach
    public void startServer() throws Exception {
        startServerFromSpec(loadSpec(CAPABILITY_FILE));
    }

    // ── tools/list ───────────────────────────────────────────────────────────

    @Test
    public void toolsListShouldAdvertiseBothTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(2, tools.size(), "step-2 exposes exactly two tools");

        // Preserve order: list-ships first, get-ship second
        assertEquals("list-ships", tools.get(0).path("name").asText());
        assertEquals("get-ship",   tools.get(1).path("name").asText());
    }

    @Test
    public void listShipsInputSchemaShouldDeclareOptionalStatusParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);
        JsonNode listShips = tools.get(0);

        JsonNode props = listShips.path("inputSchema").path("properties");
        assertTrue(props.has("status"), "list-ships inputSchema must declare 'status'");
        assertEquals("string", props.path("status").path("type").asText());

        // status is optional — required array must not contain it
        JsonNode required = listShips.path("inputSchema").path("required");
        boolean statusRequired = false;
        for (JsonNode r : required) {
            if ("status".equals(r.asText())) {
                statusRequired = true;
                break;
            }
        }
        assertFalse(statusRequired, "'status' must not appear in the required array");
    }

    @Test
    public void getShipInputSchemaShouldDeclareRequiredImoParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);
        JsonNode getShip = tools.get(1);

        JsonNode props = getShip.path("inputSchema").path("properties");
        assertTrue(props.has("imo"), "get-ship inputSchema must declare 'imo'");
        assertEquals("string", props.path("imo").path("type").asText());

        JsonNode required = getShip.path("inputSchema").path("required");
        boolean imoRequired = false;
        for (JsonNode r : required) {
            if ("imo".equals(r.asText())) {
                imoRequired = true;
                break;
            }
        }
        assertTrue(imoRequired, "'imo' must appear in the required array");
    }

    // ── list-ships — real outbound call to mocks.ikanos.net ─────────────────

    @Test
    public void listShipsWithoutFilterShouldReturnMappedShipArray() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode ships = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"list-ships","arguments":{}}}
                """);

        assertTrue(ships.isArray(), "list-ships must return an array");
        assertTrue(ships.size() > 0, "Ship list must not be empty");

        JsonNode first = ships.get(0);
        assertTrue(first.has("imo"),    "Must have 'imo'");
        assertTrue(first.has("name"),   "Must have 'name'");
        assertTrue(first.has("type"),   "Must have 'type'");
        assertTrue(first.has("flag"),   "Must have 'flag'");
        assertTrue(first.has("status"), "Must have 'status'");
        assertFalse(first.path("imo").asText().isBlank(),  "imo must not be blank");
        assertFalse(first.path("name").asText().isBlank(), "name must not be blank");
    }

    @Test
    public void listShipsFilteredByStatusShouldReturnOnlyMatchingShips() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode ships = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"list-ships","arguments":{"status":"active"}}}
                """);

        assertTrue(ships.isArray(), "list-ships must return an array");
        assertTrue(ships.size() > 0, "Filtered list must not be empty");

        // Every ship returned by the mock must have status=active
        for (JsonNode ship : ships) {
            assertEquals("active", ship.path("status").asText(),
                    "All ships in an active-filtered response must have status 'active'");
        }
    }

    // ── get-ship — with-resolved path parameter ───────────────────────────────

    @Test
    public void getShipShouldResolveMustacheWithAndReturnSingleShip() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        // The 'with' mapping resolves imo → imo_number, which fills /ships/{{imo_number}}
        JsonNode ship = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"get-ship","arguments":{"imo":"IMO-9321483"}}}
                """);

        assertTrue(ship.isObject(), "get-ship must return a JSON object");
        assertTrue(ship.has("imo"),    "Must have 'imo'");
        assertTrue(ship.has("name"),   "Must have 'name'");
        assertTrue(ship.has("type"),   "Must have 'type'");
        assertTrue(ship.has("flag"),   "Must have 'flag'");
        assertTrue(ship.has("status"), "Must have 'status'");

        // The mock returns the record for the requested IMO
        assertEquals("IMO-9321483", ship.path("imo").asText(),
                "Returned imo must match the requested IMO number");
    }

}
