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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import io.ikanos.spec.exposes.skill.ExposedSkillSpec;
import io.ikanos.spec.exposes.skill.SkillToolSpec;

/**
 * Handles {@code GET /skills} — lists all skills with their tool name summaries.
 *
 * <p>Response body (application/json):</p>
 * <pre>
 * {
 *   "count": 2,
 *   "skills": [
 *     { "name": "order-management", "description": "...", "tools": ["list-orders"] }
 *   ]
 * }
 * </pre>
 */
public class CatalogResource extends SkillServerResource {

    @Get("json")
    public Representation getCatalog() {
        ArrayNode skillList = getMapper().createArrayNode();

        for (ExposedSkillSpec skill : getSkillServerSpec().getSkills().values()) {
            ObjectNode entry = getMapper().createObjectNode();
            entry.put("name", skill.getName());
            entry.put("description", skill.getDescription());
            if (skill.getLicense() != null) {
                entry.put("license", skill.getLicense());
            }
            ArrayNode toolNames = getMapper().createArrayNode();
            for (SkillToolSpec tool : skill.getTools().values()) {
                toolNames.add(tool.getName());
            }
            entry.set("tools", toolNames);
            skillList.add(entry);
        }

        ObjectNode response = getMapper().createObjectNode();
        response.put("count", skillList.size());
        response.set("skills", skillList);

        return new JacksonRepresentation<>(response);
    }
}
