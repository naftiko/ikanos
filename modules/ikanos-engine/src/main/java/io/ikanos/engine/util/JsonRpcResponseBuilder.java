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
package io.ikanos.engine.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static io.ikanos.engine.exposes.mcp.ProtocolDispatcher.JSONRPC_VERSION;

/**
 * A helper class for building Json-Rpc response bodies.
 */
public final class JsonRpcResponseBuilder {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpcResponseBuilder() {
        // Hidden constructor
    }

    /**
     * Build a JSON-RPC error response envelope.
     */
    public static ObjectNode buildJsonRpcError(JsonNode id, int code, String message) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);

        if (id != null) {
            envelope.set("id", id);
        } else {
            envelope.putNull("id");
        }

        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return envelope;
    }

    /**
     * Build a JSON-RPC error response envelope.
     */
    public static ObjectNode buildJsonRpcError(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);

        if (id != null) {
            envelope.set("id", id);
        } else {
            envelope.putNull("id");
        }

        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        if (message != null) {
            error.put("message", message);
        }
        error.set("data", data);
        return envelope;
    }
}
