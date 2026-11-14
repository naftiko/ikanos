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
package io.ikanos.engine.exposes.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.engine.exposes.mcp.model.McpRequestContext;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.engine.exposes.mcp.search.ToolSearchDocument;
import io.ikanos.engine.exposes.mcp.search.ToolSearchIndexFactory;
import io.ikanos.engine.exposes.mcp.search.ToolSearchMode;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.restlet.Request;
import org.restlet.data.Method;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telemetry of MCP tool search: one span per search with its outcome and filters but never the
 * query text, search and call counters, and the variant on every MCP server span.
 */
class ToolSearchTelemetryTest {

    private static final String CATALOG = "src/test/resources/mcp/mcp-tool-catalog-capability.yaml";
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final McpRequestContext DEFERRED =
            new McpRequestContext(Set.of(), ToolSearchMode.DEFERRED);

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private McpServerAdapter adapter;
    private McpServerSpec serverSpec;
    private ToolsCallHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(spans))
                .setMeterProvider(OtelTestFixtures.meterProvider(metrics))
                .build());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(new File(CATALOG), IkanosSpec.class);
        serverSpec = (McpServerSpec) spec.getCapability().getExposes().getFirst();
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().getFirst();
        handler = new ToolsCallHandler(adapter, List.of(), List.of(), List.of());
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        spans.reset();
    }

    /** A catalog whose index returns the given names, whatever the query. */
    private ToolSearchCatalog catalogReturning(List<String> names) {
        ToolSearchIndexFactory factory = new ToolSearchIndexFactory() {
            @Override
            public String type() {
                return "fixed";
            }

            @Override
            public io.ikanos.engine.exposes.mcp.search.ToolSearchIndex create(
                    List<ToolSearchDocument> documents) {
                return query -> names;
            }
        };
        return catalog(Optional.of(factory));
    }

    private ToolSearchCatalog catalog(Optional<ToolSearchIndexFactory> factory) {
        return new ToolSearchCatalog(serverSpec, adapter.getTools(), adapter.getToolLabels(),
                adapter.getToolTags(), factory);
    }

    private SpanData searchSpan() {
        return spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("mcp.tool_search")).findFirst().orElseThrow();
    }

    private long counter(String name, String outcome) {
        for (MetricData metric : metrics.collectAllMetrics()) {
            if (metric.getName().equals(name)) {
                for (LongPointData point : metric.getLongSumData().getPoints()) {
                    String pointOutcome = point.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("outcome"));
                    if (outcome == null || outcome.equals(pointOutcome)) {
                        return point.getValue();
                    }
                }
            }
        }
        return 0;
    }

    @Test
    void searchShouldRecordASpanWithResultCountTopToolAndFilterNames() {
        ObjectNode arguments = JSON.createObjectNode().put("query", "secret customer name")
                .put("limit", 3);
        arguments.putArray("tags").add("kubernetes");

        handler.searchTools(catalogReturning(List.of("restart-deployment", "list-pods")),
                arguments, DEFERRED);

        SpanData span = searchSpan();
        assertEquals(2L, span.getAttributes().get(TelemetryBootstrap.ATTR_TOOL_SEARCH_RESULT_COUNT));
        assertEquals("restart-deployment",
                span.getAttributes().get(TelemetryBootstrap.ATTR_TOOL_SEARCH_TOP_TOOL));
        assertEquals("tags,limit",
                span.getAttributes().get(TelemetryBootstrap.ATTR_TOOL_SEARCH_FILTERS));
        assertEquals("ops", span.getAttributes().get(TelemetryBootstrap.ATTR_NAMESPACE));
    }

    @Test
    void searchSpanShouldNeverHoldTheQueryText() {
        handler.searchTools(catalogReturning(List.of("get-customer")),
                JSON.createObjectNode().put("query", "secret customer name"), DEFERRED);

        assertFalse(searchSpan().getAttributes().toString().contains("secret customer name"));
    }

    @Test
    void searchShouldCountAHit() {
        handler.searchTools(catalogReturning(List.of("get-customer")),
                JSON.createObjectNode().put("query", "customer"), DEFERRED);

        assertEquals(1, counter("ikanos.mcp.tool_search.total", "hit"));
    }

    @Test
    void searchShouldCountAMiss() {
        handler.searchTools(catalogReturning(List.of()),
                JSON.createObjectNode().put("query", "photosynthesis"), DEFERRED);

        assertEquals(1, counter("ikanos.mcp.tool_search.total", "miss"));
        assertNull(searchSpan().getAttributes().get(TelemetryBootstrap.ATTR_TOOL_SEARCH_TOP_TOOL));
    }

    @Test
    void searchShouldMarkAnUnavailableProviderAndCountAnError() {
        ObjectNode result = handler.searchTools(catalog(Optional.empty()),
                JSON.createObjectNode().put("query", "customer"), DEFERRED);

        assertTrue(result.path("isError").asBoolean());
        assertEquals(Boolean.TRUE,
                searchSpan().getAttributes().get(TelemetryBootstrap.ATTR_TOOL_SEARCH_UNAVAILABLE));
        assertEquals(1, counter("ikanos.mcp.tool_search.total", "error"));
    }

    @Test
    void callToolShouldCountEachToolItCalls() {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.putArray("calls").add(JSON.createObjectNode().put("name", "list-alerts"))
                .add(JSON.createObjectNode().put("name", "get-incident"));

        handler.callTool(catalogReturning(List.of()), arguments);

        assertEquals(2, counter("ikanos.mcp.tool_search.calls.total", null));
    }

    @Test
    void serverSpanShouldCarryTheVariant() throws Exception {
        new ProtocolDispatcher(adapter).dispatchWithTracing(JSON.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"%s"}}}
                """.formatted(ProtocolDispatcher.MCP_PROTOCOL_VERSION)),
                new Request(Method.POST, "http://localhost/mcp?tags=incidents"));

        SpanData server = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getAttributes()
                        .get(TelemetryBootstrap.ATTR_TOOL_SEARCH_VARIANT) != null)
                .findFirst().orElseThrow();
        assertEquals("listed",
                server.getAttributes().get(TelemetryBootstrap.ATTR_TOOL_SEARCH_VARIANT));
    }
}
