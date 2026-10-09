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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProtocolDispatcherNegativeTest {

    private ObjectMapper mapper;
    private ProtocolDispatcher dispatcher;

    @BeforeEach
    public void setUp() throws Exception {
        mapper = new ObjectMapper();
        String resourcePath = "src/test/resources/mcp/mcp-resources-prompts-capability.yaml";
        File file = new File(resourcePath);
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = yamlMapper.readValue(file, IkanosSpec.class);
        Capability capability = new Capability(spec);
        dispatcher = new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));
    }

    @Test
    public void dispatchShouldRejectInvalidJsonRpcVersion() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree(withProtocolVersion("""
                {"jsonrpc":"1.0","id":1,"method":"tools/list"}
                """))).responseBody();

        assertEquals(-32600, response.path("error").path("code").asInt());
    }

    @Test
    public void dispatchShouldRejectUnknownMethod() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"unknown/method"}
                """)).responseBody();

        assertEquals(-32601, response.path("error").path("code").asInt());
    }

    @Test
    public void dispatchShouldRejectToolsCallWithoutName() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call"}
                """))).responseBody();

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertEquals("Invalid params: Unknown tool: ", response.path("error").path("message").asText());
    }

    @Test
    public void dispatchShouldRejectResourcesReadWithoutUri() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{}}
                """))).responseBody();

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertEquals("Invalid params: uri is required", response.path("error").path("message").asText());
    }

    @Test
    public void dispatchShouldRejectPromptsGetWithoutName() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{}}
                """))).responseBody();

        assertNotNull(response.path("error"));
        assertEquals(-32602, response.path("error").path("code").asInt());
        assertEquals("Invalid params: name is required", response.path("error").path("message").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prompts/get", "tools/call", "resources/read"})
    public void dispatchShouldRejectWithoutProtocolVersion(String method) throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"%s"}
                """.formatted(method))).responseBody();

        assertNotNull(response.path("error"));
        assertEquals(-32022, response.path("error").path("code").asInt());
        assertEquals("Unsupported protocol version", response.path("error").path("message").asText());
    }

    @Test
    public void dispatchShouldRejectLegacyInitializeNamingTheSupportedVersions() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":0,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"legacy-client","version":"1.0.0"}}}
                """)).responseBody();

        JsonNode error = response.path("error");
        assertEquals(-32022, error.path("code").asInt(),
                "#732: a legacy initialize must get UnsupportedProtocolVersionError, not "
                        + "'Method not found'");
        assertEquals(0, response.path("id").asInt());
        assertEquals(ProtocolDispatcher.MCP_PROTOCOL_VERSION,
                error.path("data").path("supported").path(0).asText(),
                "#732: the error data must list the protocol versions the server supports");
        assertEquals("2025-11-25", error.path("data").path("requested").asText(),
                "#732: the error data must echo the version the legacy client asked for");
        assertTrue(error.path("message").asText().contains(ProtocolDispatcher.MCP_PROTOCOL_VERSION),
                "#732: legacy clients often surface only the message, so it must name the "
                        + "supported version, got: " + error.path("message").asText());
    }

    @Test
    public void dispatchShouldRejectLegacyInitializeWithoutProtocolVersion() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize"}
                """)).responseBody();

        JsonNode error = response.path("error");
        assertEquals(-32022, error.path("code").asInt(),
                "#732: an initialize without params must still get UnsupportedProtocolVersionError");
        assertEquals("", error.path("data").path("requested").asText());
        assertTrue(error.path("message").asText().contains(ProtocolDispatcher.MCP_PROTOCOL_VERSION));
    }

    @Test
    public void discoveryAndVersionErrorsShouldAdvertiseTheSameSupportedVersions() throws Exception {
        // The versions this server speaks are one fact. If server/discover, the per-request
        // version check and the legacy initialize error ever disagree, a client gets
        // contradictory answers depending on the path it hits (#732 review).
        JsonNode discovered = dispatcher.dispatch(mapper.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover"}
                """))).responseBody().path("result").path("supportedVersions");
        JsonNode rejectedVersion = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"1900-01-01"}}}
                """)).responseBody().path("error").path("data").path("supported");
        JsonNode rejectedInitialize = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                """)).responseBody().path("error").path("data").path("supported");

        assertTrue(discovered.isArray() && discovered.size() > 0,
                "server/discover must list at least one supported version");
        assertEquals(discovered, rejectedVersion,
                "UnsupportedProtocolVersionError must list the same versions as server/discover");
        assertEquals(discovered, rejectedInitialize,
                "The legacy initialize error must list the same versions as server/discover");
    }

    private String withProtocolVersion(String requestJson) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree(requestJson);
        ObjectNode params = request.has("params") && request.get("params").isObject()
                ? (ObjectNode) request.get("params")
                : request.putObject("params");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return mapper.writeValueAsString(request);
    }
}