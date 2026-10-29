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
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.SkillManifest;
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
 * The skills/get handler (SEP-2640): returns the entry of one skill, by the URI of its
 * {@code SKILL.md}, in the same shape as a {@code skills/list} entry.
 */
public class SkillsGetHandler extends McpCallHandler {

    public static final String METHOD_NAME = "skills/get";

    public SkillsGetHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode idNode = requestBody.get("id");
        String uri = requestBody.path("params").path("uri").asString("");
        if (uri.isEmpty()) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: uri is required"));
        }

        SkillManifest manifest;
        try {
            manifest = adapter.getSkillHandler().get(uri);
        } catch (IllegalArgumentException e) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: " + e.getMessage()));
        }

        ObjectNode result = MAPPER.createObjectNode();
        result.set("skill", SkillsListHandler.skillEntry(MAPPER, manifest));
        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
