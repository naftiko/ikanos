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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integration tests for MCP tool inputs declared without {@code type} (#986): the capability is
 * loaded end-to-end from YAML, and {@code tools/list} and {@code tools/call} are asserted on the
 * JSON-RPC wire through {@link ProtocolDispatcher}. The tools run in mock mode, so no upstream is
 * called.
 */
class McpUntypedInputIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    private McpServerAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        File file = new File("src/test/resources/mcp/mcp-untyped-input-capability.yaml");
        assertTrue(file.exists(), "Untyped input capability test file should exist");
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(file, IkanosSpec.class);
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    @Test
    void toolsListShouldAdvertiseStringForToolInputWithoutType() throws Exception {
        JsonNode schema = toolNamed("echo-owner").path("inputSchema");

        assertEquals("string", schema.path("properties").path("owner").path("type").asString());
        assertEquals("integer", schema.path("properties").path("page").path("type").asString());
    }

    @Test
    void toolsListShouldAdvertiseStringForAggregateFlowInputWithoutType() throws Exception {
        JsonNode schema = toolNamed("hello").path("inputSchema");

        assertEquals("string", schema.path("properties").path("name").path("type").asString());
    }

    @Test
    void toolsCallShouldAcceptStringForToolInputWithoutType() throws Exception {
        ObjectNode arguments = JSON.createObjectNode().put("owner", "naftiko");

        JsonNode result = call("echo-owner", arguments).path("result");

        assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
        assertTrue(result.toString().contains("naftiko"), "owner should be echoed: " + result);
    }

    @Test
    void toolsCallShouldWriteNumberAsTextForToolInputWithoutType() throws Exception {
        ObjectNode arguments = JSON.createObjectNode().put("owner", 42);

        JsonNode result = call("echo-owner", arguments).path("result");

        assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
        assertTrue(result.toString().contains("42"), "owner should be echoed: " + result);
    }

    @Test
    void toolsCallShouldRejectObjectForToolInputWithoutType() throws Exception {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.putObject("owner").put("$ne", "");

        JsonNode response = call("echo-owner", arguments);

        assertCallFailed(response);
    }

    @Test
    void toolsCallShouldRejectObjectForAggregateFlowInputWithoutType() throws Exception {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.putArray("name").add("a").add("b");

        JsonNode response = call("hello", arguments);

        assertCallFailed(response);
    }

    @Test
    void toolsCallShouldAcceptStringForAggregateFlowInputWithoutType() throws Exception {
        ObjectNode arguments = JSON.createObjectNode().put("name", "Ada");

        JsonNode result = call("hello", arguments).path("result");

        assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
        assertTrue(result.toString().contains("Hello, Ada!"), "greeting expected: " + result);
    }

    private static void assertCallFailed(JsonNode response) {
        assertTrue(response.has("error") || response.path("result").path("isError").asBoolean(),
                "a non-string value for an untyped (string) input must fail: " + response);
    }

    private JsonNode toolNamed(String name) throws Exception {
        JsonNode tools = dispatch("tools/list", JSON.createObjectNode())
                .path("result").path("tools");
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asString())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name + " in " + tools);
    }

    private JsonNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        return dispatch("tools/call", params);
    }

    private JsonNode dispatch(String method, ObjectNode params) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }
}
