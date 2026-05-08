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
package io.ikanos.spec.exposes.skill;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * Skill Server Specification Element.
 *
 * <p>Defines a skill catalog server that exposes agent skill metadata and supporting files over
 * predefined GET-only endpoints. Skills declare tools derived from sibling {@code api} or
 * {@code mcp} adapters, or defined as local file instructions. The skill server does not execute
 * tools — AI clients invoke sibling adapters directly.</p>
 *
 * <p>Predefined endpoints:</p>
 * <ul>
 *   <li>{@code GET /skills} — list all skills</li>
 *   <li>{@code GET /skills/{name}} — skill metadata + tool catalog with invocation refs</li>
 *   <li>{@code GET /skills/{name}/download} — ZIP archive from the skill's {@code location}</li>
 *   <li>{@code GET /skills/{name}/contents} — file listing from the skill's {@code location}</li>
 *   <li>{@code GET /skills/{name}/contents/{file}} — individual file from the skill's {@code location}</li>
 * </ul>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class SkillServerSpec extends ServerSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String namespace;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<ExposedSkillSpec> skills;

    public SkillServerSpec() {
        this(null, 0, null);
    }

    public SkillServerSpec(String address, int port, String namespace) {
        super("skill", address, port);
        this.namespace = namespace;
        this.skills = new CopyOnWriteArrayList<>();
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ExposedSkillSpec> getSkills() {
        return skills;
    }
}
