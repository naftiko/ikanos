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
import io.ikanos.spec.exposes.mcp.McpPromptArgumentSpec;
import io.ikanos.spec.exposes.mcp.McpServerPromptSpec;

import java.util.List;

/**
 * The prompts/list handler.
 */
public class PromptsListHandler extends McpCallHandler {

    private static final String METHOD_NAME = "prompts/list";

    public PromptsListHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode promptsArray = result.putArray("prompts");

        for (McpServerPromptSpec spec : adapter.getPromptHandler().listAll()) {
            ObjectNode promptNode = MAPPER.createObjectNode();
            promptNode.put("name", spec.getName());
            if (spec.getDisplay() != null) {
                promptNode.put("title", spec.getDisplay());
            }
            if (spec.getDescription() != null) {
                promptNode.put("description", spec.getDescription());
            }
            if (!spec.getArguments().isEmpty()) {
                ArrayNode argsArray = promptNode.putArray("arguments");
                for (McpPromptArgumentSpec arg : spec.getArguments().values()) {
                    ObjectNode argNode = MAPPER.createObjectNode();
                    argNode.put("name", arg.getName());
                    if (arg.getDisplay() != null) {
                        argNode.put("title", arg.getDisplay());
                    }
                    if (arg.getDescription() != null) {
                        argNode.put("description", arg.getDescription());
                    }
                    argNode.put("required", arg.isRequired());
                    argsArray.add(argNode);
                }
            }
            promptsArray.add(promptNode);
        }

        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
