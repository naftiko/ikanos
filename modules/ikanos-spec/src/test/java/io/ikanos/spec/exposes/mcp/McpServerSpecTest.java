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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Unit tests for {@link McpServerSpec}.
 */
public class McpServerSpecTest {

    @Test
    public void noArgConstructorShouldInitializeEmptyCollections() {
        McpServerSpec spec = new McpServerSpec();

        assertEquals("mcp", spec.getType());
        assertNull(spec.getAddress());
        assertEquals(0, spec.getPort());
        assertNull(spec.getNamespace());
        assertNull(spec.getDescription());
        assertNotNull(spec.getTools());
        assertTrue(spec.getTools().isEmpty());
        assertNotNull(spec.getResources());
        assertTrue(spec.getResources().isEmpty());
        assertNotNull(spec.getPrompts());
        assertTrue(spec.getPrompts().isEmpty());
    }

    @Test
    public void getTransportShouldDefaultToHttpWhenNotSet() {
        McpServerSpec spec = new McpServerSpec();
        assertEquals("http", spec.getTransport());
        assertFalse(spec.isStdio());
    }

    @Test
    public void getTransportShouldReturnExplicitValueWhenSet() {
        McpServerSpec spec = new McpServerSpec();
        spec.setTransport("stdio");
        assertEquals("stdio", spec.getTransport());
        assertTrue(spec.isStdio());
    }

    @Test
    public void isStdioShouldBeFalseForNonStdioTransport() {
        McpServerSpec spec = new McpServerSpec();
        spec.setTransport("http");
        assertFalse(spec.isStdio());
    }

    @Test
    public void allArgsConstructorShouldSetFields() {
        McpServerSpec spec = new McpServerSpec("0.0.0.0", 8123, "ns", "desc");

        assertEquals("0.0.0.0", spec.getAddress());
        assertEquals(8123, spec.getPort());
        assertEquals("ns", spec.getNamespace());
        assertEquals("desc", spec.getDescription());
    }

    @Test
    public void settersShouldRoundTripValues() {
        McpServerSpec spec = new McpServerSpec();
        spec.setNamespace("my-ns");
        spec.setDescription("my desc");
        spec.setTransport("stdio");

        assertEquals("my-ns", spec.getNamespace());
        assertEquals("my desc", spec.getDescription());
        assertEquals("stdio", spec.getTransport());
    }

    @Test
    public void shouldDeserializeFromYaml() throws Exception {
        String yaml = """
                address: 127.0.0.1
                port: 9001
                namespace: pets-mcp
                description: MCP server for the Pets API
                transport: http
                """;

        ObjectMapper mapper = IkanosMappers.yaml();
        McpServerSpec spec = mapper.readValue(yaml, McpServerSpec.class);

        assertEquals("127.0.0.1", spec.getAddress());
        assertEquals(9001, spec.getPort());
        assertEquals("pets-mcp", spec.getNamespace());
        assertEquals("MCP server for the Pets API", spec.getDescription());
        assertEquals("http", spec.getTransport());
    }

    @Test
    public void deserializeShouldReadHintsWhenDeclaredOnServer() throws Exception {
        String yaml = """
                namespace: gong-mcp
                hints:
                  readOnly: true
                  openWorld: true
                """;

        McpServerSpec spec = IkanosMappers.yaml().readValue(yaml, McpServerSpec.class);

        assertNotNull(spec.getHints());
        assertEquals(Boolean.TRUE, spec.getHints().getReadOnly());
        assertNull(spec.getHints().getDestructive());
        assertNull(spec.getHints().getIdempotent());
        assertEquals(Boolean.TRUE, spec.getHints().getOpenWorld());
    }

    @Test
    public void getHintsShouldBeNullWhenServerDeclaresNone() {
        assertNull(new McpServerSpec().getHints());
    }

    @Test
    public void serializeShouldWriteHintsWhenDeclaredOnServer() {
        McpServerSpec spec = new McpServerSpec(null, 0, "gong-mcp", null);
        spec.setHints(new McpToolHintsSpec(true, null, null, true));

        JsonNode node = IkanosMappers.json().valueToTree(spec);

        assertTrue(node.path("hints").path("readOnly").asBoolean());
        assertTrue(node.path("hints").path("openWorld").asBoolean());
    }

    @Test
    public void serializeShouldOmitHintsWhenServerDeclaresNone() {
        JsonNode node = IkanosMappers.json().valueToTree(new McpServerSpec(null, 0, "gong-mcp", null));

        assertTrue(node.path("hints").isMissingNode());
    }

    @Test
    public void resolveToolHintsShouldReturnServerHintsWhenToolDeclaresNone() {
        McpServerSpec server = new McpServerSpec();
        server.setHints(new McpToolHintsSpec(true, null, null, true));
        McpServerToolSpec tool = new McpServerToolSpec("list-calls", null, "List calls");

        McpToolHintsSpec resolved = server.resolveToolHints(tool);

        assertEquals(Boolean.TRUE, resolved.getReadOnly());
        assertNull(resolved.getDestructive());
        assertNull(resolved.getIdempotent());
        assertEquals(Boolean.TRUE, resolved.getOpenWorld());
    }

    @Test
    public void resolveToolHintsShouldOverrideOnlyDeclaredFieldsWhenToolDeclaresHints() {
        McpServerSpec server = new McpServerSpec();
        server.setHints(new McpToolHintsSpec(true, null, null, true));
        McpServerToolSpec tool = new McpServerToolSpec("update-task", null, "Update a task");
        tool.setHints(new McpToolHintsSpec(false, null, true, null));

        McpToolHintsSpec resolved = server.resolveToolHints(tool);

        assertEquals(Boolean.FALSE, resolved.getReadOnly());
        assertNull(resolved.getDestructive());
        assertEquals(Boolean.TRUE, resolved.getIdempotent());
        assertEquals(Boolean.TRUE, resolved.getOpenWorld());
    }

    @Test
    public void resolveToolHintsShouldReturnToolHintsWhenServerDeclaresNone() {
        McpServerSpec server = new McpServerSpec();
        McpServerToolSpec tool = new McpServerToolSpec("update-task", null, "Update a task");
        McpToolHintsSpec toolHints = new McpToolHintsSpec(false, true, null, null);
        tool.setHints(toolHints);

        assertSame(toolHints, server.resolveToolHints(tool));
    }

    @Test
    public void resolveToolHintsShouldReturnNullWhenNeitherServerNorToolDeclaresHints() {
        McpServerSpec server = new McpServerSpec();
        McpServerToolSpec tool = new McpServerToolSpec("list-calls", null, "List calls");

        assertNull(server.resolveToolHints(tool));
    }

    @Test
    public void resolveToolHintsShouldLeaveToolSpecUnchangedWhenServerDeclaresHints() {
        McpServerSpec server = new McpServerSpec();
        server.setHints(new McpToolHintsSpec(true, null, null, true));
        McpServerToolSpec tool = new McpServerToolSpec("list-calls", null, "List calls");

        server.resolveToolHints(tool);

        assertNull(tool.getHints(), "server defaults must not be copied into the tool spec");
    }
}
