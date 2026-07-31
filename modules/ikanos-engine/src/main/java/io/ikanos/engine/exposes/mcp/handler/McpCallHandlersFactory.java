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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.processor.CacheDataAppender;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.exposes.mcp.processor.ProtocolsVersionsValidator;
import io.ikanos.engine.exposes.mcp.processor.ServerDataAppender;

import java.util.List;

import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_METHOD;
import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_NAME;
import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_PROTOCOL_VERSION;

/**
 * A factory class for creating {@link McpCallHandler}s.
 */
public class McpCallHandlersFactory {

    private McpCallHandlersFactory() {
        // Utility class — no instances.
    }

    public static List<McpCallHandler> createAll(McpServerAdapter adapter) {
        Integer ttlMs = adapter.getMcpServerSpec().getTtlMs();
        String cacheScope = adapter.getMcpServerSpec().getCacheScope();

        ObjectMapper mapper = new ObjectMapper();
        DispatchPreProcessor protocolsVersionsValidator = new ProtocolsVersionsValidator(mapper);
        DispatchPostProcessor cacheDataAppender = new CacheDataAppender(ttlMs, cacheScope);
        DispatchPostProcessor serverDataAppender = new ServerDataAppender(adapter, mapper);

        return List.of(
                new ToolsListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new ToolsCallHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                        List.of(protocolsVersionsValidator), List.of(serverDataAppender)),
                new ResourcesListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new ResourcesReadHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new ResourcesTemplatesListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new PromptsListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new PromptsGetHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                        List.of(protocolsVersionsValidator), List.of(serverDataAppender)),
                new ServerDiscoverHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender))
        );
    }
}
