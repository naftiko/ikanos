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
package io.ikanos.toolsearch.lucene;

import static io.ikanos.toolsearch.lucene.McpTestServer.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpToolSearchSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deferred tool advertisement end to end, over real HTTP, with the Lucene provider on the
 * classpath and the 40-tool operations catalog. The fixture declares no {@code toolSearch} block:
 * a client selects deferred mode with {@code ?toolSearch=deferred} on the endpoint URL.
 */
class McpToolSearchIntegrationTest {

    static final String CATALOG = "src/test/resources/mcp/mcp-tool-catalog-capability.yaml";
    private static final String DEFERRED = "toolSearch=deferred";

    private static McpTestServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = McpTestServer.start(CATALOG);
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.close();
    }

    private static List<String> names(JsonNode tools) {
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asString()));
        return names;
    }

    private static JsonNode search(String query, ObjectNode arguments) throws Exception {
        return server.callTool(query, "search-tools", arguments);
    }

    private static ObjectNode query(String text) {
        return JSON.createObjectNode().put("query", text);
    }

    private static List<String> found(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return names(result.path("structuredContent").path("tools"));
    }

    // ── tools/list ──────────────────────────────────────────────────────────────────────────────

    @Test
    void deferredListShouldHoldTheMetaToolsThenThePinnedDestructiveTools() throws Exception {
        assertEquals(List.of("search-tools", "call-tool", "delete-namespace", "cancel-subscription",
                "rotate-api-key"), names(McpTestServer.tools(server.listTools(DEFERRED))));
    }

    @Test
    void deferredListShouldBeByteStableAcrossRequests() throws Exception {
        assertEquals(server.listTools(DEFERRED).body().replaceAll("\"id\":\\d+", ""),
                server.listTools(DEFERRED).body().replaceAll("\"id\":\\d+", ""));
    }

    @Test
    void listedListShouldHoldEveryToolWithOrWithoutTheParameter() throws Exception {
        assertEquals(40, McpTestServer.tools(server.listTools(null)).size());
        assertEquals(40, McpTestServer.tools(server.listTools("toolSearch=listed")).size());
    }

    @Test
    void deferredListShouldApplyTheTagFilterToPinnedToolsOnly() throws Exception {
        assertEquals(List.of("search-tools", "call-tool", "delete-namespace"),
                names(McpTestServer.tools(server.listTools(DEFERRED + "&tags=kubernetes"))));
    }

    @Test
    void searchToolDescriptionShouldNameTheCategories() throws Exception {
        String description = McpTestServer.tools(server.listTools(DEFERRED)).get(0)
                .path("description").asString();

        assertTrue(description.startsWith("Search the ops tools and return their full definitions."
                + " Operations tools for incidents,"), description);
        assertTrue(description.contains("Categories: billing, ci, customers, database,"),
                description);
        assertTrue(description.endsWith("Call a returned tool with call-tool."), description);
    }

    @Test
    void callToolShouldNotBeDestructiveWhenDestructiveToolsArePinned() throws Exception {
        JsonNode callTool = McpTestServer.tools(server.listTools(DEFERRED)).get(1);

        assertFalse(callTool.path("annotations").path("destructiveHint").asBoolean(true));
    }

    // ── search-tools ────────────────────────────────────────────────────────────────────────────

    @Test
    void searchToolsShouldRankTheMatchingToolFirst() throws Exception {
        assertEquals("restart-deployment",
                found(search(DEFERRED, query("restart a kubernetes deployment"))).getFirst());
    }

    @Test
    void searchToolsShouldReturnDefinitionsInTheShapeOfToolsList() throws Exception {
        JsonNode listed = null;
        for (JsonNode tool : McpTestServer.tools(server.listTools(null))) {
            if (tool.path("name").asString().equals("restart-deployment")) {
                listed = tool;
            }
        }
        JsonNode result = search(DEFERRED, query("restart a kubernetes deployment"));

        assertEquals(JSON.writeValueAsString(listed),
                JSON.writeValueAsString(result.path("structuredContent").path("tools").get(0)));
    }

    @Test
    void searchToolsShouldRepeatTheResultAsText() throws Exception {
        JsonNode result = search(DEFERRED, query("refund an invoice"));

        assertEquals(JSON.writeValueAsString(result.path("structuredContent")),
                result.path("content").get(0).path("text").asString());
    }

    @Test
    void searchToolsShouldReturnFiveToolsByDefault() throws Exception {
        assertEquals(5, found(search(DEFERRED, query("list"))).size());
    }

    @Test
    void searchToolsShouldHonourTheLimit() throws Exception {
        assertEquals(2, found(search(DEFERRED, query("list").put("limit", 2))).size());
    }

    @Test
    void searchToolsShouldLeavePinnedToolsOut() throws Exception {
        assertFalse(found(search(DEFERRED, query("delete a kubernetes namespace")))
                .contains("delete-namespace"));
    }

    @Test
    void searchToolsShouldKeepOnlyToolsWithTheRequestedTags() throws Exception {
        ObjectNode arguments = query("list");
        arguments.putArray("tags").add("billing");

        assertEquals(List.of("list-customer-invoices"), found(search(DEFERRED, arguments)));
    }

    @Test
    void searchToolsShouldStayWithinTheUrlTagFilter() throws Exception {
        assertEquals(List.of("list-open-incidents"),
                found(search(DEFERRED + "&tags=incidents", query("list"))));
    }

    @Test
    void searchToolsShouldMeetTheUrlTagFilterAndTheTagsArgumentSeparately() throws Exception {
        ObjectNode arguments = query("restart deployment");
        arguments.putArray("tags").add("deployments");

        // restart-deployment carries kubernetes and deployments: one tag for each filter.
        assertTrue(found(search(DEFERRED + "&tags=kubernetes", arguments))
                .contains("restart-deployment"));
    }

    @Test
    void searchToolsShouldReportNonStringTagsAsAToolError() throws Exception {
        ObjectNode arguments = query("list");
        arguments.putArray("tags").addObject();

        JsonNode result = search(DEFERRED, arguments);

        assertTrue(result.path("isError").asBoolean());
        assertEquals("tags must be an array of strings.",
                result.path("content").get(0).path("text").asString());
    }

    @Test
    void searchToolsShouldKeepOnlyReadOnlyToolsWhenAsked() throws Exception {
        List<String> names = found(search(DEFERRED, query("incident").put("readOnly", true)));

        assertEquals(List.of("get-incident", "list-open-incidents"), names.stream().sorted().toList());
    }

    @Test
    void searchToolsShouldAnswerAnEmptyResultWithAHint() throws Exception {
        JsonNode result = search(DEFERRED, query("photosynthesis"));

        assertEquals(List.of(), found(result));
        assertTrue(result.path("content").get(0).path("text").asString().startsWith("No tools matched"));
    }

    @Test
    void searchToolsShouldReportABlankQueryAsAToolError() throws Exception {
        assertTrue(search(DEFERRED, query("  ")).path("isError").asBoolean());
    }

    @Test
    void searchToolsShouldReportATooLongQueryAsAToolError() throws Exception {
        assertTrue(search(DEFERRED, query("x".repeat(501))).path("isError").asBoolean());
    }

    // ── call-tool and direct calls ──────────────────────────────────────────────────────────────

    /** Arguments of call-tool for the given tool names, each called without arguments. */
    private static ObjectNode calls(String... names) {
        ObjectNode arguments = JSON.createObjectNode();
        var list = arguments.putArray("calls");
        for (String name : names) {
            list.addObject().put("name", name).putObject("arguments");
        }
        return arguments;
    }

    private static JsonNode callResults(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return result.path("structuredContent").path("results");
    }

    @Test
    void callToolShouldCallTheNamedTool() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("get-status-page")));

        assertEquals("get-status-page", results.get(0).path("name").asString());
        assertTrue(results.get(0).path("result").path("content").get(0).path("text").asString()
                .contains("All systems operational"), results.toString());
    }

    @Test
    void callToolShouldReturnWhatADirectCallReturns() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("get-status-page")));

        ObjectNode direct = (ObjectNode) server.callTool(null, "get-status-page",
                JSON.createObjectNode());
        direct.remove("_meta"); // server info added once, on the top-level result

        assertEquals(direct, results.get(0).path("result"));
    }

    @Test
    void callToolShouldRunSeveralToolsInCallOrder() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("get-status-page", "launch-rocket", "get-status-page")));

        assertEquals(List.of("get-status-page", "launch-rocket", "get-status-page"),
                names(results));
    }

    @Test
    void callToolShouldNotStopAtAFailingCall() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("launch-rocket", "get-status-page")));

        assertTrue(results.get(0).path("result").path("isError").asBoolean());
        assertFalse(results.get(1).path("result").path("isError").asBoolean());
    }

    @Test
    void callToolShouldRefuseMoreCallsThanTheCap() throws Exception {
        JsonNode result = server.callTool(DEFERRED, "call-tool", calls("get-status-page",
                "get-status-page", "get-status-page", "get-status-page", "get-status-page",
                "get-status-page"));

        assertTrue(result.path("isError").asBoolean());
        assertEquals("Too many calls: call-tool runs at most 5 tools per request.",
                result.path("content").get(0).path("text").asString());
    }

    @Test
    void callToolShouldRequireAtLeastOneCall() throws Exception {
        assertTrue(server.callTool(DEFERRED, "call-tool", calls()).path("isError").asBoolean());
    }

    @Test
    void callToolShouldRefuseToCallAMetaTool() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("search-tools")));

        assertTrue(results.get(0).path("result").path("isError").asBoolean());
        assertEquals("call-tool cannot call search-tools.",
                results.get(0).path("result").path("content").get(0).path("text").asString());
    }

    @Test
    void callToolShouldRefuseAPinnedTool() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("delete-namespace")));

        assertTrue(results.get(0).path("result").path("isError").asBoolean());
        assertEquals("delete-namespace is listed with the other tools: call it directly, not"
                + " through call-tool.",
                results.get(0).path("result").path("content").get(0).path("text").asString());
    }

    @Test
    void callToolShouldReportAnUnknownToolAsAToolError() throws Exception {
        JsonNode results = callResults(server.callTool(DEFERRED, "call-tool",
                calls("launch-rocket")));

        assertEquals("Unknown tool: launch-rocket. Use search-tools to find tools.",
                results.get(0).path("result").path("content").get(0).path("text").asString());
    }

    @Test
    void directCallShouldStillWorkInDeferredMode() throws Exception {
        JsonNode result = server.callTool(DEFERRED, "get-status-page", JSON.createObjectNode());

        assertFalse(result.path("isError").asBoolean(), result.toString());
    }

    @Test
    void metaToolsShouldNotExistInListedMode() throws Exception {
        JsonNode result = server.callTool(null, "search-tools", query("deployment"));

        assertTrue(result.isMissingNode(), "a listed client gets a JSON-RPC error, as for any"
                + " unknown tool");
    }

    // ── endpoint default ────────────────────────────────────────────────────────────────────────

    @Test
    void endpointDeclaredDeferredShouldDeferWithoutAnyUrlParameter() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(new File(CATALOG),
                IkanosSpec.class);
        McpToolSearchSpec toolSearch = new McpToolSearchSpec();
        toolSearch.setMode("deferred");
        toolSearch.setAlwaysListed(List.of("get-incident"));
        ((McpServerSpec) spec.getCapability().getExposes().getFirst()).setToolSearch(toolSearch);

        ProtocolDispatcher dispatcher =
                new ProtocolDispatcher(McpTestServer.adapterOf(new Capability(spec)));
        JsonNode response = dispatcher.dispatch(JSON.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"%s"}}}
                """.formatted(ProtocolDispatcher.MCP_PROTOCOL_VERSION))).responseBody();

        assertEquals(List.of("search-tools", "call-tool", "get-incident", "delete-namespace",
                "cancel-subscription", "rotate-api-key"),
                names(response.path("result").path("tools")));
    }
}
