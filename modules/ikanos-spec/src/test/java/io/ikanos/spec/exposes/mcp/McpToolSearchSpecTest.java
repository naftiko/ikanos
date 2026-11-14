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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The {@code toolSearch} block of an MCP adapter: schema validation and the spec model.
 */
class McpToolSearchSpecTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpToolSearchSpecTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String toolSearch) {
        return """
                ikanos: "%s"
                info:
                  display: "Tool search"
                  description: "Tool search block test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "ops-api"
                      baseUri: "https://api.ops.test"
                      resources:
                        ops:
                          path: "/ops"
                          operations:
                            read:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      namespace: "ops"
                      port: 3001
                %s
                      tools:
                        get_incident:
                          description: "Get an incident."
                          call: "ops-api.read"
                """.formatted(IKANOS, toolSearch.indent(6).stripTrailing());
    }

    private static List<Error> validate(String toolSearch) throws Exception {
        return schema.validate(YAML.readTree(capability(toolSearch)));
    }

    @Test
    void schemaShouldAcceptEveryField() throws Exception {
        List<Error> errors = validate("""
                toolSearch:
                  mode: deferred
                  clientSelectable: false
                  maxResults: 8
                  maxToolCalls: 3
                  alwaysListed: [get_incident]
                  deferDestructive: true
                  searchToolName: find-tools
                  callToolName: run_tool
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectAnUnknownMode() throws Exception {
        assertFalse(validate("""
                toolSearch:
                  mode: lazy
                """).isEmpty());
    }

    @Test
    void schemaShouldRejectAnUnknownField() throws Exception {
        assertFalse(validate("""
                toolSearch:
                  strategy: bm25
                """).isEmpty());
    }

    @Test
    void schemaShouldRejectMaxResultsAboveFifty() throws Exception {
        assertFalse(validate("""
                toolSearch:
                  maxResults: 51
                """).isEmpty());
    }

    @Test
    void schemaShouldRejectMaxToolCallsAboveTwenty() throws Exception {
        assertFalse(validate("""
                toolSearch:
                  maxToolCalls: 21
                """).isEmpty());
    }

    @Test
    void deserializationShouldReadEveryField() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("""
                toolSearch:
                  mode: deferred
                  clientSelectable: false
                  maxResults: 8
                  maxToolCalls: 3
                  alwaysListed: [get_incident]
                  deferDestructive: true
                  searchToolName: find-tools
                  callToolName: run_tool
                """), IkanosSpec.class);
        McpToolSearchSpec toolSearch =
                ((McpServerSpec) spec.getCapability().getExposes().getFirst()).getToolSearch();

        assertEquals("deferred", toolSearch.modeOrDefault());
        assertFalse(toolSearch.clientSelectableOrDefault());
        assertEquals(8, toolSearch.maxResultsOrDefault());
        assertEquals(3, toolSearch.maxToolCallsOrDefault());
        assertEquals(List.of("get_incident"), toolSearch.getAlwaysListed());
        assertTrue(toolSearch.deferDestructiveOrDefault());
        assertEquals("find-tools", toolSearch.searchToolNameOrDefault());
        assertEquals("run_tool", toolSearch.callToolNameOrDefault());
    }

    @Test
    void omittedBlockShouldDeserializeToNull() throws Exception {
        IkanosSpec spec = YAML.readValue(capability(""), IkanosSpec.class);

        assertNull(((McpServerSpec) spec.getCapability().getExposes().getFirst()).getToolSearch());
    }

    @Test
    void emptyBlockShouldGiveTheDefaults() {
        McpToolSearchSpec toolSearch = new McpToolSearchSpec();

        assertEquals("listed", toolSearch.modeOrDefault());
        assertTrue(toolSearch.clientSelectableOrDefault());
        assertEquals(5, toolSearch.maxResultsOrDefault());
        assertEquals(5, toolSearch.maxToolCallsOrDefault());
        assertFalse(toolSearch.deferDestructiveOrDefault());
        assertEquals("search-tools", toolSearch.searchToolNameOrDefault());
        assertEquals("call-tool", toolSearch.callToolNameOrDefault());
    }

    @Test
    void serializationShouldWriteOnlyDeclaredFields() throws Exception {
        McpToolSearchSpec toolSearch = new McpToolSearchSpec();
        toolSearch.setMode("deferred");

        assertEquals("{\"mode\":\"deferred\"}", JSON.writeValueAsString(toolSearch));
    }
}
