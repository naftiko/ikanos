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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;

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
        NaftikoSpec spec = yamlMapper.readValue(file, NaftikoSpec.class);
        Capability capability = new Capability(spec);
        dispatcher = new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));
    }

    @Test
    public void dispatchShouldRejectInvalidJsonRpcVersion() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"1.0","id":1,"method":"ping"}
                """));

        assertEquals(-32600, response.path("error").path("code").asInt());
    }

    @Test
    public void dispatchShouldRejectUnknownMethod() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"unknown/method"}
                """));

        assertEquals(-32601, response.path("error").path("code").asInt());
    }

    @Test
    public void dispatchShouldRejectToolsCallWithoutParams() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call"}
                """));

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertEquals("Invalid params: missing params", response.path("error").path("message").asText());
    }

    @Test
    public void dispatchShouldRejectResourcesReadWithoutUri() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{}}
                """));

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertEquals("Invalid params: uri is required", response.path("error").path("message").asText());
    }

    @Test
    public void dispatchShouldRejectPromptsGetWithoutName() throws Exception {
        JsonNode response = dispatcher.dispatch(mapper.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{}}
                """));

        assertNotNull(response.path("error"));
        assertEquals(-32602, response.path("error").path("code").asInt());
        assertEquals("Invalid params: name is required", response.path("error").path("message").asText());
    }
}