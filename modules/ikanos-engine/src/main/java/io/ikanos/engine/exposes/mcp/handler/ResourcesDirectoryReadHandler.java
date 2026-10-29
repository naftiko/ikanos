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
import io.ikanos.engine.exposes.mcp.SkillHandler;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

import java.util.List;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * The resources/directory/read handler of the Skills Extension (SEP-2640): lists the direct
 * children of a skill directory in one page, so a request carrying a cursor is rejected: this
 * server never issues one. Unknown URIs and files return {@code -32602}.
 */
public class ResourcesDirectoryReadHandler extends McpCallHandler {

    public static final String METHOD_NAME = "resources/directory/read";

    public ResourcesDirectoryReadHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode idNode = requestBody.get("id");
        String uri = requestBody.path("params").path("uri").asString("");
        if (uri.isEmpty()) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: uri is required"));
        }
        JsonNode cursor = requestBody.path("params").path("cursor");
        if (!cursor.isMissingNode() && !cursor.isNull()) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: unknown cursor"));
        }

        List<SkillHandler.DirectoryChild> children;
        try {
            children = adapter.getSkillHandler().readDirectory(uri);
        } catch (IllegalArgumentException e) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: " + e.getMessage()));
        }

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode resources = result.putArray("resources");
        for (SkillHandler.DirectoryChild child : children) {
            ObjectNode node = resources.addObject();
            node.put("uri", child.uri());
            node.put("name", child.name());
            node.put("mimeType", child.mimeType());
            if (child.size() != null) {
                node.put("size", child.size());
            }
        }
        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
