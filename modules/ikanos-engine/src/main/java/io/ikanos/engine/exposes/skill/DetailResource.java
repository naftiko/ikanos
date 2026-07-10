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
package io.ikanos.engine.exposes.skill;

import java.util.Map;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import io.ikanos.spec.exposes.skill.ExposedSkillSpec;
import io.ikanos.spec.exposes.skill.SkillToolSpec;

/**
 * Handles {@code GET /skills/{name}} — returns full skill metadata and tool catalog.
 *
 * <p>For <strong>derived</strong> tools, the response includes an {@code invocationRef} so agents
 * know which sibling adapter to call and which operation/tool to invoke. For
 * <strong>instruction</strong> tools, the response includes the {@code instruction} file path that
 * agents can download via {@code GET /skills/{name}/contents/{file}}.</p>
 *
 * <p>Returns 404 if the skill name is not found.</p>
 */
public class DetailResource extends SkillServerResource {

    @Get("json")
    public Representation getSkill() {
        String name = getAttribute("name");
        ExposedSkillSpec skill = findSkill(name);
        if (skill == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND, "Skill not found: " + name);
        }

        ObjectNode response = getMapper().createObjectNode();
        response.put("name", skill.getName());
        response.put("description", skill.getDescription());
        if (skill.getLicense() != null) {
            response.put("license", skill.getLicense());
        }
        if (skill.getCompatibility() != null) {
            response.put("compatibility", skill.getCompatibility());
        }
        if (skill.getArgumentHint() != null) {
            response.put("argument-hint", skill.getArgumentHint());
        }
        if (skill.getAllowedTools() != null) {
            response.put("allowed-tools", skill.getAllowedTools());
        }
        if (skill.getUserInvocable() != null) {
            response.put("user-invocable", skill.getUserInvocable());
        }
        if (skill.getDisableModelInvocation() != null) {
            response.put("disable-model-invocation", skill.getDisableModelInvocation());
        }
        if (skill.getMetadata() != null && !skill.getMetadata().isEmpty()) {
            ObjectNode meta = getMapper().createObjectNode();
            for (Map.Entry<String, String> e : skill.getMetadata().entrySet()) {
                meta.put(e.getKey(), e.getValue());
            }
            response.set("metadata", meta);
        }

        Map<String, String> namespaceMode = getNamespaceMode();
        ArrayNode toolList = getMapper().createArrayNode();
        for (SkillToolSpec tool : skill.getTools().values()) {
            ObjectNode toolEntry = getMapper().createObjectNode();
            toolEntry.put("name", tool.getName());
            toolEntry.put("description", tool.getDescription());
            if (tool.getFrom() != null) {
                toolEntry.put("type", "derived");
                ObjectNode invRef = getMapper().createObjectNode();
                invRef.put("targetNamespace", tool.getFrom().getSourceNamespace());
                invRef.put("action", tool.getFrom().getAction());
                invRef.put("mode", namespaceMode.getOrDefault(tool.getFrom().getSourceNamespace(), "unknown"));
                toolEntry.set("invocationRef", invRef);
            } else if (tool.getInstruction() != null) {
                toolEntry.put("type", "instruction");
                toolEntry.put("instruction", tool.getInstruction());
            }
            toolList.add(toolEntry);
        }
        response.set("tools", toolList);

        return new JacksonRepresentation<>(response);
    }
}
