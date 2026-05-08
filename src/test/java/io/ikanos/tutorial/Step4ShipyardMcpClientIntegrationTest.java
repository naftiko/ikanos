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

import io.ikanos.spec.IkanosSpec;

/**
 * End-to-end integration test for {@code step-4-shipyard-output-shaping.yml} exercised
 * from a remote MCP client perspective.
 *
 * <p>Step 4 builds directly on step 3 (bearer auth + binds) and introduces the key new
 * mechanic: <strong>nested output shaping</strong>. The {@code get-ship} tool now returns
 * a {@code specs} sub-object whose fields are pulled from deep JSONPath expressions in the
 * API response:</p>
 *
 * <pre>
 * specs:
 *   yearBuilt  ← $.year_built
 *   tonnage    ← $.gross_tonnage
 *   length     ← $.dimensions.length_overall   (two-level path)
 * </pre>
 *
 * <p>The test harness applies two runtime patches before server startup: it rewrites the
 * shared secrets bind to an absolute file URI and overrides the consumes {@code baseUri}
 * to the live mock registry endpoint used by this integration test.</p>
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Tool surface unchanged (2 tools, same input schemas as step 3)</li>
 *   <li>{@code list-ships} still returns its 5 flat fields</li>
 *   <li>{@code get-ship} returns the 5 flat fields <em>and</em> a well-formed {@code specs}
 *       object with {@code yearBuilt}, {@code tonnage}, and {@code length}</li>
 * </ul>
 */
public class Step4ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

        private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-4-shipyard-output-shaping.yml";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";

    @BeforeEach
    public void startServer() throws Exception {
        IkanosSpec spec = loadSpec(CAPABILITY_FILE);
        useMcpServerToken(SECRETS_FILE);


        startServerFromSpec(spec);
    }

    // ── tools/list ───────────────────────────────────────────────────────────

    @Test
    public void toolsListShouldAdvertiseBothTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(2, tools.size(), "step-4 exposes exactly two tools");
        assertEquals("list-ships", tools.get(0).path("name").asText());
        assertEquals("get-ship",   tools.get(1).path("name").asText());
    }

    @Test
    public void listShipsInputSchemaShouldDeclareOptionalStatusParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode listShips = callToolsList(http, sessionId).get(0);

        JsonNode props = listShips.path("inputSchema").path("properties");
        assertTrue(props.has("status"), "list-ships inputSchema must declare 'status'");
        assertEquals("string", props.path("status").path("type").asText());

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

        JsonNode getShip = callToolsList(http, sessionId).get(1);

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

    // ── list-ships — flat fields unchanged ────────────────────────────────────

    @Test
    public void listShipsShouldReturnMappedFlatFields() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode ships = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"list-ships","arguments":{}}}
                """);

        assertTrue(ships.isArray() && ships.size() > 0, "list-ships must return a non-empty array");

        JsonNode first = ships.get(0);
        assertTrue(first.has("imo"),    "Must have 'imo'");
        assertTrue(first.has("name"),   "Must have 'name'");
        assertTrue(first.has("type"),   "Must have 'type'");
        assertTrue(first.has("flag"),   "Must have 'flag'");
        assertTrue(first.has("status"), "Must have 'status'");
        assertFalse(first.path("imo").asText().isBlank(),  "imo must not be blank");
        assertFalse(first.path("name").asText().isBlank(), "name must not be blank");
    }

    // ── get-ship — nested specs object (the step-4 feature) ──────────────────

    @Test
    public void getShipShouldReturnFlatFieldsAndNestedSpecsObject() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode ship = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"get-ship","arguments":{"imo":"IMO-9321483"}}}
                """);

        assertTrue(ship.isObject(), "get-ship must return a JSON object");

        // Flat fields — unchanged from step 2/3
        assertEquals("IMO-9321483", ship.path("imo").asText(),
                "Returned imo must match the requested IMO number");
        assertFalse(ship.path("name").asText().isBlank(),   "name must not be blank");
        assertFalse(ship.path("type").asText().isBlank(),   "type must not be blank");
        assertFalse(ship.path("flag").asText().isBlank(),   "flag must not be blank");
        assertFalse(ship.path("status").asText().isBlank(), "status must not be blank");

        // Nested specs — the step-4 addition, mapped from deep JSONPath
        assertTrue(ship.has("specs"), "get-ship must include the nested 'specs' object");
        JsonNode specs = ship.path("specs");
        assertTrue(specs.isObject(), "'specs' must be a JSON object");

        // yearBuilt: $.year_built
        assertTrue(specs.has("yearBuilt"), "specs must have 'yearBuilt' (mapped from $.year_built)");
        assertTrue(specs.path("yearBuilt").isNumber(), "'yearBuilt' must be a number");

        // tonnage: $.gross_tonnage
        assertTrue(specs.has("tonnage"), "specs must have 'tonnage' (mapped from $.gross_tonnage)");
        assertTrue(specs.path("tonnage").isNumber(), "'tonnage' must be a number");

        // length: $.dimensions.length_overall  (two-level deep JSONPath)
        assertTrue(specs.has("length"), "specs must have 'length' (mapped from $.dimensions.length_overall)");
        assertTrue(specs.path("length").isNumber(), "'length' must be a number");
    }
}
