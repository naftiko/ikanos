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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProtocolDispatcherCoverageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void dispatchShouldReturnInternalErrorWhenRequestIsNull() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        ObjectNode response = dispatcher.dispatch(null).responseBody();

        assertNotNull(response);
        assertEquals(-32600, response.path("error").path("code").asInt());
        assertEquals("Invalid Request: request body is missing",
                response.path("error").path("message").asText());
        assertEquals("2.0", response.path("jsonrpc").asText());
    }

    @Test
    void serverDiscoverShouldAdvertiseOnlyToolsWhenNoResourcesOrPrompts() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        JsonNode response = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}
                """))).responseBody();

        JsonNode capabilities = response.path("result").path("capabilities");
        assertFalse(capabilities.path("tools").isMissingNode());
        assertTrueMissing(capabilities.path("resources"));
        assertTrueMissing(capabilities.path("prompts"));
    }

    @Test
    void toolsCallUnknownToolShouldReturnInvalidParams() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        JsonNode response = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "method":"tools/call",
                  "params":{"name":"does-not-exist","arguments":{}}
                }
                """))).responseBody();

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertFalse(response.path("error").path("message").asText().isBlank());
    }

    @Test
    void resourcesAndPromptsInvalidParamsShouldReturnExpectedErrors() throws Exception {
        ProtocolDispatcher dispatcher =
                dispatcherFrom("src/test/resources/mcp/mcp-resources-prompts-capability.yaml");

        JsonNode readNullParams = dispatcher.dispatch(JSON.readTree(withProtocolVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\"}"))).responseBody();
        assertEquals(-32602, readNullParams.path("error").path("code").asInt());

        JsonNode readUnknownUri = dispatcher.dispatch(JSON.readTree(withProtocolVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"data://unknown\"}}")))
                .responseBody();
        assertEquals(-32602, readUnknownUri.path("error").path("code").asInt());

        JsonNode promptsNullParams = dispatcher.dispatch(JSON.readTree(withProtocolVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\"}"))).responseBody();
        assertEquals(-32602, promptsNullParams.path("error").path("code").asInt());
    }

    @Test
    void toolsCallShouldReturnIsErrorResultOnUnexpectedExecutionFailure() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        JsonNode response = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {
                    "jsonrpc":"2.0",
                    "id":15,
                    "method":"tools/call",
                    "params":{
                        "name":"query-database",
                        "arguments":{"name":"x"}
                    }
                }
                """))).responseBody();

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(15, response.path("id").asInt());
        assertEquals(true, response.path("result").path("isError").asBoolean());
        assertEquals("text", response.path("result").path("content").get(0).path("type")
                        .asText());
    }

    private static ProtocolDispatcher dispatcherFrom(String resourcePath) throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = yaml.readValue(new File(resourcePath), IkanosSpec.class);
        Capability capability = new Capability(spec);
        return new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));
    }

    private static String withProtocolVersion(String requestJson) throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree(requestJson);
        ObjectNode params = request.has("params") && request.get("params").isObject()
                ? (ObjectNode) request.get("params")
                : request.putObject("params");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return JSON.writeValueAsString(request);
    }

    private static void assertTrueMissing(JsonNode node) {
        assertEquals(true, node.isMissingNode());
    }
}