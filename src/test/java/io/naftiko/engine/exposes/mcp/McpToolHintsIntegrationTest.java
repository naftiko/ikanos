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
package io.naftiko.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import io.naftiko.spec.exposes.McpToolHintsSpec;
import java.io.File;

/**
 * Integration tests for MCP tool hints (ToolAnnotations) support. Validates YAML deserialization,
 * spec-to-SDK mapping, and wire format generation.
 */
public class McpToolHintsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Capability capability;
    private McpServerAdapter adapter;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mcp-hints-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "MCP hints capability test file should exist");

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        capability = new Capability(spec);
        adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
    }

    @Test
    void hintsSpecShouldDeserializeFromYaml() {
        McpServerSpec serverSpec = adapter.getMcpServerSpec();
        McpServerToolSpec readTool = serverSpec.getTools().get(0);

        McpToolHintsSpec hints = readTool.getHints();
        assertNotNull(hints, "Hints should be deserialized for read-data tool");
        assertEquals(true, hints.getReadOnly());
        assertEquals(true, hints.getOpenWorld());
        assertNull(hints.getDestructive(), "Unset hints should be null");
        assertNull(hints.getIdempotent(), "Unset hints should be null");
    }

    @Test
    void allHintsShouldDeserializeWhenFullySpecified() {
        McpServerSpec serverSpec = adapter.getMcpServerSpec();
        McpServerToolSpec deleteTool = serverSpec.getTools().get(1);

        McpToolHintsSpec hints = deleteTool.getHints();
        assertNotNull(hints);
        assertEquals(false, hints.getReadOnly());
        assertEquals(true, hints.getDestructive());
        assertEquals(true, hints.getIdempotent());
        assertEquals(false, hints.getOpenWorld());
    }

    @Test
    void toolWithoutHintsShouldHaveNullHintsSpec() {
        McpServerSpec serverSpec = adapter.getMcpServerSpec();
        McpServerToolSpec noHintsTool = serverSpec.getTools().get(2);

        assertNull(noHintsTool.getHints(), "Tool without hints should have null hints");
    }

    @Test
    void buildToolAnnotationsShouldMapHintsAndLabel() {
        McpServerToolSpec toolSpec = new McpServerToolSpec("test", "Test Title", "desc");
        toolSpec.setHints(new McpToolHintsSpec(true, false, true, false));

        McpSchema.ToolAnnotations annotations = adapter.buildToolAnnotations(toolSpec);

        assertNotNull(annotations);
        assertEquals("Test Title", annotations.title());
        assertEquals(true, annotations.readOnlyHint());
        assertEquals(false, annotations.destructiveHint());
        assertEquals(true, annotations.idempotentHint());
        assertEquals(false, annotations.openWorldHint());
    }

    @Test
    void buildToolAnnotationsShouldReturnNullWhenNoHintsAndNoLabel() {
        McpServerToolSpec toolSpec = new McpServerToolSpec("test", null, "desc");

        McpSchema.ToolAnnotations annotations = adapter.buildToolAnnotations(toolSpec);

        assertNull(annotations, "Should return null when no hints and no label");
    }

    @Test
    void buildToolAnnotationsShouldMapLabelOnlyWhenNoHints() {
        McpServerToolSpec toolSpec = new McpServerToolSpec("test", "Label Only", "desc");

        McpSchema.ToolAnnotations annotations = adapter.buildToolAnnotations(toolSpec);

        assertNotNull(annotations);
        assertEquals("Label Only", annotations.title());
        assertNull(annotations.readOnlyHint());
        assertNull(annotations.destructiveHint());
        assertNull(annotations.idempotentHint());
        assertNull(annotations.openWorldHint());
    }

    @Test
    void toolsListShouldIncludeAnnotationsInWireFormat() throws Exception {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);

        JsonNode response = dispatcher.dispatch(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        JsonNode tools = response.path("result").path("tools");
        assertEquals(3, tools.size(), "Should have three tools");

        // read-data tool: has label + readOnly + openWorld hints
        JsonNode readTool = tools.get(0);
        assertEquals("read-data", readTool.path("name").asText());
        assertEquals("Read Data", readTool.path("title").asText());

        JsonNode readAnnotations = readTool.path("annotations");
        assertFalse(readAnnotations.isMissingNode(), "read-data should have annotations");
        assertEquals("Read Data", readAnnotations.path("title").asText());
        assertEquals(true, readAnnotations.path("readOnlyHint").asBoolean());
        assertEquals(true, readAnnotations.path("openWorldHint").asBoolean());
        assertTrue(readAnnotations.path("destructiveHint").isMissingNode(),
                "Unset hints should be absent from wire format");

        // delete-record tool: no label, full hints
        JsonNode deleteTool = tools.get(1);
        assertEquals("delete-record", deleteTool.path("name").asText());

        JsonNode deleteAnnotations = deleteTool.path("annotations");
        assertFalse(deleteAnnotations.isMissingNode());
        assertTrue(deleteAnnotations.path("title").isMissingNode(),
                "Tool without label should not have annotations.title");
        assertEquals(false, deleteAnnotations.path("readOnlyHint").asBoolean());
        assertEquals(true, deleteAnnotations.path("destructiveHint").asBoolean());
        assertEquals(true, deleteAnnotations.path("idempotentHint").asBoolean());
        assertEquals(false, deleteAnnotations.path("openWorldHint").asBoolean());

        // no-hints-tool: no annotations at all
        JsonNode noHintsTool = tools.get(2);
        assertEquals("no-hints-tool", noHintsTool.path("name").asText());
        assertTrue(noHintsTool.path("annotations").isMissingNode(),
                "Tool without hints or label should have no annotations");
    }
}
