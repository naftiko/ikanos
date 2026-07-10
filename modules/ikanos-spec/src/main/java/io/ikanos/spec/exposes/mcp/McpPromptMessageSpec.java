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

/**
 * A single message in an inline MCP prompt template.
 *
 * The {@code role} must be {@code "user"} or {@code "assistant"}.
 * The {@code content} may contain {@code {{arg}}} placeholders that are substituted
 * when the prompt is rendered via {@code prompts/get}.
 */
public class McpPromptMessageSpec {

    private volatile String role;
    private volatile String content;

    public McpPromptMessageSpec() {}

    public McpPromptMessageSpec(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
