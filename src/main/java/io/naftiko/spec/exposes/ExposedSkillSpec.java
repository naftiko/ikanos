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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 */
public class ExposedSkillSpec {

    private volatile String name;

    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String license;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String compatibility;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private volatile Map<String, String> metadata;

    @JsonProperty("allowed-tools")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String allowedTools;

    @JsonProperty("argument-hint")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String argumentHint;

    @JsonProperty("user-invocable")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Boolean userInvocable;

    @JsonProperty("disable-model-invocation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Boolean disableModelInvocation;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String location;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<SkillToolSpec> tools;

    public ExposedSkillSpec() {
        this.tools = new CopyOnWriteArrayList<>();
    }

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

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getCompatibility() {
        return compatibility;
    }

    public void setCompatibility(String compatibility) {
        this.compatibility = compatibility;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public String getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(String allowedTools) {
        this.allowedTools = allowedTools;
    }

    public String getArgumentHint() {
        return argumentHint;
    }

    public void setArgumentHint(String argumentHint) {
        this.argumentHint = argumentHint;
    }

    public Boolean getUserInvocable() {
        return userInvocable;
    }

    public void setUserInvocable(Boolean userInvocable) {
        this.userInvocable = userInvocable;
    }

    public Boolean getDisableModelInvocation() {
        return disableModelInvocation;
    }

    public void setDisableModelInvocation(Boolean disableModelInvocation) {
        this.disableModelInvocation = disableModelInvocation;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List<SkillToolSpec> getTools() {
        return tools;
    }
}
