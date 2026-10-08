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

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Apps metadata on an exposed MCP resource (SEP-1865): the security envelope and rendering
 * preferences of a {@code ui://} view. Emitted as {@code _meta.ui} on both the
 * {@code resources/list} entry and the {@code resources/read} content item.
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}.
 */
public class McpResourceUiSpec {

    private final AtomicReference<McpResourceUiCspSpec> csp = new AtomicReference<>();
    private final AtomicReference<McpResourceUiPermissionsSpec> permissions =
            new AtomicReference<>();
    private final AtomicReference<String> domain = new AtomicReference<>();
    private final AtomicReference<Boolean> prefersBorder = new AtomicReference<>();

    public McpResourceUiSpec() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public McpResourceUiCspSpec getCsp() { return csp.get(); }
    public void setCsp(McpResourceUiCspSpec csp) { this.csp.set(csp); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public McpResourceUiPermissionsSpec getPermissions() { return permissions.get(); }
    public void setPermissions(McpResourceUiPermissionsSpec permissions) {
        this.permissions.set(permissions);
    }

    /** Host-specific sandbox origin. Passed through verbatim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDomain() { return domain.get(); }
    public void setDomain(String domain) { this.domain.set(domain); }

    /** Whether the view prefers a visible border. Host defaults vary, so set it explicitly. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getPrefersBorder() { return prefersBorder.get(); }
    public void setPrefersBorder(Boolean prefersBorder) { this.prefersBorder.set(prefersBorder); }
}
