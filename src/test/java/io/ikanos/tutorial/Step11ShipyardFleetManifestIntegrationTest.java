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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.skill.SkillServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.skill.SkillServerSpec;

/**
 * End-to-end integration test for {@code step-11-shipyard-fleet-manifest.yml}
 * exercised from MCP and Skill client perspectives.
 *
 * <p>Step 11 adds orchestrated multi-step tools ({@code get-ship-with-crew},
 * {@code get-voyage-manifest}) that chain API calls and server-side lookups to
 * assemble a full fleet manifest experience.</p>
 */
public class Step11ShipyardFleetManifestIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-11-shipyard-fleet-manifest.yml";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";

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
        IkanosSpec spec = loadSpec(CAPABILITY_FILE);
        useMcpServerToken(SECRETS_FILE);
        disableMcpAuthentication(spec);

        restPort = findFreePort();
        int mcpPort = findFreePort();
        int skillPort = findFreePort();

        spec.getCapability().getExposes().get(0).setPort(mcpPort);
        spec.getCapability().getExposes().get(0).setAddress("localhost");

        RestServerSpec restSpec = (RestServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof RestServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("REST expose not found in step-11 spec"));
        restSpec.setPort(restPort);
        restSpec.setAddress("localhost");

        SkillServerSpec skillSpec = (SkillServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof SkillServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Skill expose not found in step-11 spec"));
        skillSpec.setPort(skillPort);
        skillSpec.setAddress("localhost");

        Capability capability = new Capability(spec);
        allAdapters.addAll(capability.getServerAdapters());
        for (ServerAdapter a : allAdapters) {
            a.start();
        }

        serverUrl = "http://localhost:" + mcpPort;
    }

    @Test
    public void toolsListShouldAdvertiseSixTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(6, tools.size(), "step-11 MCP exposes exactly six tools");
        assertEquals("list-ships", tools.get(0).path("name").asText());
        assertEquals("get-ship", tools.get(1).path("name").asText());
        assertEquals("list-legacy-vessels", tools.get(2).path("name").asText());
        assertEquals("create-voyage", tools.get(3).path("name").asText());
        assertEquals("get-ship-with-crew", tools.get(4).path("name").asText());
        assertEquals("get-voyage-manifest", tools.get(5).path("name").asText());
    }

    @Test
    public void getShipWithCrewInputSchemaShouldDeclareImoParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode getShipWithCrew = callToolsList(http, sessionId).get(4);

        JsonNode props = getShipWithCrew.path("inputSchema").path("properties");
        assertTrue(props.has("imo"), "get-ship-with-crew must declare imo parameter");
        assertEquals("string", props.path("imo").path("type").asText(), "imo must be string type");

        JsonNode required = getShipWithCrew.path("inputSchema").path("required");
        boolean imoRequired = false;
        for (JsonNode r : required) {
            if ("imo".equals(r.asText())) {
                imoRequired = true;
                break;
            }
        }
        assertTrue(imoRequired, "imo must be in required list");
    }

    @Test
    public void getVoyageManifestInputSchemaShouldDeclareVoyageIdParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode getVoyageManifest = callToolsList(http, sessionId).get(5);

        JsonNode props = getVoyageManifest.path("inputSchema").path("properties");
        assertTrue(props.has("voyageId"), "get-voyage-manifest must declare voyageId parameter");
        assertEquals("string", props.path("voyageId").path("type").asText(), "voyageId must be string type");

        JsonNode required = getVoyageManifest.path("inputSchema").path("required");
        boolean voyageIdRequired = false;
        for (JsonNode r : required) {
            if ("voyageId".equals(r.asText())) {
                voyageIdRequired = true;
                break;
            }
        }
        assertTrue(voyageIdRequired, "voyageId must be in required list");
    }

    @Test
    public void skillVoyageOpsShouldIncludeVoyageManifestTool() throws Exception {
        int skillPort = findFreePort();
        SkillServerAdapter skillAdapter = startIsolatedSkillServer(skillPort);

        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + skillPort + "/skills/voyage-ops"))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, string());
            assertEquals(200, response.statusCode(), "GET /skills/voyage-ops must succeed");

            JsonNode detail = json.readTree(response.body());
            assertEquals("voyage-ops", detail.path("name").asText());

            JsonNode tools = detail.path("tools");
            assertTrue(tools.isArray(), "voyage-ops detail must contain a tools array");
            assertEquals(2, tools.size(), "voyage-ops must have 2 tools including manifest");

            List<String> toolNames = new ArrayList<>();
            for (JsonNode t : tools) {
                toolNames.add(t.path("name").asText());
            }
            assertTrue(toolNames.contains("create-voyage"), "voyage-ops must include create-voyage");
            assertTrue(toolNames.contains("get-voyage-manifest"), "voyage-ops must include get-voyage-manifest");
        } finally {
            skillAdapter.stop();
        }
    }

    private IkanosSpec loadPatchedStep11Spec() throws Exception {
        IkanosSpec spec = loadSpec(CAPABILITY_FILE);
        disableMcpAuthentication(spec);


        return spec;
    }

    private SkillServerAdapter startIsolatedSkillServer(int skillPort) throws Exception {
        IkanosSpec spec = loadPatchedStep11Spec();

        spec.getCapability().getExposes().get(0).setPort(findFreePort());

        RestServerSpec restSpec = (RestServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof RestServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("REST expose not found in step-11 spec"));
        restSpec.setPort(findFreePort());

        SkillServerSpec skillSpec = (SkillServerSpec) spec.getCapability().getExposes().stream()
                .filter(e -> e instanceof SkillServerSpec)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Skill expose not found in step-11 spec"));
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