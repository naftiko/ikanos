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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.engine.exposes.mcp.model.McpRequestContext;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.engine.exposes.mcp.search.ToolSearchMode;
import io.ikanos.engine.exposes.mcp.search.ToolSearchQuery;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Offline benchmark of MCP tool search on the 40-tool operations catalog: how many bytes of tool
 * definitions each request carries with and without deferred mode, and how often the Lucene
 * baseline finds the tool a task needs.
 *
 * <p>It runs the real index, dispatcher and handlers, with no network and no model, so it is
 * reproducible in CI and guards against regressions in ranking and in advertisement size. It does
 * not measure task success, requests per task or prompt-cache tokens: those need live model
 * runs. The report is written to {@code target/tool-search-benchmark.md}.</p>
 */
class ToolSearchBenchmarkTest {

    private static final String QUERIES = "src/test/resources/benchmark/tool-search-queries.yml";
    private static final Path REPORT = Path.of("target/tool-search-benchmark.md");

    /** Rough bytes per token for JSON tool definitions; the token figures are estimates. */
    private static final double BYTES_PER_TOKEN = 4.0;

    /**
     * Regression gates, set just below the baseline measured when the benchmark was added
     * (hit@1 0.62, hit@3 0.87, hit@5 0.95, MRR 0.75 on 39 tasks). They catch a ranking change
     * that makes search worse; they are not a claim that the baseline is good enough.
     */
    private static final double MIN_HIT_AT_5 = 0.90;
    private static final double MIN_HIT_AT_1 = 0.55;

    private static ProtocolDispatcher dispatcher;
    private static ToolSearchCatalog catalog;
    private static List<LabeledQuery> queries;

    record LabeledQuery(String query, String expected) {}

    record Retrieval(double hitAt1, double hitAt3, double hitAt5, double mrr, List<String> misses) {}

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper yaml = IkanosMappers.lenientYaml();
        IkanosSpec spec = yaml.readValue(new File(McpToolSearchIntegrationTest.CATALOG),
                IkanosSpec.class);
        McpServerAdapter adapter = McpTestServer.adapterOf(new Capability(spec));
        dispatcher = new ProtocolDispatcher(adapter);
        catalog = adapter.getToolSearchCatalog();

        queries = new ArrayList<>();
        yaml.readTree(new File(QUERIES)).path("queries").forEach(entry -> queries.add(
                new LabeledQuery(entry.path("query").asString(), entry.path("expected").asString())));
    }

    private static JsonNode dispatch(String method, ObjectNode params, ToolSearchMode mode) {
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1)
                .put("method", method);
        request.set("params", params);
        return dispatcher.dispatch(request, new McpRequestContext(Set.of(), mode))
                .responseBody().path("result");
    }

    private static int listingBytes(ToolSearchMode mode) {
        JsonNode tools = dispatch("tools/list", JSON.createObjectNode(), mode).path("tools");
        return JSON.writeValueAsString(tools).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Bytes of the text a model reads back from one search-tools call. */
    private static int searchResultBytes(String query) {
        ObjectNode params = JSON.createObjectNode().put("name", "search-tools");
        params.putObject("arguments").put("query", query);
        JsonNode result = dispatch("tools/call", params, ToolSearchMode.DEFERRED);
        return result.path("content").get(0).path("text").asString()
                .getBytes(StandardCharsets.UTF_8).length;
    }

    static Retrieval retrieval() {
        int hit1 = 0;
        int hit3 = 0;
        int hit5 = 0;
        double reciprocalRanks = 0;
        List<String> misses = new ArrayList<>();
        for (LabeledQuery labeled : queries) {
            List<String> ranked = catalog.search(new ToolSearchQuery(labeled.query(), Set.of(),
                    false, 5));
            int rank = ranked.indexOf(labeled.expected()) + 1;
            if (rank == 1) {
                hit1++;
            }
            if (rank >= 1 && rank <= 3) {
                hit3++;
            }
            if (rank >= 1) {
                hit5++;
                reciprocalRanks += 1.0 / rank;
            } else {
                misses.add(labeled.query() + " -> expected " + labeled.expected() + ", got "
                        + ranked);
            }
        }
        double n = queries.size();
        return new Retrieval(hit1 / n, hit3 / n, hit5 / n, reciprocalRanks / n, misses);
    }

    @Test
    void everyExpectedToolShouldBeSearchable() {
        Set<String> searchable = Set.copyOf(catalog.searchableTools().stream()
                .map(tool -> tool.name()).toList());

        assertEquals(List.of(), queries.stream().map(LabeledQuery::expected)
                .filter(name -> !searchable.contains(name)).toList());
    }

    @Test
    void deferredListingShouldBeAFractionOfTheFullListing() {
        assertTrue(listingBytes(ToolSearchMode.DEFERRED) * 4 < listingBytes(ToolSearchMode.LISTED),
                "deferred mode must carry far fewer definition bytes per request");
    }

    @Test
    @DisplayName("Lucene baseline finds the expected tool in the top 5 for most labeled tasks")
    void searchShouldMeetTheRetrievalGates() throws Exception {
        Retrieval retrieval = retrieval();
        writeReport(retrieval);

        assertTrue(retrieval.hitAt5() >= MIN_HIT_AT_5,
                "hit@5 " + retrieval.hitAt5() + " below " + MIN_HIT_AT_5 + "; misses: "
                        + retrieval.misses());
        assertTrue(retrieval.hitAt1() >= MIN_HIT_AT_1,
                "hit@1 " + retrieval.hitAt1() + " below " + MIN_HIT_AT_1);
    }

    private static void writeReport(Retrieval retrieval) throws Exception {
        int listed = listingBytes(ToolSearchMode.LISTED);
        int deferred = listingBytes(ToolSearchMode.DEFERRED);
        double averageSearch = queries.stream().mapToInt(q -> searchResultBytes(q.query()))
                .average().orElse(0);
        int tools = catalog.searchableTools().size() + catalog.pinnedTools().size();

        StringBuilder report = new StringBuilder();
        report.append("# MCP tool search benchmark (offline)\n\n");
        report.append("Catalog: ").append(tools).append(" tools, ")
                .append(catalog.pinnedTools().size()).append(" pinned (destructive), ")
                .append(catalog.searchableTools().size()).append(" searchable. Queries: ")
                .append(queries.size()).append(" labeled tasks. Tokens are estimates at ")
                .append((int) BYTES_PER_TOKEN).append(" bytes per token.\n\n");
        report.append("## Tool definitions per request\n\n");
        report.append("| Arm | Bytes | ≈ tokens |\n| --- | ---: | ---: |\n");
        row(report, "B: listed, every request", listed);
        row(report, "D-server: deferred listing, every request", deferred);
        row(report, "D-server: one search-tools result (top 5, average)", (int) averageSearch);
        report.append("\nA task of N requests with one search costs about ")
                .append(String.format(Locale.ROOT, "%d·N", listed)).append(" bytes listed and ")
                .append(String.format(Locale.ROOT, "%d·N + %d", deferred, (int) averageSearch))
                .append(" bytes deferred, plus one extra request.\n\n");
        report.append("## Retrieval (Lucene BM25 baseline)\n\n");
        report.append("| hit@1 | hit@3 | hit@5 | MRR |\n| ---: | ---: | ---: | ---: |\n");
        report.append(String.format(Locale.ROOT, "| %.2f | %.2f | %.2f | %.2f |%n%n",
                retrieval.hitAt1(), retrieval.hitAt3(), retrieval.hitAt5(), retrieval.mrr()));
        report.append("Misses (not in the top 5):\n\n");
        if (retrieval.misses().isEmpty()) {
            report.append("- none\n");
        }
        retrieval.misses().forEach(miss -> report.append("- ").append(miss).append('\n'));
        report.append("\nNot measured here: task success, requests per task, and prompt-cache read"
                + " and write tokens. Those need live model runs (arms D-client and D-native).\n");

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static void row(StringBuilder report, String arm, int bytes) {
        report.append("| ").append(arm).append(" | ").append(bytes).append(" | ")
                .append(Math.round(bytes / BYTES_PER_TOKEN)).append(" |\n");
    }
}
