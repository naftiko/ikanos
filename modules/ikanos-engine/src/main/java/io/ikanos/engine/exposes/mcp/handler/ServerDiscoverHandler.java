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
import io.ikanos.engine.exposes.mcp.McpAppsMetadata;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.SkillHandler;
import io.ikanos.engine.exposes.mcp.SkillManifest;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

import java.util.List;

import static io.ikanos.engine.exposes.mcp.ProtocolDispatcher.putSupportedProtocolVersions;

/**
 * The server/discover handler.
 */
public class ServerDiscoverHandler extends McpCallHandler {

    public static final String METHOD_NAME = "server/discover";

    public ServerDiscoverHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        ObjectNode result = MAPPER.createObjectNode();

        putSupportedProtocolVersions(result, "supportedVersions");

        // Conditionally advertise capabilities based on what is declared in the spec
        McpServerSpec spec = adapter.getMcpServerSpec();
        List<SkillManifest> skills = adapter.getSkillHandler() != null
                ? adapter.getSkillHandler().list() : List.of();
        ObjectNode capabilities = MAPPER.createObjectNode();
        if (!spec.getTools().isEmpty()) {
            capabilities.putObject("tools");
        }
        // Skills are resources, so the Skills Extension requires the resources capability.
        if (!spec.getResources().isEmpty() || !skills.isEmpty()) {
            capabilities.putObject("resources");
        }
        if (!spec.getPrompts().isEmpty()) {
            capabilities.putObject("prompts");
        }
        // Each extension adds its own entry to the one "extensions" object.
        ObjectNode extensions = MAPPER.createObjectNode();
        // MCP Apps (SEP-1865): advertised when any tool links a ui:// view. Load-time validation
        // guarantees a declared resource backs each view, so "resources" is present too.
        if (McpAppsMetadata.anyToolDeclaresUi(spec.getTools().values())) {
            extensions.putObject(McpAppsMetadata.EXTENSION_ID);
        }
        // Skills (SEP-2640), with resources/directory/read over every skill directory
        if (!skills.isEmpty()) {
            extensions.putObject(SkillHandler.EXTENSION_ID).put("directoryRead", true);
        }
        if (!extensions.isEmpty()) {
            capabilities.set("extensions", extensions);
        }
        result.set("capabilities", capabilities);

        // Instructions from the spec description, plus where to find the skills for clients that
        // read resources but do not implement the Skills Extension
        String instructions = instructions(spec.getDescription(), skills);
        if (instructions != null) {
            result.put("instructions", instructions);
        }

        result.put("resultType", "complete");
        return new HandlerSuccessResult(result);
    }

    /**
     * Build the server instructions: the spec description, followed by the URIs of the skills'
     * {@code SKILL.md} when the server serves skills. Returns {@code null} when both are absent.
     */
    static String instructions(String description, List<SkillManifest> skills) {
        StringBuilder text = new StringBuilder();
        if (description != null) {
            text.append(description);
        }
        if (!skills.isEmpty()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("Agent skills (read each SKILL.md with resources/read):");
            for (SkillManifest skill : skills) {
                text.append("\n- ").append(skill.uri());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
