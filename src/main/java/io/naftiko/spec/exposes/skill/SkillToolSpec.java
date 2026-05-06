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
package io.naftiko.spec.exposes.skill;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool declared within a skill.
 *
 * <p>Exactly one of {@code from} (derived from a sibling {@code api} or {@code mcp} adapter) or
 * {@code instruction} (path to a local file relative to the skill's {@code location} directory)
 * must be specified.</p>
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}. This satisfies SonarQube rule
 * {@code java:S3077}.
 */
public class SkillToolSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<SkillToolFromSpec> from = new AtomicReference<>();
    private final AtomicReference<String> instruction = new AtomicReference<>();

    public SkillToolSpec() {}

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public SkillToolFromSpec getFrom() {
        return from.get();
    }

    public void setFrom(SkillToolFromSpec from) {
        this.from.set(from);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getInstruction() {
        return instruction.get();
    }

    public void setInstruction(String instruction) {
        this.instruction.set(instruction);
    }
}
