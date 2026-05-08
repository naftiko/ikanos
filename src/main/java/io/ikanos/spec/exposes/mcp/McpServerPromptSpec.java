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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Prompt Specification Element.
 *
 * Defines an MCP prompt template with typed arguments that agents can discover and render.
 * Two source types are supported:
 * <ul>
 *   <li><b>Inline</b> ({@code template}): prompt messages declared directly in YAML with
 *       {@code {{arg}}} placeholders.</li>
 *   <li><b>File-based</b> ({@code location}): prompt content loaded from a {@code file:///} URI;
 *       the file content becomes a single {@code user} role message.</li>
 * </ul>
 */
public class McpServerPromptSpec {

    private volatile String name;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String label;

    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<McpPromptArgumentSpec> arguments;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<McpPromptMessageSpec> template;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String location;

    public McpServerPromptSpec() {
        this.arguments = new CopyOnWriteArrayList<>();
        this.template = new CopyOnWriteArrayList<>();
    }

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

    public List<McpPromptArgumentSpec> getArguments() {
        return arguments;
    }

    public List<McpPromptMessageSpec> getTemplate() {
        return template;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    /**
     * Returns {@code true} when this prompt is rendered from a local file.
     */
    public boolean isFileBased() {
        return location != null;
    }
}
