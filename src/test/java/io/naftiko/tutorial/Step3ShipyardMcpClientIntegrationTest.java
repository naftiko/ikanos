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

import java.io.File;
import java.net.http.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.HttpClientSpec;

/**
 * End-to-end integration test for {@code step-3-shipyard-auth-and-binds.yml} exercised
 * from a remote MCP client perspective.
 *
 * <p>Step 3 introduces three new mechanics on top of step 2:</p>
 * <ul>
 *   <li><strong>Binds</strong> — {@code REGISTRY_TOKEN} and {@code REGISTRY_VERSION} are
 *       loaded from {@code shared/secrets.yaml} at startup via the root-level {@code binds}
 *       block.</li>
 *   <li><strong>Bearer authentication</strong> — every outbound request to the registry
 *       carries {@code Authorization: Bearer <token>} resolved from the bind, so the full
 *       authenticated data surface is available.</li>
 *   <li><strong>Consumes-level version header</strong> — every outbound request carries
 *       {@code Registry-Version} resolved from the bind; this is applied at the consumes
 *       level so it is automatic across all operations.</li>
 * </ul>
 *
 * <p>Tool signatures are identical to step 2 ({@code list-ships} + {@code get-ship}, same
 * optional {@code status}, same required {@code imo}). Tests verify that the full auth
 * pipeline is operational: binds resolve → token injected → mock responds correctly.</p>
 *
 * <p>Two overrides are applied before the Jetty server starts:</p>
 * <ul>
 *   <li>The bind {@code location} is set to an absolute file URI pointing to the
 *       test-local {@code secrets.yaml}, avoiding the relative {@code file:///./shared/…}
 *       resolution against the Maven working directory.</li>
 *   <li>The consumes {@code baseUri} is redirected from the tutorial's
 *       {@code http://localhost:8080} placeholder to the real
 *       {@code https://mocks.naftiko.net/…} mock endpoint.</li>
 * </ul>
 */
public class Step3ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-3-shipyard-auth-and-binds.yml";
    private static final String MOCK_BASE_URI =
            "https://mocks.naftiko.net/rest/naftiko-shipyard-maritime-registry-api/1.0.0-alpha1";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";

    @BeforeEach
    public void startServer() throws Exception {
        NaftikoSpec spec = loadSpec(CAPABILITY_FILE);

        // Override the bind location to an absolute file URI so the BindingResolver finds it
        // regardless of the Maven working directory (tutorial uses file:///./shared/secrets.yaml
        // which would resolve relative to the project root, not the tutorial folder)
        String secretsAbsoluteUri = new File(SECRETS_FILE).getAbsoluteFile().toURI().toString();
        spec.getBinds().get(0).setLocation(secretsAbsoluteUri);

        // Override the upstream baseUri (tutorial targets http://localhost:8080 as a local mock;
        // tests hit the real mock server at mocks.naftiko.net)
        HttpClientSpec registrySpec = (HttpClientSpec) spec.getCapability().getConsumes().get(0);
        registrySpec.setBaseUri(MOCK_BASE_URI);

        startServerFromSpec(spec);
    }

    // ── initialize — proves binds resolved and auth pipeline is live ─────────

    @Test
    public void initializeShouldSucceedWithBindsAndAuth() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        String sessionId = initialize(http);

        assertNotNull(sessionId, "Server must issue a session ID after initialize");
        assertFalse(sessionId.isBlank(), "Session ID must not be blank");
    }

    // ── tools/list ───────────────────────────────────────────────────────────

    @Test
    public void toolsListShouldAdvertiseBothTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(2, tools.size(), "step-3 exposes exactly two tools");
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

        // status is optional — must not appear in the required array
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

    // ── list-ships — outbound call with bearer auth + version header ──────────

    @Test
    public void listShipsWithBearerAuthShouldReturnMappedShipArray() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode ships = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"list-ships","arguments":{}}}
                """);

        assertTrue(ships.isArray(), "list-ships must return an array");
        assertTrue(ships.size() > 0, "Ship list must not be empty");

        // Verify the output mapping is correctly applied (imo_number → imo, vessel_name → name …)
        JsonNode first = ships.get(0);
        assertTrue(first.has("imo"),    "Must have 'imo'");
        assertTrue(first.has("name"),   "Must have 'name'");
        assertTrue(first.has("type"),   "Must have 'type'");
        assertTrue(first.has("flag"),   "Must have 'flag'");
        assertTrue(first.has("status"), "Must have 'status'");
        assertFalse(first.path("imo").asText().isBlank(),  "imo must not be blank");
        assertFalse(first.path("name").asText().isBlank(), "name must not be blank");
    }

    // ── get-ship — with-resolved path param + bearer auth ────────────────────

    @Test
    public void getShipWithBearerAuthShouldResolveMustacheAndReturnSingleShip() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

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
        assertEquals("IMO-9321483", ship.path("imo").asText(),
                "Returned imo must match the requested IMO number");
    }

}
