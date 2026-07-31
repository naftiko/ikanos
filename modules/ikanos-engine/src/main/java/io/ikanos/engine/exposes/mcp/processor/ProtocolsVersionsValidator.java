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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.exception.ProcessorException;

import static io.ikanos.engine.exposes.mcp.ProtocolDispatcher.JSONRPC_VERSION;
import static io.ikanos.engine.exposes.mcp.ProtocolDispatcher.MCP_PROTOCOL_VERSION;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_REQUEST;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * A pre-processor for validating the protocols' versions.
 */
public class ProtocolsVersionsValidator implements DispatchPreProcessor {

    private final ObjectMapper mapper;

    public ProtocolsVersionsValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void apply(JsonNode request) throws ProcessorException {
        String jsonrpc = request.path("jsonrpc").asText("");
        JsonNode idNode = request.get("id");
        String bodyProtocolVersion = request.path("params").path("_meta")
                .path("io.modelcontextprotocol/protocolVersion").asText("");

        if (!JSONRPC_VERSION.equals(jsonrpc)) {
            throw new ProcessorException("Invalid Request: jsonrpc must be '2.0', got %s".formatted(jsonrpc),
                    INVALID_REQUEST, buildJsonRpcError(idNode, INVALID_REQUEST.getCode(),
                    "Invalid Request: jsonrpc must be '2.0'"));
        }

        if (!MCP_PROTOCOL_VERSION.equals(bodyProtocolVersion)) {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode supported = data.putArray("supported");
            supported.add(MCP_PROTOCOL_VERSION);
            data.put("requested", bodyProtocolVersion);
            throw new ProcessorException("Unsupported protocol version %s".formatted(bodyProtocolVersion), UNSUPPORTED_PROTOCOL_VERSION,
                    buildJsonRpcError(idNode, UNSUPPORTED_PROTOCOL_VERSION.getCode(), "Unsupported protocol version", data));
        }
    }
}
