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

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResourcesReadHandlerTest {

    ObjectMapper mapper = new ObjectMapper();
    McpServerAdapter adapter = mock();
    McpCallHandler handler = new ResourcesReadHandler(adapter, List.of(), List.of(), List.of());

    @Test
    void handleShouldRejectWithoutParams() throws Exception {
        // Given
        JsonNode request = mapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read"}
                """);

        // When
        HandlerFailureResult result = (HandlerFailureResult) handler.handle(request);

        // Then
        assertThat(result.rpcError()).isEqualTo(INVALID_PARAMS);
    }
}