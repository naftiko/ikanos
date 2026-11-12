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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the default tool {@code hints} of an exposed MCP server (#980). Loads a
 * capability through the full engine and asserts the annotations {@code tools/list} advertises:
 * each hint comes from the tool, else from the aggregate flow's semantics, else from the server.
 */
public class McpServerHintsIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    private Map<String, JsonNode> annotationsByTool;

    @BeforeEach
    public void setUp() throws Exception {
        File file = new File("src/test/resources/mcp/mcp-server-hints-capability.yaml");
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(file, IkanosSpec.class);
        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        JsonNode response = new ProtocolDispatcher(adapter).dispatch(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"%s\"}}}"
                        .formatted(ProtocolDispatcher.MCP_PROTOCOL_VERSION))).responseBody();

        annotationsByTool = new HashMap<>();
        for (JsonNode tool : response.path("result").path("tools")) {
            annotationsByTool.put(tool.path("name").asString(), tool.path("annotations"));
        }
    }

    @Test
    void toolsListShouldAdvertiseServerHintsWhenToolDeclaresNone() {
        JsonNode annotations = annotationsByTool.get("list-calls");

        assertTrue(annotations.path("readOnlyHint").asBoolean());
        assertTrue(annotations.path("destructiveHint").isMissingNode(),
                "a hint nobody declares stays absent");
        assertEquals(false, annotations.path("idempotentHint").asBoolean(true));
        assertTrue(annotations.path("openWorldHint").asBoolean());
    }

    @Test
    void toolsListShouldOverrideOnlyDeclaredServerHintsWhenToolDeclaresHints() {
        JsonNode annotations = annotationsByTool.get("update-task");

        assertEquals(false, annotations.path("readOnlyHint").asBoolean(true));
        assertTrue(annotations.path("destructiveHint").isMissingNode());
        assertTrue(annotations.path("idempotentHint").asBoolean());
        assertTrue(annotations.path("openWorldHint").asBoolean(), "kept from the server");
    }

    @Test
    void toolsListShouldAdvertiseNotReadOnlyWhenCallToolDeclaresOnlyDestructive() {
        JsonNode annotations = annotationsByTool.get("purge-calls");

        assertEquals(false, annotations.path("readOnlyHint").asBoolean(true),
                "a tool declared destructive is not read-only, whatever the server default");
        assertTrue(annotations.path("destructiveHint").asBoolean());
    }

    @Test
    void toolsListShouldResolveToolThenSemanticsThenServerWhenToolUsesRef() {
        JsonNode annotations = annotationsByTool.get("delete-task");

        assertEquals(false, annotations.path("readOnlyHint").asBoolean(true),
                "semantics safe: false wins over server readOnly: true");
        assertTrue(annotations.path("destructiveHint").asBoolean(), "from the tool");
        assertTrue(annotations.path("idempotentHint").asBoolean(),
                "semantics idempotent: true wins over server idempotent: false");
        assertTrue(annotations.path("openWorldHint").asBoolean(), "from the server");
    }
}
