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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpToolSearchSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The engine without the optional {@code ikanos-tool-search} module, as in the stock
 * {@code ikanos.jar} and in applications that embed the engine: a client that asks for deferred
 * mode still gets a full, working listing, and only a spec that declares deferred mode fails.
 */
class ToolSearchModuleAbsentIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String CATALOG = "src/test/resources/mcp/mcp-tool-catalog-capability.yaml";

    private static McpHttpTestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        client = McpHttpTestClient.start(CATALOG);
    }

    @AfterAll
    static void stopServer() throws Exception {
        client.close();
    }

    @Test
    void deferredRequestShouldFallBackToTheFullListingWithTheGoldenBytes() throws Exception {
        String golden = Files.readString(
                Path.of("src/test/resources/mcp/golden/mcp-tool-catalog-capability.tools.json"),
                StandardCharsets.UTF_8).stripTrailing();

        assertEquals(golden, JSON.writeValueAsString(JSON.readTree(
                client.listTools("toolSearch=deferred").body()).path("result").path("tools")));
    }

    @Test
    void unknownToolSearchValueShouldBeAnInvalidRequest() throws Exception {
        HttpResponse<String> response = client.listTools("toolSearch=lazy");
        JsonNode error = JSON.readTree(response.body()).path("error");

        assertEquals(400, response.statusCode());
        assertEquals(-32600, error.path("code").asInt());
        assertTrue(error.path("message").asString().contains("toolSearch value 'lazy'"),
                error.toString());
    }

    @Test
    void metaToolsShouldNotExistWithoutTheModule() throws Exception {
        JsonNode body = JSON.readTree(client.callTool("toolSearch=deferred", "search-tools",
                JSON.createObjectNode().put("query", "deployment")).body());

        assertTrue(body.has("error"), "search-tools is an unknown tool here: " + body);
    }

    @Test
    void capabilityDeclaringDeferredModeShouldFailToLoadWithoutTheModule() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(new File(CATALOG),
                IkanosSpec.class);
        McpToolSearchSpec toolSearch = new McpToolSearchSpec();
        toolSearch.setMode("deferred");
        ((McpServerSpec) spec.getCapability().getExposes().getFirst()).setToolSearch(toolSearch);

        Exception error = assertThrows(Exception.class, () -> new Capability(spec));
        Throwable cause = error;
        while (cause.getCause() != null && !cause.getMessage().contains("ikanos-tool-search")) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("Add the ikanos-tool-search module"),
                error.toString());
    }
}
