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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A skill definition.
 *
 * <p>Supports the full <a href="https://agentskills.io/specification">Agent Skills Spec</a>
 * frontmatter properties. Skills describe tools from sibling {@code api} or {@code mcp} adapters,
 * or from local file instructions — they do not execute tools themselves.</p>
 *
 * <p>A skill may:</p>
 * <ul>
 *   <li>Declare derived tools (via {@code from}) that reference sibling adapter operations</li>
 *   <li>Declare instruction tools (via {@code instruction}) backed by local files</li>
 *   <li>Stand alone as purely descriptive (no tools, just metadata and {@code location} files)</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code metadata} map is stored
 * as an immutable snapshot. The {@code tools} list is a {@link CopyOnWriteArrayList}. This
 * satisfies SonarQube rule {@code java:S3077}.
 */
public class ExposedSkillSpec {

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

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<SkillToolSpec> tools;

    public ExposedSkillSpec() {
        this.tools = new CopyOnWriteArrayList<>();
    }

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
    public String getLicense() {
        return license.get();
    }

    public void setLicense(String license) {
        this.license.set(license);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCompatibility() {
        return compatibility.get();
    }

    public void setCompatibility(String compatibility) {
        this.compatibility.set(compatibility);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> getMetadata() {
        return metadata.get();
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata.set(metadata != null ? Map.copyOf(metadata) : null);
    }

    @JsonProperty("allowed-tools")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getAllowedTools() {
        return allowedTools.get();
    }

    @JsonProperty("allowed-tools")
    public void setAllowedTools(String allowedTools) {
        this.allowedTools.set(allowedTools);
    }

    @JsonProperty("argument-hint")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getArgumentHint() {
        return argumentHint.get();
    }

    @JsonProperty("argument-hint")
    public void setArgumentHint(String argumentHint) {
        this.argumentHint.set(argumentHint);
    }

    @JsonProperty("user-invocable")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getUserInvocable() {
        return userInvocable.get();
    }

    @JsonProperty("user-invocable")
    public void setUserInvocable(Boolean userInvocable) {
        this.userInvocable.set(userInvocable);
    }

    @JsonProperty("disable-model-invocation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getDisableModelInvocation() {
        return disableModelInvocation.get();
    }

    @JsonProperty("disable-model-invocation")
    public void setDisableModelInvocation(Boolean disableModelInvocation) {
        this.disableModelInvocation.set(disableModelInvocation);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLocation() {
        return location.get();
    }

    public void setLocation(String location) {
        this.location.set(location);
    }

    public List<SkillToolSpec> getTools() {
        return tools;
    }
}
