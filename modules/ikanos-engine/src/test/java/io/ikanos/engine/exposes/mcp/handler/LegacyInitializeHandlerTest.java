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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.ikanos.engine.exposes.mcp.ProtocolDispatcher.MCP_PROTOCOL_VERSION;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LegacyInitializeHandlerTest {

    ObjectMapper mapper = new ObjectMapper();
    McpServerAdapter adapter = mock();
    McpCallHandler handler = new LegacyInitializeHandler(adapter, List.of(), List.of(), List.of());

    @Test
    void handleShouldRejectWithUnsupportedProtocolVersionNamingSupportedAndRequested()
            throws Exception {
        // Given
        JsonNode request = mapper.readTree("""
                {"jsonrpc":"2.0","id":0,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25","capabilities":{}}}
                """);

        // When
        HandlerFailureResult result = (HandlerFailureResult) handler.handle(request);

        // Then
        JsonNode error = result.body().path("error");
        assertThat(result.rpcError()).isEqualTo(UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(result.body().path("id").asInt()).isZero();
        assertThat(error.path("code").asInt()).isEqualTo(UNSUPPORTED_PROTOCOL_VERSION.getCode());
        assertThat(error.path("data").path("supported").get(0).asText())
                .isEqualTo(MCP_PROTOCOL_VERSION);
        assertThat(error.path("data").path("requested").asText()).isEqualTo("2025-11-25");
        assertThat(error.path("message").asText())
                .contains("2025-11-25")
                .contains(MCP_PROTOCOL_VERSION);
    }

    @Test
    void handleShouldNameSupportedVersionWhenRequestedVersionIsMissing() throws Exception {
        // Given
        JsonNode request = mapper.readTree("""
                {"jsonrpc":"2.0","id":"init-1","method":"initialize"}
                """);

        // When
        HandlerFailureResult result = (HandlerFailureResult) handler.handle(request);

        // Then
        JsonNode error = result.body().path("error");
        assertThat(result.body().path("id").asText()).isEqualTo("init-1");
        assertThat(error.path("data").path("requested").asText()).isEmpty();
        assertThat(error.path("message").asText())
                .startsWith("Unsupported protocol version:")
                .contains(MCP_PROTOCOL_VERSION);
    }
}
