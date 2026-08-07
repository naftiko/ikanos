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
package io.ikanos.engine.exposes.mcp.model;

import java.util.Map;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.HEADER_MISMATCH;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_REQUEST;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.METHOD_NOT_FOUND;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.PARSE_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION;

/**
 * A mapper that maps JsonRpc errors to the HTTP status rpcError
 * specified by the MCP specification.
 */
public class JsonRpcCodeMapper {

    private static final Map<JsonRpcError, Integer> JSON_RPC_CODE_TO_HTTP_STATUS = Map.ofEntries(
            // Malformed/invalid requests are client errors.
            Map.entry(PARSE_ERROR, 400),
            Map.entry(INVALID_REQUEST, 400),
            Map.entry(INVALID_PARAMS, 400),
            // The server doesn't implement the requested RPC method: distinguished from a
            // generic 400 so clients can tell it apart from a legacy HTTP+SSE 404 during
            // backward-compatibility probing.
            Map.entry(METHOD_NOT_FOUND, 404),
            // Unexpected failures while processing an otherwise well-formed request.
            Map.entry(INTERNAL_ERROR, 500),
            // Header/body mismatch or missing mandatory header.
            Map.entry(HEADER_MISMATCH, 400),
            // Request requires a client capability that was not declared in _meta.
            Map.entry(MISSING_REQUIRED_CLIENT_CAPABILITY, 400),
            // Requested protocol version is unknown or unsupported by the server.
            Map.entry(UNSUPPORTED_PROTOCOL_VERSION, 400)
    );

    private JsonRpcCodeMapper() {
        // Empty constructor
    }

    /**
     * Get the HTTP status rpcError based on the JsonRpc one according to the specification.
     * @param jsonRpcError the JsonRpc error.
     * @return a valid HTTP status rpcError if found, -1 otherwise.
     * @throws IllegalArgumentException in case no mapping exists for the {@link JsonRpcError}.
     */
    public static int getHttpStatusCodeOfJsonRpcCode(JsonRpcError jsonRpcError) {
        Integer httpStatus = JSON_RPC_CODE_TO_HTTP_STATUS.get(jsonRpcError);
        if (httpStatus == null) {
            throw new IllegalArgumentException("No mapping exists for the JsonRpc code: %s".formatted(jsonRpcError.getCode()));
        }

        return httpStatus;
    }
}
