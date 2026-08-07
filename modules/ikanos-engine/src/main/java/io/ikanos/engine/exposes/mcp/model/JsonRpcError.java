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

/**
 * An enumeration listing all the JsonRpc errors relevant to MCP.
 */
public enum JsonRpcError {

    // Standard JSON-RPC 2.0 codes (MCP spec: "-32700", "-32600" to "-32603")
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603),

    // MCP-reserved sub-range (-32020 to -32099)
    HEADER_MISMATCH(-32020),
    MISSING_REQUIRED_CLIENT_CAPABILITY(-32021),
    UNSUPPORTED_PROTOCOL_VERSION(-32022);

    private final int code;

    JsonRpcError(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
