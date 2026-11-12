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

import tools.jackson.databind.ObjectMapper;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.processor.CacheDataAppender;
import io.ikanos.engine.exposes.mcp.processor.CacheHints;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.exposes.mcp.processor.ProtocolsVersionsValidator;
import io.ikanos.engine.exposes.mcp.processor.ServerDataAppender;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.restlet.Context;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_METHOD;
import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_NAME;
import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_PROTOCOL_VERSION;
import io.ikanos.spec.util.IkanosMappers;

/**
 * A factory class for creating {@link McpCallHandler}s.
 */
public class McpCallHandlersFactory {

    private McpCallHandlersFactory() {
        // Utility class — no instances.
    }

    public static List<McpCallHandler> createAll(McpServerAdapter adapter) {
        ObjectMapper mapper = IkanosMappers.json();
        DispatchPreProcessor protocolsVersionsValidator = new ProtocolsVersionsValidator(mapper);
        DispatchPostProcessor cacheDataAppender =
                new CacheDataAppender(CacheHints.forServer(adapter.getMcpServerSpec()));
        // Resolve every resource's hints now, so an invalid one fails at startup, not on a read,
        // and say which scope each result gets and why (cacheScope: auto is derived)
        McpServerSpec spec = adapter.getMcpServerSpec();
        Logger logger = Context.getCurrentLogger();
        logger.fine(() -> "MCP adapter " + spec.getNamespace() + " lists: cacheScope "
                + CacheHints.scopeDecision(spec, null).scope() + " ("
                + CacheHints.scopeDecision(spec, null).reason() + ")");
        for (McpServerResourceSpec resource : spec.getResources().values()) {
            CacheHints.forResource(spec, resource);
            logger.fine(() -> "MCP adapter " + spec.getNamespace() + " resource "
                    + resource.getName() + ": cacheScope "
                    + CacheHints.scopeDecision(spec, resource).scope() + " ("
                    + CacheHints.scopeDecision(spec, resource).reason() + ")");
        }
        DispatchPostProcessor serverDataAppender = new ServerDataAppender(adapter, mapper);

        List<McpCallHandler> handlers = new ArrayList<>(List.of(
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
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                // Takes no headers or processors: legacy clients send neither MCP headers nor _meta.
                new LegacyInitializeHandler(adapter)
        ));

        // Skills Extension (SEP-2640) methods exist only when the server serves skills.
        if (adapter.getSkillHandler() != null && !adapter.getSkillHandler().isEmpty()) {
            handlers.add(new SkillsListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                    List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)));
            handlers.add(new SkillsGetHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                    List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)));
            handlers.add(new ResourcesDirectoryReadHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                    List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)));
        }
        return List.copyOf(handlers);
    }
}
