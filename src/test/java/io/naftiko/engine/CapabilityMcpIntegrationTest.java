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
package io.naftiko.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.McpServerAdapter;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.consumes.HttpClientAdapter;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import java.io.File;

/**
 * Integration tests for MCP Server Adapter.
 * Tests YAML deserialization, spec wiring, tool building, and adapter lifecycle.
 */
public class CapabilityMcpIntegrationTest {

    private Capability capability;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mcp-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(), "MCP capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        capability = new Capability(spec);
    }

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability, "Capability should be initialized");
        assertNotNull(capability.getSpec(), "Capability spec should be loaded");
        assertEquals("0.4", capability.getSpec().getNaftiko(), "Naftiko version should be 0.4");
    }

    @Test
    public void testMcpServerAdapterCreated() {
        assertFalse(capability.getServerAdapters().isEmpty(),
                "Capability should have at least one server adapter");

        ServerAdapter adapter = capability.getServerAdapters().get(0);
        assertInstanceOf(McpServerAdapter.class, adapter,
                "First server adapter should be McpServerAdapter");
    }

    @Test
    public void testMcpServerSpecDeserialized() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec spec = adapter.getMcpServerSpec();

        assertEquals("mcp", spec.getType(), "Type should be 'mcp'");
        assertEquals("localhost", spec.getAddress(), "Address should be 'localhost'");
        assertEquals(9095, spec.getPort(), "Port should be 9095");
        assertEquals("test-mcp", spec.getNamespace(), "Namespace should be 'test-mcp'");
        assertNotNull(spec.getDescription(), "Description should not be null");
        assertTrue(spec.getDescription().contains("Test MCP server"),
                "Description should contain expected text");
    }

    @Test
    public void testMcpToolSpecDeserialized() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec spec = adapter.getMcpServerSpec();

        assertEquals(1, spec.getTools().size(), "Should have exactly one tool");

        McpServerToolSpec tool = spec.getTools().get(0);
        assertEquals("query-database", tool.getName(), "Tool name should be 'query-database'");
        assertTrue(tool.getDescription().contains("Query the test database"),
                "Tool description should contain expected text");

        // call spec
        assertNotNull(tool.getCall(), "Tool should have a call spec");
        assertEquals("mock-api.query-db", tool.getCall().getOperation(),
                "Call operation should reference the consumed operation");

        // with parameters
        assertNotNull(tool.getWith(), "Tool should have 'with' parameters");
        assertEquals("test-db-id-123", tool.getWith().get("datasource_id"),
                "With should contain datasource_id");

        // output parameters
        assertNotNull(tool.getOutputParameters(), "Tool should have output parameters");
        assertFalse(tool.getOutputParameters().isEmpty(),
                "Output parameters should not be empty");
    }

    @Test
    public void testMcpToolsBuilt() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        assertNotNull(adapter.getTools(), "MCP tools list should not be null");
        assertEquals(1, adapter.getTools().size(), "Should have one MCP tool");

        McpSchema.Tool mcpTool = adapter.getTools().get(0);
        assertEquals("query-database", mcpTool.name(), "MCP tool name should match spec");
        assertTrue(mcpTool.description().contains("Query the test database"),
                "MCP tool description should match spec");
        assertNotNull(mcpTool.inputSchema(), "MCP tool should have input schema");
    }

    @Test
    public void testMcpToolHandlerCreated() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        assertNotNull(adapter.getToolHandler(), "Tool handler should be created");
    }

    @Test
    public void testHttpClientAdapterWired() {
        assertFalse(capability.getClientAdapters().isEmpty(),
                "Capability should have at least one client adapter");

        var clientAdapter = capability.getClientAdapters().get(0);
        assertInstanceOf(HttpClientAdapter.class, clientAdapter,
                "Client adapter should be HttpClientAdapter");

        HttpClientAdapter httpClient = (HttpClientAdapter) clientAdapter;
        assertEquals("mock-api", httpClient.getHttpClientSpec().getNamespace(),
                "Client namespace should be 'mock-api'");
        assertEquals("http://localhost:8080/v1/",
                httpClient.getHttpClientSpec().getBaseUri(),
                "Client baseUri should match spec");
    }

    @Test
    public void testMcpAdapterStartAndStop() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        // Start the adapter — Jetty server should bind to port
        adapter.start();

        // Stop the adapter — should clean up
        adapter.stop();

        // No exception means the lifecycle works
    }

}
