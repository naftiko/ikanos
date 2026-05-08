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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An argument for an MCP prompt template.
 *
 * Arguments are always strings per the MCP specification. They are substituted into
 * prompt templates via {@code {{name}}} placeholders.
 */
public class McpPromptArgumentSpec {

    private volatile String name;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String label;

    private volatile String description;

    /** Defaults to {@code true} — arguments are required unless explicitly set to false. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Boolean required;

    public McpPromptArgumentSpec() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    /**
     * Returns {@code true} unless {@code required} is explicitly set to {@code false}.
     */
    public boolean isRequired() {
        return required == null || required;
    }
}
