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

import io.naftiko.engine.ConsumesImportResolver;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;

/**
 * End-to-end integration test for {@code step-7-shipyard-orchestrated-lookup.yml}
 * exercised from a remote MCP client perspective.
 *
 * <p>Step 7 keeps the step-6 tool surface and adds an orchestrated tool,
 * {@code get-ship-with-crew}, which resolves crew member IDs into name/role pairs
 * using lookup steps.</p>
 */
public class Step7ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-7-shipyard-orchestrated-lookup.yml";
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
    public void toolsListShouldAdvertiseFiveTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(5, tools.size(), "step-7 exposes exactly five tools");
        assertEquals("list-ships",          tools.get(0).path("name").asText());
        assertEquals("get-ship",            tools.get(1).path("name").asText());
        assertEquals("list-legacy-vessels", tools.get(2).path("name").asText());
        assertEquals("create-voyage",       tools.get(3).path("name").asText());
        assertEquals("get-ship-with-crew",  tools.get(4).path("name").asText());
    }

    @Test
    public void getShipWithCrewInputSchemaShouldDeclareRequiredImoParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode getShipWithCrew = callToolsList(http, sessionId).get(4);

        JsonNode props = getShipWithCrew.path("inputSchema").path("properties");
        assertTrue(props.has("imo"), "get-ship-with-crew inputSchema must declare 'imo'");
        assertEquals("string", props.path("imo").path("type").asText());

        JsonNode required = getShipWithCrew.path("inputSchema").path("required");
        boolean imoRequired = false;
        for (JsonNode r : required) {
            if ("imo".equals(r.asText())) {
                imoRequired = true;
                break;
            }
        }
        assertTrue(imoRequired, "'imo' must appear in required for get-ship-with-crew");
    }

    @Test
    public void getShipWithCrewShouldReturnCrewEntriesWithResolvedFields() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode crew = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                 "params":{"name":"get-ship-with-crew","arguments":{"imo":"IMO-9321483"}}}
                """);
        assertTrue(crew.isArray(), "crew must be an array");
        assertTrue(crew.size() > 0, "crew must not be empty");

        JsonNode first = crew.get(0);
        assertTrue(first.has("crewId"), "crew entries must have crewId");
        assertTrue(first.has("fullName"), "crew entries must have fullName");
        assertTrue(first.has("role"), "crew entries must have role");
        assertTrue(first.has("certifications"), "crew entries must have certifications");
        assertFalse(first.path("fullName").asText().isBlank(), "fullName must not be blank");
        assertFalse(first.path("role").asText().isBlank(), "role must not be blank");
    }
}
