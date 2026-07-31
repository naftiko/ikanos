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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
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
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");
        Map<String, String> labels = adapter.getToolLabels();

        for (McpSchema.Tool tool : adapter.getTools()) {
            ObjectNode toolNode = MAPPER.createObjectNode();
            toolNode.put("name", tool.name());

            String title = labels.get(tool.name());
            if (title != null) {
                toolNode.put("title", title);
            }

            if (tool.description() != null) {
                toolNode.put("description", tool.description());
            }

            if (tool.inputSchema() != null) {
                toolNode.set("inputSchema", MAPPER.valueToTree(tool.inputSchema()));
            }

            if (tool.annotations() != null) {
                ObjectNode annotationsNode = MAPPER.createObjectNode();
                McpSchema.ToolAnnotations ann = tool.annotations();
                if (ann.title() != null) {
                    annotationsNode.put("title", ann.title());
                }
                if (ann.readOnlyHint() != null) {
                    annotationsNode.put("readOnlyHint", ann.readOnlyHint());
                }
                if (ann.destructiveHint() != null) {
                    annotationsNode.put("destructiveHint", ann.destructiveHint());
                }
                if (ann.idempotentHint() != null) {
                    annotationsNode.put("idempotentHint", ann.idempotentHint());
                }
                if (ann.openWorldHint() != null) {
                    annotationsNode.put("openWorldHint", ann.openWorldHint());
                }
                if (!annotationsNode.isEmpty()) {
                    toolNode.set("annotations", annotationsNode);
                }
            }

            toolsArray.add(toolNode);
        }

        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
