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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code tools/list} filtered by tag through the endpoint URL ({@code ?tags=a,b}), over real HTTP
 * against the 40-tool catalog fixture.
 */
class McpToolsListTagFilterIntegrationTest {

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
    void toolsListShouldReturnOnlyToolsCarryingTheRequestedTag() throws Exception {
        assertEquals(List.of("get-incident", "list-open-incidents", "create-incident",
                "acknowledge-incident", "resolve-incident", "add-incident-note"),
                toolNames(client.listTools("tags=incidents")));
    }

    @Test
    void toolsListShouldReturnToolsCarryingAnyOfSeveralTags() throws Exception {
        assertEquals(List.of("get-pod-logs", "search-logs", "get-on-call", "page-engineer",
                "override-on-call"), toolNames(client.listTools("tags=logs,on-call")));
    }

    @Test
    void toolsListShouldMatchTagsExactlyAndCaseSensitively() throws Exception {
        assertEquals(List.of(), toolNames(client.listTools("tags=Incidents")));
    }

    @Test
    void toolsListShouldIgnoreAnEmptyTagsParameter() throws Exception {
        assertEquals(40, toolNames(client.listTools("tags=")).size());
    }

    @Test
    void toolsListWithoutParameterShouldKeepTheGoldenBytes() throws Exception {
        String golden = Files.readString(
                Path.of("src/test/resources/mcp/golden/mcp-tool-catalog-capability.tools.json"),
                StandardCharsets.UTF_8).stripTrailing();

        assertEquals(golden, JSON.writeValueAsString(
                JSON.readTree(client.listTools(null).body()).path("result").path("tools")));
    }

    @Test
    void filteredToolsListShouldBeByteStableAcrossRequests() throws Exception {
        assertEquals(resultBytes(client.listTools("tags=kubernetes")),
                resultBytes(client.listTools("tags=kubernetes")));
    }

    @Test
    void toolsCallShouldResolveAToolLeftOutByTheFilter() throws Exception {
        HttpResponse<String> response = client.callTool("tags=incidents", "list-alerts",
                JSON.createObjectNode());
        JsonNode body = JSON.readTree(response.body());

        assertFalse(body.has("error"), "the filter limits discovery, not access: " + body);
        assertTrue(body.path("result").has("content"));
    }

    private static List<String> toolNames(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        List<String> names = new ArrayList<>();
        JSON.readTree(response.body()).path("result").path("tools")
                .forEach(tool -> names.add(tool.path("name").asString()));
        return names;
    }

    private static String resultBytes(HttpResponse<String> response) throws Exception {
        return JSON.writeValueAsString(JSON.readTree(response.body()).path("result"));
    }
}
