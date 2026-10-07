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
package io.ikanos.engine.exposes.mcp.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

import java.util.List;

import static io.ikanos.engine.exposes.mcp.ProtocolDispatcher.MCP_PROTOCOL_VERSION;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * Rejects the legacy {@code initialize} handshake with an {@code UnsupportedProtocolVersionError}.
 *
 * <p>The engine only speaks the modern, handshake-free {@value
 * io.ikanos.engine.exposes.mcp.ProtocolDispatcher#MCP_PROTOCOL_VERSION} revision. Per the spec's
 * backward-compatibility section, a modern-only server SHOULD name the protocol versions it
 * supports in any error it returns to {@code initialize}: legacy clients have no fall-forward
 * mechanism, and the error message may be the only diagnostic they can show. The versions are
 * therefore named both in {@code error.data.supported} and in the human-readable message.</p>
 *
 * <p>Legacy clients send no MCP request headers and no {@code _meta}, so this handler must be
 * registered without required headers or the protocol-version pre-processor.</p>
 */
public class LegacyInitializeHandler extends McpCallHandler {

    public static final String METHOD_NAME = "initialize";

    private static final String MESSAGE_FORMAT = "Unsupported protocol version%s: this server supports "
            + "only MCP %s, which has no initialize handshake";

    public LegacyInitializeHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders,
            List<DispatchPreProcessor> preProcessors,
            List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        // Legacy clients declare their version in params.protocolVersion, not in _meta.
        String requested = requestBody.path("params").path("protocolVersion").asText("");

        ObjectNode data = MAPPER.createObjectNode();
        data.putArray("supported").add(MCP_PROTOCOL_VERSION);
        data.put("requested", requested);

        String message = MESSAGE_FORMAT.formatted(
                requested.isEmpty() ? "" : " " + requested, MCP_PROTOCOL_VERSION);
        return new HandlerFailureResult(UNSUPPORTED_PROTOCOL_VERSION, buildJsonRpcError(
                requestBody.get("id"), UNSUPPORTED_PROTOCOL_VERSION.getCode(), message, data));
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
