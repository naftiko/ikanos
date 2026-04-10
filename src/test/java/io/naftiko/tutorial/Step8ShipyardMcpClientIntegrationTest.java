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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.naftiko.Capability;
import io.naftiko.engine.consumes.ConsumesImportResolver;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.exposes.skill.SkillServerAdapter;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.exposes.SkillServerSpec;

/**
 * End-to-end integration test for {@code step-8-shipyard-skill-groups.yml}
 * exercised from an MCP client perspective.
 *
 * <p>Step 8 adds a Skill expose for grouped discovery while preserving the step-6
 * MCP tool surface and behavior.</p>
 */
public class Step8ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-8-shipyard-skill-groups.yml";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";
    private static final String TUTORIAL_DIR =
            "src/main/resources/tutorial";
    private static final String LEGACY_MOCK_URI =
            "https://mocks.naftiko.net/rest/naftiko-shipyard-legacy-dockyard-api/1.0.0-alpha1";

    @BeforeEach
    public void startServer() throws Exception {
        NaftikoSpec spec = loadSpec(CAPABILITY_FILE);

        String secretsAbsoluteUri = new File(SECRETS_FILE).getAbsoluteFile().toURI().toString();
        spec.getBinds().get(0).setLocation(secretsAbsoluteUri);
        spec.getBinds().get(1).setLocation(secretsAbsoluteUri);

        String tutorialAbsoluteDir = new File(TUTORIAL_DIR).getAbsolutePath();
        new ConsumesImportResolver().resolveImports(
                spec.getCapability().getConsumes(), tutorialAbsoluteDir);

        for (ClientSpec cs : spec.getCapability().getConsumes()) {
            if (cs instanceof HttpClientSpec && "legacy".equals(cs.getNamespace())) {
                ((HttpClientSpec) cs).setBaseUri(LEGACY_MOCK_URI);
            }
        }

        startServerFromSpec(spec);
    }

    @Test
    public void toolsListShouldAdvertiseFourTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(4, tools.size(), "step-8 MCP exposes exactly four tools");
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

    @Test
    public void skillCatalogShouldExposeFleetAndVoyageGroups() throws Exception {
        int skillPort = findFreePort();
        SkillServerAdapter skillAdapter = startIsolatedSkillServer(skillPort);

        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + skillPort + "/skills"))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, string());
            assertEquals(200, response.statusCode(), "GET /skills must succeed");

            JsonNode catalog = json.readTree(response.body());
            assertEquals(2, catalog.path("count").asInt(), "step-8 skill expose must declare 2 skill groups");

            List<String> names = new ArrayList<>();
            for (JsonNode s : catalog.path("skills")) {
                names.add(s.path("name").asText());
            }
            assertTrue(names.contains("fleet-ops"), "catalog must include fleet-ops");
            assertTrue(names.contains("voyage-ops"), "catalog must include voyage-ops");
        } finally {
            skillAdapter.stop();
        }
    }

    @Test
    public void fleetOpsSkillDetailShouldExposeDerivedMcpInvocationRefs() throws Exception {
        int skillPort = findFreePort();
        SkillServerAdapter skillAdapter = startIsolatedSkillServer(skillPort);

        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + skillPort + "/skills/fleet-ops"))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, string());
            assertEquals(200, response.statusCode(), "GET /skills/fleet-ops must succeed");

            JsonNode detail = json.readTree(response.body());
            assertEquals("fleet-ops", detail.path("name").asText());

            JsonNode tools = detail.path("tools");
            assertTrue(tools.isArray(), "fleet-ops detail must contain a tools array");
            assertEquals(3, tools.size(), "fleet-ops must expose 3 tools in step-8");

            for (JsonNode t : tools) {
                assertEquals("derived", t.path("type").asText(), "fleet-ops tools should be derived");
                JsonNode inv = t.path("invocationRef");
                assertEquals("shipyard-tools", inv.path("targetNamespace").asText());
                assertEquals("mcp", inv.path("mode").asText());
                assertFalse(inv.path("action").asText().isBlank(), "invocation action must be present");
            }
        } finally {
            skillAdapter.stop();
        }
    }

    private NaftikoSpec loadPatchedStep8Spec() throws Exception {
        NaftikoSpec spec = loadSpec(CAPABILITY_FILE);

        String secretsAbsoluteUri = new File(SECRETS_FILE).getAbsoluteFile().toURI().toString();
        spec.getBinds().get(0).setLocation(secretsAbsoluteUri);
        spec.getBinds().get(1).setLocation(secretsAbsoluteUri);

        String tutorialAbsoluteDir = new File(TUTORIAL_DIR).getAbsolutePath();
        new ConsumesImportResolver().resolveImports(
                spec.getCapability().getConsumes(), tutorialAbsoluteDir);

        for (ClientSpec cs : spec.getCapability().getConsumes()) {
            if (cs instanceof HttpClientSpec && "legacy".equals(cs.getNamespace())) {
                ((HttpClientSpec) cs).setBaseUri(LEGACY_MOCK_URI);
            }
        }

        return spec;
    }

    private SkillServerAdapter startIsolatedSkillServer(int skillPort) throws Exception {
        NaftikoSpec spec = loadPatchedStep8Spec();

        // Keep this server isolated from the MCP server started by the base class.
        spec.getCapability().getExposes().get(0).setPort(findFreePort());
        SkillServerSpec skillSpec = (SkillServerSpec) spec.getCapability().getExposes().get(1);
        skillSpec.setAddress("localhost");
        skillSpec.setPort(skillPort);

        Capability capability = new Capability(spec);
        ServerAdapter adapter = capability.getServerAdapters().stream()
                .filter(a -> a instanceof SkillServerAdapter)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SkillServerAdapter found"));

        SkillServerAdapter skillAdapter = (SkillServerAdapter) adapter;
        skillAdapter.start();
        return skillAdapter;
    }
}
