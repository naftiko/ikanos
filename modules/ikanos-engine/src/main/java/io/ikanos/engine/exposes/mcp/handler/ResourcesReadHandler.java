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
import io.ikanos.engine.exposes.mcp.ResourceHandler;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import org.restlet.Context;

import java.util.List;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * The resources/read handler.
 */
public class ResourcesReadHandler extends McpCallHandler {

    public static final String METHOD_NAME = "resources/read";

    public ResourcesReadHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode idNode = requestBody.get("id");
        JsonNode params = requestBody.get("params");

        if (params == null) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: missing params"));
        }
        String uri = params.path("uri").asText("");
        if (uri.isEmpty()) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: uri is required"));
        }

        try {
            List<ResourceHandler.ResourceContent> contents =
                    adapter.getResourceHandler().read(uri);
            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode contentsArray = result.putArray("contents");
            for (ResourceHandler.ResourceContent c : contents) {
                ObjectNode contentNode = MAPPER.createObjectNode();
                contentNode.put("uri", c.uri);
                if (c.mimeType != null) {
                    contentNode.put("mimeType", c.mimeType);
                }
                if (c.blob != null) {
                    contentNode.put("blob", c.blob);
                } else {
                    contentNode.put("text", c.text != null ? c.text : "");
                }
                contentsArray.add(contentNode);
            }

            result.put("resultType", "complete");
            return new HandlerSuccessResult(result);
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling resources/read", e);
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: " + e.getMessage()));
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling resources/read", e);
            return new HandlerFailureResult(INTERNAL_ERROR, buildJsonRpcError(idNode, INTERNAL_ERROR.getCode(), "Internal error: " + e.getMessage()));
        }
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
