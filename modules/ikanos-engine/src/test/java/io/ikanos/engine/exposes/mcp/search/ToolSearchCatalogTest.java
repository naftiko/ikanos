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
package io.ikanos.engine.exposes.mcp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpToolSearchSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.modelcontextprotocol.spec.McpSchema;

class ToolSearchCatalogTest {

    private static final String CATALOG = "src/test/resources/mcp/mcp-tool-catalog-capability.yaml";

    private McpServerSpec serverSpec;
    private McpServerAdapter adapter;

    /** A provider whose index returns the first tools in authored order, and counts creations. */
    private final AtomicInteger created = new AtomicInteger();
    private final ToolSearchIndexFactory provider = new ToolSearchIndexFactory() {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public ToolSearchIndex create(List<ToolSearchDocument> documents) {
            created.incrementAndGet();
            return query -> documents.stream().map(ToolSearchDocument::name)
                    .limit(query.limit()).toList();
        }
    };

    @BeforeEach
    void loadCatalog() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(new File(CATALOG), IkanosSpec.class);
        serverSpec = (McpServerSpec) spec.getCapability().getExposes().getFirst();
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().getFirst();
    }

    private ToolSearchCatalog catalog(McpToolSearchSpec toolSearch,
            Optional<ToolSearchIndexFactory> factory) {
        serverSpec.setToolSearch(toolSearch);
        return new ToolSearchCatalog(serverSpec, adapter.getTools(), adapter.getToolLabels(),
                adapter.getToolTags(), factory);
    }

    private ToolSearchCatalog catalog(McpToolSearchSpec toolSearch) {
        return catalog(toolSearch, Optional.of(provider));
    }

    private static McpToolSearchSpec toolSearch() {
        return new McpToolSearchSpec();
    }

    private static List<String> names(List<McpSchema.Tool> tools) {
        return tools.stream().map(McpSchema.Tool::name).toList();
    }

    // ── mode resolution ─────────────────────────────────────────────────────────────────────────

    @Test
    void resolveModeShouldUseTheEndpointModeWithoutAParameter() {
        McpToolSearchSpec spec = toolSearch();
        spec.setMode("deferred");

        assertEquals(ToolSearchMode.DEFERRED, catalog(spec).resolveMode(null));
    }

    @Test
    void resolveModeShouldTreatAnEmptyParameterAsAbsent() {
        assertEquals(ToolSearchMode.LISTED, catalog(null).resolveMode(""));
    }

    @Test
    void resolveModeShouldFollowTheClientWhenNoBlockIsDeclared() {
        assertEquals(ToolSearchMode.DEFERRED, catalog(null).resolveMode("deferred"));
    }

    @Test
    void resolveModeShouldLetAClientReturnToListed() {
        McpToolSearchSpec spec = toolSearch();
        spec.setMode("deferred");

        assertEquals(ToolSearchMode.LISTED, catalog(spec).resolveMode("listed"));
    }

    @Test
    void resolveModeShouldRejectAnUnknownValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> catalog(null).resolveMode("Deferred"));

        assertEquals("Unsupported toolSearch value 'Deferred'; expected listed or deferred",
                error.getMessage());
    }

    @Test
    void resolveModeShouldIgnoreTheParameterWhenClientsCannotSelect() {
        McpToolSearchSpec spec = toolSearch();
        spec.setClientSelectable(false);

        assertEquals(ToolSearchMode.LISTED, catalog(spec).resolveMode("deferred"));
        assertEquals(ToolSearchMode.LISTED, catalog(spec).resolveMode("anything"));
    }

    @Test
    void resolveModeShouldFallBackToListedWithoutAProvider() {
        assertEquals(ToolSearchMode.LISTED,
                catalog(null, Optional.empty()).resolveMode("deferred"));
    }

    // ── load-time validation ────────────────────────────────────────────────────────────────────

    @Test
    void constructorShouldFailWhenDeferredIsDeclaredWithoutAProvider() {
        McpToolSearchSpec spec = toolSearch();
        spec.setMode("deferred");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> catalog(spec, Optional.empty()));
        assertTrue(error.getMessage().contains("Add the ikanos-tool-search module"),
                error.getMessage());
    }

    @Test
    void constructorShouldAcceptListedWithoutAProvider() {
        McpToolSearchSpec spec = toolSearch();
        spec.setMode("listed");

        assertFalse(catalog(spec, Optional.empty()).isAvailable());
    }

    @Test
    void constructorShouldFailWhenAlwaysListedNamesAnUnknownTool() {
        McpToolSearchSpec spec = toolSearch();
        spec.setAlwaysListed(List.of("get-incident", "launch-rocket"));

        assertThrows(IllegalArgumentException.class, () -> catalog(spec));
    }

    @Test
    void constructorShouldFailWhenBothMetaToolsShareAName() {
        McpToolSearchSpec spec = toolSearch();
        spec.setSearchToolName("find");
        spec.setCallToolName("find");

        assertThrows(IllegalArgumentException.class, () -> catalog(spec));
    }

    @Test
    void constructorShouldFailWhenADeclaredMetaToolNameIsATool() {
        McpToolSearchSpec spec = toolSearch();
        spec.setSearchToolName("search-logs");

        assertThrows(IllegalArgumentException.class, () -> catalog(spec));
    }

    @Test
    void constructorShouldOnlyDisableClientSelectionWhenAnUndeclaredMetaToolNameIsATool() {
        List<McpSchema.Tool> tools = new java.util.ArrayList<>(adapter.getTools());
        tools.add(McpSchema.Tool.builder("search-tools", Map.<String, Object>of("type", "object"))
                .build());
        serverSpec.setToolSearch(null);

        ToolSearchCatalog catalog = new ToolSearchCatalog(serverSpec, tools,
                adapter.getToolLabels(), adapter.getToolTags(), Optional.of(provider));

        assertFalse(catalog.isClientSelectable());
        assertEquals(ToolSearchMode.LISTED, catalog.resolveMode("deferred"));
    }

    // ── pinned and searchable tools ─────────────────────────────────────────────────────────────

    @Test
    void pinnedToolsShouldBeAlwaysListedPlusDestructiveToolsInAuthoredOrder() {
        McpToolSearchSpec spec = toolSearch();
        spec.setAlwaysListed(List.of("get-on-call", "get-incident"));

        assertEquals(List.of("get-incident", "delete-namespace", "get-on-call",
                "cancel-subscription", "rotate-api-key"), names(catalog(spec).pinnedTools()));
    }

    @Test
    void searchableToolsShouldBeEveryOtherTool() {
        ToolSearchCatalog catalog = catalog(null);

        assertEquals(37, catalog.searchableTools().size());
        assertFalse(names(catalog.searchableTools()).contains("delete-namespace"));
    }

    @Test
    void deferDestructiveShouldLetDestructiveToolsBeSearched() {
        McpToolSearchSpec spec = toolSearch();
        spec.setDeferDestructive(true);
        ToolSearchCatalog catalog = catalog(spec);

        assertEquals(List.of(), catalog.pinnedTools());
        assertTrue(catalog.metaTools().get(1).annotations().destructiveHint(),
                "call-tool must warn approval prompts when it can reach a destructive tool");
    }

    @Test
    void callToolShouldNotBeDestructiveWhenDestructiveToolsArePinned() {
        assertFalse(catalog(null).metaTools().get(1).annotations().destructiveHint());
    }

    // ── meta-tools ──────────────────────────────────────────────────────────────────────────────

    @Test
    void metaToolsShouldUseTheConfiguredNames() {
        McpToolSearchSpec spec = toolSearch();
        spec.setSearchToolName("find-ops-tools");
        spec.setCallToolName("run-ops-tool");
        ToolSearchCatalog catalog = catalog(spec);

        assertEquals(List.of("find-ops-tools", "run-ops-tool"), names(catalog.metaTools()));
        assertTrue(catalog.metaTools().getFirst().description()
                .endsWith("Call a returned tool with run-ops-tool."));
    }

    @Test
    void searchDescriptionShouldListSortedCategoriesOfSearchableTools() {
        String description = catalog(null).searchDescription(null);

        assertEquals("Search the ops tools and return their full definitions. Categories: billing,"
                + " ci, customers, database, deployments, feature-flags, incidents, kubernetes,"
                + " logs, monitoring, on-call, releases, security, status-page, support."
                + " Call a returned tool with call-tool.", description);
    }

    @Test
    void searchDescriptionShouldEndTheServerDescriptionWithAPeriod() {
        assertTrue(catalog(null).searchDescription("Ops tools")
                .startsWith("Search the ops tools and return their full definitions. Ops tools."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void callToolShouldAdvertiseMaxToolCallsAsTheLimitOfCalls() {
        McpToolSearchSpec spec = toolSearch();
        spec.setMaxToolCalls(3);
        Map<String, Object> properties = (Map<String, Object>) catalog(spec).metaTools().get(1)
                .inputSchema().get("properties");

        assertEquals(3, ((Map<String, Object>) properties.get("calls")).get("maxItems"));
    }

    @Test
    void metaToolsShouldBeIdenticalAcrossCatalogs() {
        assertEquals(catalog(null).metaTools(), catalog(null).metaTools());
    }

    // ── documents and search ────────────────────────────────────────────────────────────────────

    @Test
    void documentsShouldCarryParametersTagsAndReadOnly() {
        ToolSearchDocument document = catalog(null).documents().stream()
                .filter(d -> d.name().equals("get-pod-logs")).findFirst().orElseThrow();

        assertEquals(List.of("kubernetes", "logs"), document.tags());
        assertTrue(document.readOnly());
        assertEquals("pod Name of the pod. lines Number of lines to return.", document.parameters());
    }

    @Test
    void searchShouldBuildTheIndexOnceOnFirstUse() {
        ToolSearchCatalog catalog = catalog(null);
        assertEquals(0, created.get());

        catalog.search(new ToolSearchQuery("a", Set.of(), false, 1));
        catalog.search(new ToolSearchQuery("b", Set.of(), false, 1));

        assertEquals(1, created.get());
    }

    @Test
    void searchShouldReportAMissingProviderAsUnavailable() {
        ToolSearchCatalog catalog = catalog(null, Optional.empty());

        assertThrows(ToolSearchUnavailableException.class,
                () -> catalog.search(new ToolSearchQuery("a", Set.of(), false, 1)));
    }

    @Test
    void searchShouldReportAFailingProviderAsUnavailable() {
        ToolSearchIndexFactory broken = new ToolSearchIndexFactory() {
            @Override
            public String type() {
                return "broken";
            }

            @Override
            public ToolSearchIndex create(List<ToolSearchDocument> documents) {
                throw new NoClassDefFoundError("org/apache/lucene/index/IndexWriter");
            }
        };
        ToolSearchCatalog catalog = catalog(null, Optional.of(broken));

        assertThrows(ToolSearchUnavailableException.class,
                () -> catalog.search(new ToolSearchQuery("a", Set.of(), false, 1)));
    }

    @Test
    void parametersTextShouldIncludeNestedProperties() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("labels",
                Map.of("type", "object", "description", "Labels.", "properties",
                        Map.of("team", Map.of("type", "string", "description", "Owning team.")))));

        assertEquals("labels Labels. team Owning team.", ToolSearchCatalog.parametersText(schema));
    }
}
