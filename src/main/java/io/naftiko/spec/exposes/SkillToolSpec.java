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
package io.naftiko.spec.exposes;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool declared within a skill.
 *
 * <p>Exactly one of {@code from} (derived from a sibling {@code api} or {@code mcp} adapter) or
 * {@code instruction} (path to a local file relative to the skill's {@code location} directory)
 * must be specified.</p>
 */
public class SkillToolSpec {

    private volatile String name;

    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile SkillToolFromSpec from;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String instruction;

    public SkillToolSpec() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SkillToolFromSpec getFrom() {
        return from;
    }

    public void setFrom(SkillToolFromSpec from) {
        this.from = from;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }
}
