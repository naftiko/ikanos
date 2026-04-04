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
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;

public class ToolHandlerTest {

    @Test
    public void handleToolCallShouldThrowForUnknownTool() {
        McpServerToolSpec tool = new McpServerToolSpec();
        tool.setName("known-tool");

        ToolHandler handler = new ToolHandler(null, List.of(tool));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handler.handleToolCall("unknown-tool", Map.of()));

        assertTrue(error.getMessage().contains("Unknown tool"));
    }

    @Test
    public void handleToolCallShouldHandleNullArguments() throws Exception {
        McpServerToolSpec tool = new McpServerToolSpec();
        tool.setName("test-tool");
        tool.setWith(Map.of("default_param", "default_value"));
        // No call or steps — mock mode returns an empty JSON object

        ToolHandler handler = new ToolHandler(null, List.of(tool));

        var result = handler.handleToolCall("test-tool", null);
        assertNotNull(result);
    }

    @Test
    public void handleToolCallShouldMergeToolWithParameters() throws Exception {
        McpServerToolSpec tool = new McpServerToolSpec();
        tool.setName("test-tool");
        tool.setWith(Map.of("fromTool", "fromToolValue"));

        ToolHandler handler = new ToolHandler(null, List.of(tool));

        // No call or steps — mock mode returns an empty JSON object
        var result = handler.handleToolCall("test-tool",
                Map.of("fromArgs", "fromArgsValue"));
        assertNotNull(result);
    }

    /**
     * Regression test for #204.
     *
     * Before the fix, 'with' values containing Mustache templates (e.g. {@code "{{imo}}"}) were
     * inserted raw into the parameters map without resolution. This caused the HTTP URI
     * {@code /ships/{{imo_number}}} to remain unresolved as {@code /ships/{{imo}}}, triggering
     * an {@link IllegalArgumentException} about unresolved template parameters.
     *
     * After the fix, 'with' values are resolved as Mustache templates against the tool call
     * arguments before merging, so {@code "{{imo}}"} becomes {@code "IMO-9321483"} and the URI
     * resolves cleanly. The call may still fail at the HTTP level (no server), but must not
     * throw an IllegalArgumentException about unresolved templates.
     */
    @Test
    public void handleToolCallShouldResolveMustacheTemplatesInWithValues() throws Exception {
        String resourcePath = "src/test/resources/mcp/mcp-tool-handler-with-mustache-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Test capability file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
        Capability capability = new Capability(spec);

        McpServerSpec mcpSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
        ToolHandler handler = new ToolHandler(capability, mcpSpec.getTools());

        // Must NOT throw IllegalArgumentException("Unresolved template parameters in URI: ...")
        // (connection failure at HTTP level is acceptable — the template must be resolved first)
        assertDoesNotThrow(() -> handler.handleToolCall("get-ship", Map.of("imo", "IMO-9321483")));
    }
}
