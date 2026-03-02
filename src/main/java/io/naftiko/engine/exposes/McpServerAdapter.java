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
package io.naftiko.engine.exposes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;

/**
 * MCP Server Adapter implementation.
 * 
 * Supports two transports, selected via the spec's {@code transport} field:
 * <ul>
 *   <li>{@code http} (default) — Jetty-based Streamable HTTP server</li>
 *   <li>{@code stdio} — stdin/stdout JSON-RPC for local IDE integration</li>
 * </ul>
 * 
 * In both modes, tool definitions and the {@link McpToolHandler} are shared.
 * Only the I/O layer differs.
 */
public class McpServerAdapter extends ServerAdapter {

    /** Jetty server — only initialized for HTTP transport */
    private Server jettyServer;

    /** Stdio handler and thread — only initialized for stdio transport */
    private volatile StdioJsonRpcHandler stdioHandler;
    private volatile Thread stdioThread;

    private final McpToolHandler toolHandler;
    private final List<McpSchema.Tool> tools;

    public McpServerAdapter(Capability capability, McpServerSpec serverSpec) {
        super(capability, serverSpec);

        // Build MCP Tool definitions from the spec
        this.tools = new ArrayList<>();
        for (McpServerToolSpec toolSpec : serverSpec.getTools()) {
            this.tools.add(buildMcpTool(toolSpec));
        }

        // Create the tool handler (transport-agnostic)
        this.toolHandler = new McpToolHandler(capability, serverSpec.getTools());

        if (serverSpec.isStdio()) {
            initStdioTransport();
        } else {
            initHttpTransport(serverSpec);
        }
    }

    /**
     * Initialize the Streamable HTTP transport (Jetty).
     */
    private void initHttpTransport(McpServerSpec serverSpec) {
        this.jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        String address = serverSpec.getAddress() != null ? serverSpec.getAddress() : "localhost";
        connector.setHost(address);
        connector.setPort(serverSpec.getPort());

        // TODO: Make idle timeout configurable
        connector.setIdleTimeout(120000); // 2 minutes — tool calls may involve upstream HTTP requests
        jettyServer.addConnector(connector);

        // Set the MCP handler
        jettyServer.setHandler(new JettyMcpStreamableHandler(this));
    }

    /**
     * Initialize the stdio transport (stdin/stdout JSON-RPC).
     */
    private void initStdioTransport() {
        McpProtocolDispatcher dispatcher = new McpProtocolDispatcher(this);
        this.stdioHandler = new StdioJsonRpcHandler(dispatcher);
    }

    /**
     * Build an MCP Tool from a tool spec.
     * Converts input parameters to a JSON Schema map for the tool's inputSchema.
     */
    private McpSchema.Tool buildMcpTool(McpServerToolSpec toolSpec) {
        // Build JSON Schema properties from input parameters
        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (toolSpec.getInputParameters() != null) {
            for (InputParameterSpec param : toolSpec.getInputParameters()) {
                Map<String, Object> property = new HashMap<>();
                property.put("type", param.getType() != null ? param.getType() : "string");
                if (param.getDescription() != null) {
                    property.put("description", param.getDescription());
                }
                schemaProperties.put(param.getName(), property);

                // By default, all parameters are required unless explicitly marked otherwise
                required.add(param.getName());
            }
        }

        // Build the input schema using McpSchema.JsonSchema
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                schemaProperties.isEmpty() ? null : schemaProperties,
                required.isEmpty() ? null : required,
                null, null, null);

        return McpSchema.Tool.builder()
                .name(toolSpec.getName())
                .description(toolSpec.getDescription())
                .inputSchema(inputSchema)
                .build();
    }

    public McpServerSpec getMcpServerSpec() {
        return (McpServerSpec) getSpec();
    }

    public McpToolHandler getToolHandler() {
        return toolHandler;
    }

    public List<McpSchema.Tool> getTools() {
        return tools;
    }

    @Override
    public void start() throws Exception {
        if (getMcpServerSpec().isStdio()) {
            stdioThread = new Thread(stdioHandler, "mcp-stdio");
            stdioThread.setDaemon(true);
            stdioThread.start();
            System.err.println("MCP Server started on stdio"
                    + " (namespace: " + getMcpServerSpec().getNamespace() + ")");
        } else {
            jettyServer.start();
            System.out.println("MCP Server started on "
                    + getMcpServerSpec().getAddress() + ":" + getMcpServerSpec().getPort()
                    + " (namespace: " + getMcpServerSpec().getNamespace() + ")");
        }
    }

    @Override
    public void stop() throws Exception {
        if (getMcpServerSpec().isStdio()) {
            if (stdioHandler != null) {
                stdioHandler.shutdown();
            }
        } else {
            if (jettyServer != null) {
                jettyServer.stop();
            }
        }
    }

}
