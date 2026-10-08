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
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Apps metadata on an exposed MCP tool (SEP-1865, extension {@code io.modelcontextprotocol/ui}).
 *
 * <p>Links the tool to a predeclared {@code ui://} resource that a supporting host renders as an
 * interactive view. Emitted as {@code _meta.ui} in {@code tools/list}.</p>
 *
 * <h2>Thread safety</h2>
 * {@code resourceUri} is held in an {@link AtomicReference}; {@code visibility} is a
 * {@link CopyOnWriteArrayList}. This satisfies SonarQube rule {@code java:S3077}.
 */
public class McpToolUiSpec {

    private final AtomicReference<String> resourceUri = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final CopyOnWriteArrayList<String> visibility = new CopyOnWriteArrayList<>();

    public McpToolUiSpec() {}

    public McpToolUiSpec(String resourceUri) {
        this.resourceUri.set(resourceUri);
    }

    /** URI of a {@code ui://} resource declared on the same MCP server. */
    public String getResourceUri() { return resourceUri.get(); }
    public void setResourceUri(String resourceUri) { this.resourceUri.set(resourceUri); }

    /**
     * Who may call the tool: {@code model}, {@code app}, or both. Empty means "not authored", in
     * which case the host default ({@code ["model","app"]}) applies. This is a host-enforced hint,
     * not an access control.
     */
    public List<String> getVisibility() { return visibility; }
    public void setVisibility(List<String> visibility) {
        this.visibility.clear();
        if (visibility != null) this.visibility.addAll(visibility);
    }
}
