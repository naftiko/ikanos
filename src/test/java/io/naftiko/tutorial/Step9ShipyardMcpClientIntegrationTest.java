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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.naftiko.Capability;
import io.naftiko.engine.ConsumesImportResolver;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.exposes.skill.SkillServerAdapter;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.spec.exposes.SkillServerSpec;

/**
 * End-to-end integration test for {@code step-9-shipyard-rest-adapter.yml}
 * exercised from REST client perspective.
 *
 * <p>Step 9 adds a REST expose for non-agent consumers (dashboards, mobile apps)
 * while preserving MCP tools and Skill groups from prior steps.</p>
 */
public class Step9ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-9-shipyard-rest-adapter.yml";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";
    private static final String TUTORIAL_DIR =
            "src/main/resources/tutorial";
    private static final String LEGACY_MOCK_URI =
            "https://mocks.naftiko.net/rest/naftiko-shipyard-legacy-dockyard-api/1.0.0-alpha1";

    private int restPort;
    private List<ServerAdapter> allAdapters = new ArrayList<>();

    @AfterEach
    public void stopAllServers() throws Exception {
        for (ServerAdapter a : allAdapters) {
            try {
                a.stop();
            } catch (Exception e) {
                // ignore
            }
        }
    }

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

        // Allocate separate ports for each adapter to avoid conflicts
        restPort = findFreePort();
        int mcpPort = findFreePort();
        int skillPort = findFreePort();
        
        spec.getCapability().getExposes().get(0).setPort(mcpPort);
        spec.getCapability().getExposes().get(0).setAddress("localhost");
        
        RestServerSpec restSpec = (RestServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof RestServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("REST expose not found in step-9 spec"));
        restSpec.setPort(restPort);
        restSpec.setAddress("localhost");

        SkillServerSpec skillSpec = (SkillServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof SkillServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Skill expose not found in step-9 spec"));
        skillSpec.setPort(skillPort);
        skillSpec.setAddress("localhost");

        // Start all adapters
        Capability capability = new Capability(spec);
        allAdapters.addAll(capability.getServerAdapters());
        for (ServerAdapter a : allAdapters) {
            a.start();
        }
        
        // Set serverUrl for MCP tests
        serverUrl = "http://localhost:" + mcpPort;
    }

    @Test
    public void restListShipsShouldReturnShipsArray() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + restPort + "/ships"))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, string());
        assertEquals(200, response.statusCode(), "GET /ships must succeed");

        JsonNode ships = json.readTree(response.body());
        assertTrue(ships.isArray(), "GET /ships must return an array");
        assertTrue(ships.size() > 0, "Ships array must not be empty");

        JsonNode firstShip = ships.get(0);
        assertTrue(firstShip.has("imo"), "Ship must have imo field");
        assertTrue(firstShip.has("name"), "Ship must have name field");
        assertTrue(firstShip.has("type"), "Ship must have type field");
        assertTrue(firstShip.has("flag"), "Ship must have flag field");
        assertTrue(firstShip.has("status"), "Ship must have status field");
    }

    @Test
    public void restCreateVoyageShouldReturnMappedVoyageObject() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        String voyagePayload = """
                {
                  "shipImo": "IMO-9321483",
                  "departurePort": "Oslo",
                  "arrivalPort": "Singapore",
                  "departureDate": "2026-04-10",
                  "arrivalDate": "2026-05-02",
                  "crewIds": ["CREW-001", "CREW-003"],
                  "cargoIds": ["CARGO-2024-0451"]
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + restPort + "/voyages"))
                .POST(HttpRequest.BodyPublishers.ofString(voyagePayload))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = http.send(request, string());
        assertEquals(201, response.statusCode(), "POST /voyages must return 201 Created");

        JsonNode voyage = json.readTree(response.body());
        assertTrue(voyage.isObject(), "POST /voyages must return a JSON object");
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
            assertEquals(2, catalog.path("count").asInt(), "step-9 skill expose must declare 2 skill groups");

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
            assertEquals(3, tools.size(), "fleet-ops must expose 3 tools in step-9");

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

    private NaftikoSpec loadPatchedStep9Spec() throws Exception {
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
        NaftikoSpec spec = loadPatchedStep9Spec();

        // Keep other adapters on different ports to avoid conflicts
        spec.getCapability().getExposes().get(0).setPort(findFreePort());
        
        RestServerSpec restSpec = (RestServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof RestServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("REST expose not found in step-9 spec"));
        restSpec.setPort(findFreePort());
        
        SkillServerSpec skillSpec = (SkillServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof SkillServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Skill expose not found in step-9 spec"));
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
