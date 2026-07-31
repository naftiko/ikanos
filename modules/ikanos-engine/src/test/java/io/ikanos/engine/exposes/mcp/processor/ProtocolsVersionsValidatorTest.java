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
package io.ikanos.engine.exposes.mcp.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.exception.ProcessorException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProtocolsVersionsValidatorTest {

    ObjectMapper mapper = new ObjectMapper();
    DispatchPreProcessor processor = new ProtocolsVersionsValidator(mapper);

    @Test
    void applyWithValidRequestShouldSucceed() throws Exception {
        // Given
        JsonNode request = mapper.readTree("""
                {
                  "jsonrpc": "%s",
                  "id": 1,
                  "method": "tools/list",
                  "params": {
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "%s"
                    }
                  }
                }""".formatted(ProtocolDispatcher.JSONRPC_VERSION, ProtocolDispatcher.MCP_PROTOCOL_VERSION));

        // When, Then
        Assertions.assertDoesNotThrow(() -> processor.apply(request));
    }

    @Test
    void applyWithInvalidRequestShouldThrow() throws Exception {
        // Given
        JsonNode requestWithBadJsonRpcVersion = mapper.readTree("""
                {
                  "jsonrpc": "0.1",
                  "id": 1,
                  "method": "tools/list",
                  "params": {
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "%s"
                    }
                  }
                }""".formatted(ProtocolDispatcher.MCP_PROTOCOL_VERSION));
        JsonNode requestWithBadProtocolVersion = mapper.readTree("""
                {
                  "jsonrpc": "%s",
                  "id": 1,
                  "method": "tools/list",
                  "params": {
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "2018-01-01"
                    }
                  }
                }""".formatted(ProtocolDispatcher.JSONRPC_VERSION));

        // When, Then
        Assertions.assertThrows(ProcessorException.class, () -> processor.apply(requestWithBadJsonRpcVersion));
        Assertions.assertThrows(ProcessorException.class, () -> processor.apply(requestWithBadProtocolVersion));
    }
}