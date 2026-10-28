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
package io.ikanos.engine.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Aggregate flow {@code mappings:} resolve through the same JSONPath engine as
 * {@code outputParameters[].mapping}, so array indexing and other JSONPath features work (#746).
 *
 * <p>The capability consumes only the test {@code type: fake} adapter, which returns a canned
 * listing in-process, so no test opens a network connection.</p>
 */
class AggregateMappingsJsonPathTest {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private Capability capability;

    @BeforeEach
    void setUp() throws Exception {
        File file = new File("src/test/resources/aggregates/aggregate-mappings-jsonpath.yaml");
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(file, IkanosSpec.class);
        capability = new Capability(spec);
    }

    private JsonNode executeFlow() throws Exception {
        return JSON.readTree(
                capability.lookupFlow("registry.first-record").execute(Map.of()).mappedOutput);
    }

    @Test
    void flowMappingsShouldResolveElementWhenValueHasArrayIndex() throws Exception {
        assertEquals(1, executeFlow().path("first-id").asInt(),
                "#746: $.list-records.records[0].id must resolve the first record's id, "
                        + "not silently resolve to null");
    }

    @Test
    void flowMappingsShouldResolveLastElementWhenValueHasNegativeIndex() throws Exception {
        assertEquals("Grace", executeFlow().path("last-name").asString(),
                "#746: a negative JSONPath index must resolve in an aggregate mapping");
    }

    @Test
    void flowMappingsShouldResolveArrayWhenValueHasWildcard() throws Exception {
        assertEquals(List.of(1, 2), JSON.convertValue(executeFlow().path("all-ids"), List.class),
                "#746: a JSONPath wildcard must resolve in an aggregate mapping");
    }

    @Test
    void mcpToolRefShouldReturnArrayIndexedMappingWhenFlowMapsArrayElement() throws Exception {
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpSchema.CallToolResult result =
                mcp.getToolHandler().handleToolCall("first-record", Map.of());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertNotNull(structured);
        assertEquals(1, ((Number) structured.get("first-id")).intValue(),
                "#746: the MCP tool must expose the array-indexed value mapped by the flow");
    }
}
