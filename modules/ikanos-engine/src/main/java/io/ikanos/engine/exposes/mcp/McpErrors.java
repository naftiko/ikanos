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

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;
import java.util.logging.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.ErrorReference;

/**
 * Builds the JSON-RPC error responses the MCP adapter returns for unexpected failures.
 */
public final class McpErrors {

    private McpErrors() {}

    /**
     * Log an unexpected failure and build the JSON-RPC internal-error envelope for it. The caller
     * receives only a generic message plus a correlation identifier; the exception detail stays in
     * the log under the same identifier. See {@link ErrorReference}.
     */
    public static ObjectNode internalError(JsonNode id, String logContext, Throwable cause) {
        String ref = ErrorReference.record(Level.SEVERE, logContext, cause);
        return buildJsonRpcError(id, INTERNAL_ERROR.getCode(),
                ErrorReference.withReference("Internal error", ref));
    }
}
