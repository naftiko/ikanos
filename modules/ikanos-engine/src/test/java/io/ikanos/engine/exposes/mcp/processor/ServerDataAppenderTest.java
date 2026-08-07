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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.exception.ProcessorException;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerDataAppenderTest {

    static ObjectMapper mapper = new ObjectMapper();
    static InfoSpec info = mock(InfoSpec.class);
    static IkanosSpec spec = mock(IkanosSpec.class);
    static Capability capability = mock(Capability.class);
    static McpServerAdapter adapter = mock(McpServerAdapter.class);
    static McpServerSpec mcpServerSpec = mock(McpServerSpec.class);
    DispatchPostProcessor processor = new ServerDataAppender(adapter, mapper);

    @BeforeAll
    static void setup() {
        when(info.getVersion()).thenReturn("1.0.0");
        when(spec.getInfo()).thenReturn(info);
        when(mcpServerSpec.getNamespace()).thenReturn("ikanos");
        when(capability.getSpec()).thenReturn(spec);
        when(adapter.getCapability()).thenReturn(capability);
        when(adapter.getMcpServerSpec()).thenReturn(mcpServerSpec);
    }

    @Test
    void applyShouldAddServerData() throws ProcessorException {
        // Given
        ObjectNode response = mapper.createObjectNode();
        response.set("result", mapper.createObjectNode());

        // When
        processor.apply(response);

        // Then
        assertThat(response.path("jsonrpc").asText()).isEqualTo(ProtocolDispatcher.JSONRPC_VERSION);
        assertThat(response.path("result").path("_meta").path("io.modelcontextprotocol/serverInfo")
                .path("name").asText()).isEqualTo("ikanos");
        assertThat(response.path("result").path("_meta").path("io.modelcontextprotocol/serverInfo")
                .path("version").asText()).isEqualTo("1.0.0");
    }
}
