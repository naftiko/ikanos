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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.http.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import io.naftiko.engine.consumes.ConsumesImportResolver;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;

/**
 * End-to-end integration test for {@code step-6-shipyard-write-operations.yml}
 * exercised from a remote MCP client perspective.
 *
 * <p>Step 6 extends step 5 with the first write operation tool: {@code create-voyage}.
 * The capability still imports registry and legacy consumes files, and still relies on
 * bind injection for both auth schemes.</p>
 */
public class Step6ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-6-shipyard-write-operations.yml";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";
    private static final String TUTORIAL_DIR =
            "src/main/resources/tutorial";
        private static final String REGISTRY_MOCK_URI =
            "https://mocks.naftiko.net/rest/naftiko-shipyard-maritime-registry-api/1.0.0-alpha1";
    private static final String LEGACY_MOCK_URI =
            "https://mocks.naftiko.net/rest/naftiko-shipyard-legacy-dockyard-api/1.0.0-alpha1";

    @BeforeEach
    public void startServer() throws Exception {
        NaftikoSpec spec = loadSpec(CAPABILITY_FILE);
        useMcpServerToken(SECRETS_FILE);

        String secretsAbsoluteUri = new File(SECRETS_FILE).getAbsoluteFile().toURI().toString();
        spec.getBinds().get(0).setLocation(secretsAbsoluteUri);
        spec.getBinds().get(1).setLocation(secretsAbsoluteUri);

        String tutorialAbsoluteDir = new File(TUTORIAL_DIR).getAbsolutePath();
        new ConsumesImportResolver().resolveImports(
                spec.getCapability().getConsumes(), tutorialAbsoluteDir);

        for (ClientSpec cs : spec.getCapability().getConsumes()) {
            if (cs instanceof HttpClientSpec) {
                if ("registry".equals(cs.getNamespace())) {
                    ((HttpClientSpec) cs).setBaseUri(REGISTRY_MOCK_URI);
                } else if ("legacy".equals(cs.getNamespace())) {
                    ((HttpClientSpec) cs).setBaseUri(LEGACY_MOCK_URI);
                }
            }
        }

        startServerFromSpec(spec);
    }

    @Test
    public void toolsListShouldAdvertiseFourTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(4, tools.size(), "step-6 exposes exactly four tools");
        assertEquals("list-ships",          tools.get(0).path("name").asText());
        assertEquals("get-ship",            tools.get(1).path("name").asText());
        assertEquals("list-legacy-vessels", tools.get(2).path("name").asText());
        assertEquals("create-voyage",       tools.get(3).path("name").asText());
    }

    @Test
    public void createVoyageInputSchemaShouldDeclareRequiredFields() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode createVoyage = callToolsList(http, sessionId).get(3);

        JsonNode props = createVoyage.path("inputSchema").path("properties");
        assertTrue(props.has("shipImo"), "create-voyage inputSchema must declare 'shipImo'");
        assertTrue(props.has("departurePort"), "create-voyage inputSchema must declare 'departurePort'");
        assertTrue(props.has("arrivalPort"), "create-voyage inputSchema must declare 'arrivalPort'");
        assertTrue(props.has("departureDate"), "create-voyage inputSchema must declare 'departureDate'");
        assertTrue(props.has("arrivalDate"), "create-voyage inputSchema must declare 'arrivalDate'");
        assertTrue(props.has("crewIds"), "create-voyage inputSchema must declare 'crewIds'");
        assertTrue(props.has("cargoIds"), "create-voyage inputSchema must declare 'cargoIds'");
        assertEquals("array", props.path("crewIds").path("type").asText());
        assertEquals("array", props.path("cargoIds").path("type").asText());

        JsonNode required = createVoyage.path("inputSchema").path("required");
        boolean shipImoRequired = false;
        boolean departurePortRequired = false;
        boolean arrivalPortRequired = false;
        boolean departureDateRequired = false;
        boolean arrivalDateRequired = false;
        boolean crewIdsRequired = false;
        boolean cargoIdsRequired = false;

        for (JsonNode r : required) {
            String val = r.asText();
            if ("shipImo".equals(val)) {
                shipImoRequired = true;
            } else if ("departurePort".equals(val)) {
                departurePortRequired = true;
            } else if ("arrivalPort".equals(val)) {
                arrivalPortRequired = true;
            } else if ("departureDate".equals(val)) {
                departureDateRequired = true;
            } else if ("arrivalDate".equals(val)) {
                arrivalDateRequired = true;
            } else if ("crewIds".equals(val)) {
                crewIdsRequired = true;
            } else if ("cargoIds".equals(val)) {
                cargoIdsRequired = true;
            }
        }

        assertTrue(shipImoRequired, "'shipImo' must appear in required");
        assertTrue(departurePortRequired, "'departurePort' must appear in required");
        assertTrue(arrivalPortRequired, "'arrivalPort' must appear in required");
        assertTrue(departureDateRequired, "'departureDate' must appear in required");
        assertTrue(arrivalDateRequired, "'arrivalDate' must appear in required");
        assertTrue(crewIdsRequired, "'crewIds' must appear in required");
        assertFalse(cargoIdsRequired, "'cargoIds' must not appear in required");
    }

    @Test
    public void createVoyageShouldReturnMappedVoyageObject() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode voyage = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"create-voyage","arguments":{
                   "shipImo":"IMO-9321483",
                   "departurePort":"Oslo",
                   "arrivalPort":"Singapore",
                   "departureDate":"2026-04-10",
                   "arrivalDate":"2026-05-02",
                   "crewIds":["CREW-001","CREW-003"],
                   "cargoIds":["CARGO-2024-0451"]
                 }}}
                """);

        assertTrue(voyage.isObject(), "create-voyage must return a JSON object");
        assertFalse(voyage.path("voyageId").asText().isBlank(), "voyageId must not be blank");
        assertEquals("IMO-9321483", voyage.path("shipImo").asText(), "shipImo must match request");

        JsonNode route = voyage.path("route");
        assertTrue(route.isObject(), "route must be an object");
        assertEquals("Oslo", route.path("from").asText(), "route.from must map from departurePort");
        assertEquals("Singapore", route.path("to").asText(), "route.to must map from arrivalPort");

        JsonNode dates = voyage.path("dates");
        assertTrue(dates.isObject(), "dates must be an object");
        assertEquals("2026-04-10", dates.path("departure").asText(), "dates.departure must map");
        assertEquals("2026-05-02", dates.path("arrival").asText(), "dates.arrival must map");

        assertTrue(voyage.path("crewIds").isArray(), "crewIds must be an array");
        assertTrue(voyage.path("crewIds").size() > 0, "crewIds must not be empty");
        assertTrue(voyage.path("cargoIds").isArray(), "cargoIds must be an array");
        assertFalse(voyage.path("status").asText().isBlank(), "status must not be blank");
    }
}
