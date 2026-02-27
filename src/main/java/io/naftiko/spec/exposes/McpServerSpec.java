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
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Server Specification Element.
 * 
 * Defines an MCP server that exposes tools over Streamable HTTP transport.
 * Each tool maps to one or more consumed HTTP operations.
 */
public class McpServerSpec extends ServerSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String namespace;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<McpServerToolSpec> tools;

    public McpServerSpec() {
        this(null, 0, null, null);
    }

    public McpServerSpec(String address, int port, String namespace, String description) {
        super("mcp", address, port);
        this.namespace = namespace;
        this.description = description;
        this.tools = new CopyOnWriteArrayList<>();
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<McpServerToolSpec> getTools() {
        return tools;
    }

}
