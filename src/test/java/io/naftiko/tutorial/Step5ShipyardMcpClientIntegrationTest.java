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
 * End-to-end integration test for {@code step-5-shipyard-multi-source.yml} exercised
 * from a remote MCP client perspective.
 *
 * <p>Step 5 is the first tutorial step to consume <strong>multiple imported sources</strong>.
 * Instead of embedding consumes inline, both adapters are declared via {@code import:}:</p>
 *
 * <pre>
 * consumes:
 *   - import: registry
 *     location: "./shared/step5-registry-consumes.yaml"
 *   - import: legacy
 *     location: "./shared/legacy-consumes.yaml"
 * </pre>
 *
 * <p>The capability exposes <strong>three tools</strong>:</p>
 * <ul>
 *   <li>{@code list-ships} — calls {@code registry.list-ships}, returns flat array</li>
 *   <li>{@code get-ship} — calls {@code registry.get-ship}, returns object with nested
 *       {@code specs} sub-object (same output shaping introduced in step 4)</li>
 *   <li>{@code list-legacy-vessels} — calls {@code legacy.list-vessels}, the new tool in
 *       this step, mapping legacy field names to canonical names</li>
 * </ul>
 *
 * <p>Setup steps at test start:</p>
 * <ol>
 *   <li>Patch both bind {@code location}s to an absolute URI so the {@link io.naftiko.engine.util.BindingResolver}
 *       finds the secrets file regardless of Maven CWD.</li>
 *   <li>Manually resolve imported consumes via {@link ConsumesImportResolver} using the
 *       tutorial directory as {@code capabilityDir}. This populates the consumes list with
 *       real {@link HttpClientSpec} objects before {@link io.naftiko.Capability} is constructed.</li>
 *   <li>Patch the resolved {@code legacy} {@link HttpClientSpec#setBaseUri(String)} to the
 *       real mock URL (the shared file contains a placeholder URI).</li>
 * </ol>
 */
public class Step5ShipyardMcpClientIntegrationTest
        extends AbstractShipyardMcpClientIntegrationTest {

    private static final String CAPABILITY_FILE =
            "src/main/resources/tutorial/step-5-shipyard-multi-source.yml";
    private static final String SECRETS_FILE =
            "src/main/resources/tutorial/shared/secrets.yaml";
    private static final String TUTORIAL_DIR =
            "src/main/resources/tutorial";
    private static final String LEGACY_MOCK_URI =
            "https://mocks.naftiko.net/rest/naftiko-shipyard-legacy-dockyard-api/1.0.0-alpha1";

    @BeforeEach
    public void startServer() throws Exception {
        NaftikoSpec spec = loadSpec(CAPABILITY_FILE);

        // Patch both bind locations to an absolute file URI
        String secretsAbsoluteUri = new File(SECRETS_FILE).getAbsoluteFile().toURI().toString();
        spec.getBinds().get(0).setLocation(secretsAbsoluteUri);
        spec.getBinds().get(1).setLocation(secretsAbsoluteUri);

        // Resolve imported consumes (./shared/*.yaml) against the tutorial directory.
        // After this call the consumes list contains HttpClientSpec objects, so the
        // subsequent Capability(spec) constructor finds nothing left to import.
        String tutorialAbsoluteDir = new File(TUTORIAL_DIR).getAbsolutePath();
        new ConsumesImportResolver().resolveImports(
                spec.getCapability().getConsumes(), tutorialAbsoluteDir);

        // Patch the legacy baseUri: the shared file targets a placeholder host;
        // tests must hit the real mock server at mocks.naftiko.net
        for (ClientSpec cs : spec.getCapability().getConsumes()) {
            if (cs instanceof HttpClientSpec && "legacy".equals(cs.getNamespace())) {
                ((HttpClientSpec) cs).setBaseUri(LEGACY_MOCK_URI);
            }
        }

        startServerFromSpec(spec);
    }

    // ── tools/list ────────────────────────────────────────────────────────────

    @Test
    public void toolsListShouldAdvertiseThreeTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode tools = callToolsList(http, sessionId);

        assertEquals(3, tools.size(), "step-5 exposes exactly three tools");
        assertEquals("list-ships",          tools.get(0).path("name").asText());
        assertEquals("get-ship",            tools.get(1).path("name").asText());
        assertEquals("list-legacy-vessels", tools.get(2).path("name").asText());
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

    @Test
    public void listLegacyVesselsInputSchemaShouldDeclareOptionalStatusParameter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode listLegacy = callToolsList(http, sessionId).get(2);

        JsonNode props = listLegacy.path("inputSchema").path("properties");
        assertTrue(props.has("status"), "list-legacy-vessels inputSchema must declare 'status'");
        assertEquals("string", props.path("status").path("type").asText());

        JsonNode required = listLegacy.path("inputSchema").path("required");
        boolean statusRequired = false;
        for (JsonNode r : required) {
            if ("status".equals(r.asText())) {
                statusRequired = true;
                break;
            }
        }
        assertFalse(statusRequired, "'status' must not appear in the required array for list-legacy-vessels");
    }

    // ── list-ships — registry source, flat fields ─────────────────────────────

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

    // ── get-ship — registry source, nested specs object ──────────────────────

    @Test
    public void getShipShouldReturnFlatFieldsAndNestedSpecsObject() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode ship = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"get-ship","arguments":{"imo":"IMO-9321483"}}}
                """);

        assertTrue(ship.isObject(), "get-ship must return a JSON object");

        assertEquals("IMO-9321483", ship.path("imo").asText(),
                "Returned imo must match the requested IMO number");
        assertFalse(ship.path("name").asText().isBlank(),   "name must not be blank");
        assertFalse(ship.path("type").asText().isBlank(),   "type must not be blank");
        assertFalse(ship.path("flag").asText().isBlank(),   "flag must not be blank");
        assertFalse(ship.path("status").asText().isBlank(), "status must not be blank");

        assertTrue(ship.has("specs"), "get-ship must include the nested 'specs' object");
        JsonNode specs = ship.path("specs");
        assertTrue(specs.isObject(), "'specs' must be a JSON object");
        assertTrue(specs.has("yearBuilt"), "specs must have 'yearBuilt'");
        assertTrue(specs.path("yearBuilt").isNumber(), "'yearBuilt' must be a number");
        assertTrue(specs.has("tonnage"),   "specs must have 'tonnage'");
        assertTrue(specs.path("tonnage").isNumber(),   "'tonnage' must be a number");
        assertTrue(specs.has("length"),    "specs must have 'length'");
        assertTrue(specs.path("length").isNumber(),    "'length' must be a number");
    }

    // ── list-legacy-vessels — legacy source, mapped field names ──────────────

    @Test
    public void listLegacyVesselsShouldReturnMappedVesselArray() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String sessionId = initialize(http);

        JsonNode vessels = callTool(http, sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"list-legacy-vessels","arguments":{}}}
                """);

        assertTrue(vessels.isArray() && vessels.size() > 0,
                "list-legacy-vessels must return a non-empty array");

        JsonNode first = vessels.get(0);
        assertTrue(first.has("vesselCode"), "Must have 'vesselCode' (direct mapping from $.vesselCode)");
        assertTrue(first.has("name"),       "Must have 'name' (mapped from $.vesselName)");
        assertTrue(first.has("type"),       "Must have 'type' (mapped from $.category)");
        assertTrue(first.has("flag"),       "Must have 'flag' (mapped from $.flagState)");
        assertTrue(first.has("status"),     "Must have 'status' (mapped from $.operationalStatus)");
        assertFalse(first.path("vesselCode").asText().isBlank(), "vesselCode must not be blank");
        assertFalse(first.path("name").asText().isBlank(),       "name must not be blank");
    }
}
