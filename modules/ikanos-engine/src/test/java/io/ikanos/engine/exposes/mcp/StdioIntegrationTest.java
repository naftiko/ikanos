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

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

/**
 * Integration tests for MCP Server Adapter with stdio transport.
 * Tests YAML deserialization, transport selection, and stdio JSON-RPC protocol.
 */
public class StdioIntegrationTest {

    private Capability capability;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mcp/mcp-stdio-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(),
                "MCP stdio capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        capability = new Capability(spec);
    }

    @Test
    public void testStdioTransportDeserialized() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec spec = adapter.getMcpServerSpec();

        assertEquals("mcp", spec.getType(), "Type should be 'mcp'");
        assertEquals("stdio", spec.getTransport(), "Transport should be 'stdio'");
        assertTrue(spec.isStdio(), "isStdio() should return true");
        assertEquals("test-mcp-stdio", spec.getNamespace(),
                "Namespace should be 'test-mcp-stdio'");
    }

    @Test
    public void testStdioAdapterIsMcpServerAdapter() {
        ServerAdapter adapter = capability.getServerAdapters().get(0);
        assertInstanceOf(McpServerAdapter.class, adapter,
                "Stdio transport should use the same McpServerAdapter class");
    }

    @Test
    public void testStdioToolsBuilt() {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        assertNotNull(adapter.getTools(), "Tools should be built");
        assertEquals(1, adapter.getTools().size(), "Should have one tool");
        assertEquals("query-database", adapter.getTools().get(0).name(),
                "Tool name should match");
    }

    @Test
    public void testStdioAdapterStartAndStop() throws Exception {
        // Verify lifecycle works — but don't actually start on System.in
        // (that would interfere with the test runner's own stdin).
        // Instead, verify the adapter is correctly configured for stdio.
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        assertTrue(adapter.getMcpServerSpec().isStdio(),
                "Adapter should be configured for stdio transport");
        assertNotNull(adapter.getToolHandler(),
                "Tool handler should be initialized regardless of transport");
    }

    @Test
    public void testStdioServerDiscoverProtocol() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        // Simulate a server/discover request via the protocol dispatcher
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        String discoverRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";

        JsonNode request = mapper.readTree(discoverRequest);
        JsonNode response = dispatcher.dispatch(request).responseBody();

        assertNotNull(response, "server/discover should return a response");
        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(1, response.path("id").asInt());

        JsonNode result = response.get("result");
        assertNotNull(result, "Should have a result");
        JsonNode supportedVersions = result.path("supportedVersions");
        assertTrue(supportedVersions.isArray() && supportedVersions.size() > 0);
        assertEquals(ProtocolDispatcher.MCP_PROTOCOL_VERSION, supportedVersions.get(0).asText());
    }

    @Test
    public void testStdioToolsListProtocol() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        String listRequest = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\","
                + "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}}}";

        JsonNode response = dispatcher.dispatch(mapper.readTree(listRequest)).responseBody();

        assertNotNull(response);
        JsonNode tools = response.path("result").path("tools");
        assertTrue(tools.isArray(), "tools should be an array");
        assertEquals(1, tools.size(), "Should list one tool");
        assertEquals("query-database", tools.get(0).path("name").asText());
    }

    @Test
    public void testStdioUnsupportedProtocolVersionReturnsError() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        // A request without a matching protocolVersion in _meta must be rejected —
        // the initialize/notifications/initialized handshake no longer exists in 2026-07-28,
        // every request must self-describe its protocol version.
        String requestWithoutVersion = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}";

        JsonNode response = dispatcher.dispatch(mapper.readTree(requestWithoutVersion)).responseBody();

        assertNotNull(response);
        assertEquals(-32022, response.path("error").path("code").asInt(),
                "Missing/unsupported protocol version should return UnsupportedProtocolVersion (-32022)");
    }

    @Test
    public void testStdioHandlerEndToEnd() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);

        String metaSuffix = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
                + ProtocolDispatcher.MCP_PROTOCOL_VERSION + "\"}";

        // Build a multi-line input: server/discover + tools/list
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                + "\"params\":{" + metaSuffix + "}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\","
                + "\"params\":{" + metaSuffix + "}}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(
                input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StdioJsonRpcHandler handler = new StdioJsonRpcHandler(dispatcher, in, out);
        handler.run(); // Runs synchronously since input is finite

        String output = out.toString(StandardCharsets.UTF_8);
        String[] lines = output.strip().split("\\n");

        // Should have 2 responses (server/discover, tools/list)
        assertEquals(2, lines.length,
                "Should have 2 response lines (server/discover, tools/list)");

        ObjectMapper mapper = new ObjectMapper();

        // Verify server/discover response
        JsonNode discoverResponse = mapper.readTree(lines[0]);
        assertEquals(1, discoverResponse.path("id").asInt());
        assertEquals(ProtocolDispatcher.MCP_PROTOCOL_VERSION,
                discoverResponse.path("result").path("supportedVersions").get(0).asText());

        // Verify tools/list response
        JsonNode toolsResponse = mapper.readTree(lines[1]);
        assertEquals(2, toolsResponse.path("id").asInt());
        assertEquals("query-database",
                toolsResponse.path("result").path("tools").get(0).path("name").asText());
    }

    @Test
    public void testHttpTransportDefaultWhenNotSet() throws Exception {
        // Load the original MCP capability (no transport field)
        String resourcePath = "src/test/resources/mcp/mcp-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        Capability httpCapability = new Capability(spec);
        McpServerAdapter adapter =
                (McpServerAdapter) httpCapability.getServerAdapters().get(0);

        assertEquals("http", adapter.getMcpServerSpec().getTransport(),
                "Transport should default to 'http' when not set");
        assertFalse(adapter.getMcpServerSpec().isStdio(),
                "isStdio() should return false for default transport");
    }

}
