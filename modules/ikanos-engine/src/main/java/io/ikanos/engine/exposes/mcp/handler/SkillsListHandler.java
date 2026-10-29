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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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
 * The skills/list handler (SEP-2640). Every served skill fits in one page, so a request carrying
 * a cursor is rejected: this server never issues one.
 */
public class SkillsListHandler extends McpCallHandler {

    public static final String METHOD_NAME = "skills/list";

    public SkillsListHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode cursor = requestBody.path("params").path("cursor");
        if (!cursor.isMissingNode() && !cursor.isNull()) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(
                    requestBody.get("id"), INVALID_PARAMS.getCode(), "Invalid params: unknown cursor"));
        }

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode skills = result.putArray("skills");
        for (SkillManifest manifest : adapter.getSkillHandler().list()) {
            skills.add(skillEntry(MAPPER, manifest));
        }

        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    /**
     * Build a skill entry: the {@code SKILL.md} URI, its frontmatter and the complete list of its
     * files with their digests and sizes ({@code SKILL.md} included).
     */
    static ObjectNode skillEntry(ObjectMapper mapper, SkillManifest manifest) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("uri", manifest.uri());
        entry.set("frontmatter", mapper.valueToTree(manifest.frontmatter()));
        ArrayNode resources = entry.putArray("resources");
        for (SkillManifest.SkillFile file : manifest.files()) {
            ObjectNode resource = resources.addObject();
            resource.put("uri", file.uri());
            resource.put("digest", file.digest());
            resource.put("size", file.size());
        }
        return entry;
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
