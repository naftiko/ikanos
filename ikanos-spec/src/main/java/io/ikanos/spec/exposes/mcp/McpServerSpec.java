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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * MCP Server Specification Element.
 * 
 * <p>Defines an MCP server that exposes tools, resources, and prompts over a configurable
 * transport. Supported transports:
 * <ul>
 *   <li>{@code http} (default) — Streamable HTTP via Restlet, for networked deployments</li>
 *   <li>{@code stdio} — stdin/stdout JSON-RPC, for local IDE development</li>
 * </ul>
 * Tools, resources, and prompts are keyed by their name (kebab-case identifier).</p>
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
    @JsonDeserialize(using = McpServerToolMapDeserializer.class)
    private final Map<String, McpServerToolSpec> tools =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = McpServerResourceMapDeserializer.class)
    private final Map<String, McpServerResourceSpec> resources =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = McpServerPromptMapDeserializer.class)
    private final Map<String, McpServerPromptSpec> prompts =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public McpServerSpec() {
        this(null, 0, null, null);
    }

    public McpServerSpec(String address, int port, String namespace, String description) {
        super("mcp", address, port);
        this.namespace = namespace;
        this.description = description;
    }

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

    public Map<String, McpServerToolSpec> getTools() {
        return tools;
    }

    public void setTools(Map<String, McpServerToolSpec> tools) {
        if (tools == null) return;
        synchronized (this.tools) {
            this.tools.clear();
            this.tools.putAll(tools);
        }
    }

    public Map<String, McpServerResourceSpec> getResources() {
        return resources;
    }

    public void setResources(Map<String, McpServerResourceSpec> resources) {
        if (resources == null) return;
        synchronized (this.resources) {
            this.resources.clear();
            this.resources.putAll(resources);
        }
    }

    public Map<String, McpServerPromptSpec> getPrompts() {
        return prompts;
    }

    public void setPrompts(Map<String, McpServerPromptSpec> prompts) {
        if (prompts == null) return;
        synchronized (this.prompts) {
            this.prompts.clear();
            this.prompts.putAll(prompts);
        }
    }
}
