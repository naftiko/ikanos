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

import java.util.List;
import java.util.Map;

/**
 * The resources/list handler.
 */
public class ResourcesListHandler extends McpCallHandler {

    public static final String METHOD_NAME = "resources/list";

    public ResourcesListHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode resourcesArray = result.putArray("resources");

        for (Map<String, String> entry : adapter.getResourceHandler().listAll()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("uri", entry.get("uri"));
            node.put("name", entry.get("name"));
            String title = entry.get("display");
            if (title != null) {
                node.put("title", title);
            }
            String description = entry.get("description");
            if (description != null) {
                node.put("description", description);
            }
            String mimeType = entry.get("mimeType");
            if (mimeType != null) {
                node.put("mimeType", mimeType);
            }
            resourcesArray.add(node);
        }

        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
