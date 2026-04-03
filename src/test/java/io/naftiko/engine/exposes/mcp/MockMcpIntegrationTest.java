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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;

/**
 * Integration tests for MCP mock mode.
 * Validates the full chain: YAML fixture → deserialization → ToolHandler → mock response.
 */
public class MockMcpIntegrationTest {

    private Capability capability;
    private McpServerSpec mcpSpec;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mock-mcp-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Mock MCP capability file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        capability = new Capability(spec);
        mcpSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
    }

    @Test
    void capabilityShouldLoadWithNoConsumesBlock() {
        assertTrue(capability.getClientAdapters().isEmpty(),
                "Mock capability should have no client adapters");
    }

    @Test
    void mcpSpecShouldHaveTwoTools() {
        assertEquals(2, mcpSpec.getTools().size());
        assertEquals("say-hello", mcpSpec.getTools().get(0).getName());
        assertEquals("get-profile", mcpSpec.getTools().get(1).getName());
    }

    @Test
    void sayHelloShouldReturnMockGreeting() throws Exception {
        ToolHandler handler = new ToolHandler(capability, mcpSpec.getTools(),
                mcpSpec.getNamespace());

        McpSchema.CallToolResult result =
                handler.handleToolCall("say-hello", Map.of("name", "Alice"));

        assertFalse(result.isError(), "Mock tool should not return an error");
        assertNotNull(result.content());
        assertEquals(1, result.content().size());

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Hello, World!"),
                "Response should contain the const value");
    }

    @Test
    void getProfileShouldReturnMockProfile() throws Exception {
        ToolHandler handler = new ToolHandler(capability, mcpSpec.getTools(),
                mcpSpec.getNamespace());

        McpSchema.CallToolResult result =
                handler.handleToolCall("get-profile", Map.of());

        assertFalse(result.isError(), "Mock tool should not return an error");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Naftiko"));
        assertTrue(text.contains("Engineer"));
        assertTrue(text.contains("Earth"));
    }

    @Test
    void mockToolShouldBuildValidMcpToolDefinition() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        assertNotNull(adapter.getTools());
        assertEquals(2, adapter.getTools().size());

        McpSchema.Tool sayHello = adapter.getTools().get(0);
        assertEquals("say-hello", sayHello.name());
        assertNotNull(sayHello.inputSchema());
    }
}
