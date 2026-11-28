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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.McpToolJson;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.model.McpRequestContext;
import io.ikanos.engine.exposes.mcp.model.RequestClientCapabilities;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.engine.exposes.mcp.search.ToolSearchMode;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * The tools/list handler.
 */
public class ToolsListHandler extends McpCallHandler {

    public static final String METHOD_NAME = "tools/list";

    public ToolsListHandler(McpServerAdapter adapter,
                            List<McpHeader> requiredHeaders,
                            List<DispatchPreProcessor> preProcessors,
                            List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        return handle(requestBody, McpRequestContext.of(adapter.getToolSearchCatalog()));
    }

    /**
     * Lists the tools in authored order, keeping only those that carry one of the context's tags
     * when the request sets a tag filter. The filter limits discovery, not access: a tool left
     * out here can still be called by name.
     *
     * <p>In deferred mode, the list is the two meta-tools, never filtered, followed by the pinned
     * tools. It never changes between requests, so it stays in the client's cached prompt
     * prefix; the model finds the other tools with the search meta-tool.</p>
     *
     * <p>A tool that takes a file ({@code type: file}) is left out for a request that does not
     * declare the files extension, as the draft recommends: such a client could not fill its file
     * argument.</p>
     */
    @Override
    public HandlerResult handle(JsonNode requestBody, McpRequestContext context) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");
        Map<String, String> labels = adapter.getToolLabels();
        Map<String, List<String>> tags = adapter.getToolTags();
        ToolSearchCatalog catalog = adapter.getToolSearchCatalog();

        List<McpSchema.Tool> listed = adapter.getTools();
        if (context.mode() == ToolSearchMode.DEFERRED) {
            for (McpSchema.Tool metaTool : catalog.metaTools()) {
                toolsArray.add(McpToolJson.write(metaTool, labels, MAPPER));
            }
            listed = catalog.pinnedTools();
        }
        // Files extension (experimental): a tool that takes a file is listed only to a client
        // that declares the extension, which can fill its file object
        boolean files = RequestClientCapabilities.from(requestBody.path("params"))
                .supportsFiles();
        for (McpSchema.Tool tool : listed) {
            if (!files && adapter.takesFiles(tool.name())) {
                continue;
            }
            if (context.acceptsTags(tags.getOrDefault(tool.name(), List.of()))) {
                toolsArray.add(McpToolJson.write(tool, labels, MAPPER));
            }
        }

        result.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
