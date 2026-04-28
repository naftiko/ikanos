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
package io.naftiko.spec.exposes.mcp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * MCP Server Specification Element.
 * 
 * Defines an MCP server that exposes tools over a configurable transport.
 * Supported transports:
 * <ul>
 *   <li>{@code http} (default) — Streamable HTTP via Restlet, for networked deployments</li>
 *   <li>{@code stdio} — stdin/stdout JSON-RPC, for local IDE development</li>
 * </ul>
 * Each tool maps to one or more consumed HTTP operations.
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class McpServerSpec extends ServerSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String transport;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String namespace;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<McpServerToolSpec> tools;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<McpServerResourceSpec> resources;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<McpServerPromptSpec> prompts;

    public McpServerSpec() {
        this(null, 0, null, null);
    }

    public McpServerSpec(String address, int port, String namespace, String description) {
        super("mcp", address, port);
        this.namespace = namespace;
        this.description = description;
        this.tools = new CopyOnWriteArrayList<>();
        this.resources = new CopyOnWriteArrayList<>();
        this.prompts = new CopyOnWriteArrayList<>();
    }

    /**
     * Returns the transport type. Defaults to {@code "http"} when not set.
     */
    public String getTransport() {
        return transport != null ? transport : "http";
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public boolean isStdio() {
        return "stdio".equals(getTransport());
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

    public List<McpServerResourceSpec> getResources() {
        return resources;
    }

    public List<McpServerPromptSpec> getPrompts() {
        return prompts;
    }

}
