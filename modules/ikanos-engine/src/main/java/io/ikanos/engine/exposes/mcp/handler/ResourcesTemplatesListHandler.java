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
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;

import java.util.List;

/**
 * The resources/templates/list handler.
 */
public class ResourcesTemplatesListHandler extends McpCallHandler {

    public static final String METHOD_NAME = "resources/templates/list";

    public ResourcesTemplatesListHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode templatesArray = result.putArray("resourceTemplates");

        for (McpServerResourceSpec spec : adapter.getResourceHandler().listTemplates()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("uriTemplate", spec.getUri());
            node.put("name", spec.getName());
            if (spec.getDisplay() != null) {
                node.put("title", spec.getDisplay());
            }
            if (spec.getDescription() != null) {
                node.put("description", spec.getDescription());
            }
            if (spec.getMimeType() != null) {
                node.put("mimeType", spec.getMimeType());
            }
            templatesArray.add(node);
        }

        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
