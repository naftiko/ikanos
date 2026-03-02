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
import io.naftiko.Capability;
import io.naftiko.engine.exposes.McpProtocolDispatcher;
import io.naftiko.engine.exposes.McpServerAdapter;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.exposes.StdioJsonRpcHandler;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;

/**
 * Integration tests for MCP Server Adapter with stdio transport.
 * Tests YAML deserialization, transport selection, and stdio JSON-RPC protocol.
 */
public class CapabilityMcpStdioIntegrationTest {

    private Capability capability;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mcp-stdio-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(),
                "MCP stdio capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

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
    public void testStdioInitializeProtocol() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        // Simulate an initialize request via the protocol dispatcher
        McpProtocolDispatcher dispatcher = new McpProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        String initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\","
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"},"
                + "\"capabilities\":{}}}";

        JsonNode request = mapper.readTree(initRequest);
        JsonNode response = dispatcher.dispatch(request);

        assertNotNull(response, "Initialize should return a response");
        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(1, response.path("id").asInt());

        JsonNode result = response.get("result");
        assertNotNull(result, "Should have a result");
        assertEquals("2025-03-26", result.path("protocolVersion").asText());
        assertEquals("test-mcp-stdio",
                result.path("serverInfo").path("name").asText());
    }

    @Test
    public void testStdioToolsListProtocol() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpProtocolDispatcher dispatcher = new McpProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        String listRequest = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

        JsonNode response = dispatcher.dispatch(mapper.readTree(listRequest));

        assertNotNull(response);
        JsonNode tools = response.path("result").path("tools");
        assertTrue(tools.isArray(), "tools should be an array");
        assertEquals(1, tools.size(), "Should list one tool");
        assertEquals("query-database", tools.get(0).path("name").asText());
    }

    @Test
    public void testStdioNotificationReturnsNull() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpProtocolDispatcher dispatcher = new McpProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

        JsonNode response = dispatcher.dispatch(mapper.readTree(notification));
        assertNull(response, "Notifications should return null (no response)");
    }

    @Test
    public void testStdioPingProtocol() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpProtocolDispatcher dispatcher = new McpProtocolDispatcher(adapter);
        ObjectMapper mapper = new ObjectMapper();

        String pingRequest = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";

        JsonNode response = dispatcher.dispatch(mapper.readTree(pingRequest));

        assertNotNull(response);
        assertEquals(3, response.path("id").asInt());
        assertNotNull(response.get("result"), "Ping should return an empty result");
    }

    @Test
    public void testStdioHandlerEndToEnd() throws Exception {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpProtocolDispatcher dispatcher = new McpProtocolDispatcher(adapter);

        // Build a multi-line input: initialize + tools/list + ping
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\","
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"},"
                + "\"capabilities\":{}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(
                input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StdioJsonRpcHandler handler = new StdioJsonRpcHandler(dispatcher, in, out);
        handler.run(); // Runs synchronously since input is finite

        String output = out.toString(StandardCharsets.UTF_8);
        String[] lines = output.strip().split("\\n");

        // Should have 3 responses (notification has no response)
        assertEquals(3, lines.length,
                "Should have 3 response lines (initialize, tools/list, ping)");

        ObjectMapper mapper = new ObjectMapper();

        // Verify initialize response
        JsonNode initResponse = mapper.readTree(lines[0]);
        assertEquals(1, initResponse.path("id").asInt());
        assertEquals("2025-03-26",
                initResponse.path("result").path("protocolVersion").asText());

        // Verify tools/list response
        JsonNode toolsResponse = mapper.readTree(lines[1]);
        assertEquals(2, toolsResponse.path("id").asInt());
        assertEquals("query-database",
                toolsResponse.path("result").path("tools").get(0).path("name").asText());

        // Verify ping response
        JsonNode pingResponse = mapper.readTree(lines[2]);
        assertEquals(3, pingResponse.path("id").asInt());
    }

    @Test
    public void testHttpTransportDefaultWhenNotSet() throws Exception {
        // Load the original MCP capability (no transport field)
        String resourcePath = "src/test/resources/mcp-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        Capability httpCapability = new Capability(spec);
        McpServerAdapter adapter =
                (McpServerAdapter) httpCapability.getServerAdapters().get(0);

        assertEquals("http", adapter.getMcpServerSpec().getTransport(),
                "Transport should default to 'http' when not set");
        assertFalse(adapter.getMcpServerSpec().isStdio(),
                "isStdio() should return false for default transport");
    }

}
