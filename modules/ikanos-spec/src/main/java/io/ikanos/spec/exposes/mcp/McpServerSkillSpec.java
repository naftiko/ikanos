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
package io.ikanos.spec.exposes.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An Agent Skill served by an MCP server through the Skills Extension (SEP-2640,
 * {@code io.modelcontextprotocol/skills}).
 *
 * <p>The frontmatter fields follow the Agent Skills format. {@code tools} names tools declared on
 * the same MCP server that the skill depends on (validated at load, never rendered into
 * {@code SKILL.md}); it may be empty for a guidance-only skill. {@code instructions} maps a name
 * to a file relative to {@code location}.</p>
 */
public class McpServerSkillSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<String> license = new AtomicReference<>();
    private final AtomicReference<String> compatibility = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> metadata = new AtomicReference<>();
    private final AtomicReference<String> allowedTools = new AtomicReference<>();
    private final AtomicReference<String> argumentHint = new AtomicReference<>();
    private final AtomicReference<Boolean> userInvocable = new AtomicReference<>();
    private final AtomicReference<Boolean> disableModelInvocation = new AtomicReference<>();
    private final AtomicReference<String> location = new AtomicReference<>();

    private final CopyOnWriteArrayList<String> tools = new CopyOnWriteArrayList<>();

    private final Map<String, String> instructions =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public McpServerSkillSpec() {}

    /** The skill name, set from its key in the {@code skills} map. */
    public String getName() { return name.get(); }
    public void setName(String name) { this.name.set(name); }

    public String getDescription() { return description.get(); }
    public void setDescription(String description) { this.description.set(description); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLicense() { return license.get(); }
    public void setLicense(String license) { this.license.set(license); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCompatibility() { return compatibility.get(); }
    public void setCompatibility(String compatibility) { this.compatibility.set(compatibility); }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> getMetadata() { return metadata.get(); }
    public void setMetadata(Map<String, String> metadata) {
        this.metadata.set(metadata != null ? Collections.unmodifiableMap(
                new LinkedHashMap<>(metadata)) : null);
    }

    @JsonProperty("allowed-tools")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getAllowedTools() { return allowedTools.get(); }

    @JsonProperty("allowed-tools")
    public void setAllowedTools(String allowedTools) { this.allowedTools.set(allowedTools); }

    @JsonProperty("argument-hint")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getArgumentHint() { return argumentHint.get(); }

    @JsonProperty("argument-hint")
    public void setArgumentHint(String argumentHint) { this.argumentHint.set(argumentHint); }

    @JsonProperty("user-invocable")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getUserInvocable() { return userInvocable.get(); }

    @JsonProperty("user-invocable")
    public void setUserInvocable(Boolean userInvocable) { this.userInvocable.set(userInvocable); }

    @JsonProperty("disable-model-invocation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getDisableModelInvocation() { return disableModelInvocation.get(); }

    @JsonProperty("disable-model-invocation")
    public void setDisableModelInvocation(Boolean v) { this.disableModelInvocation.set(v); }

    /** {@code file:///} URI of the directory holding the skill's files, or {@code null}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLocation() { return location.get(); }
    public void setLocation(String location) { this.location.set(location); }

    /** Names of tools on the same MCP server this skill depends on. Empty when none. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) {
        this.tools.clear();
        if (tools != null) this.tools.addAll(tools);
    }

    /** Instruction files keyed by name, each a path relative to {@code location}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> getInstructions() { return instructions; }
    public void setInstructions(Map<String, String> instructions) {
        synchronized (this.instructions) {
            this.instructions.clear();
            if (instructions != null) this.instructions.putAll(instructions);
        }
    }
}
