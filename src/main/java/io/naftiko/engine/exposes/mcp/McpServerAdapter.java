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
package io.naftiko.engine.exposes.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import io.modelcontextprotocol.spec.McpSchema;
import io.naftiko.Capability;
import io.naftiko.engine.aggregates.AggregateFunction;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.consumes.http.OAuth2AuthenticationSpec;
import io.naftiko.spec.exposes.mcp.McpServerSpec;
import io.naftiko.spec.exposes.mcp.McpServerToolSpec;
import io.naftiko.spec.exposes.mcp.McpToolHintsSpec;

/**
 * MCP Server Adapter implementation.
 * 
 * Supports two transports, selected via the spec's {@code transport} field:
 * <ul>
 * <li>{@code http} (default) — Restlet-based Streamable HTTP server</li>
 * <li>{@code stdio} — stdin/stdout JSON-RPC for local IDE integration</li>
 * </ul>
 * 
 * In both modes, tool definitions and the {@link ToolHandler} are shared. Only the I/O layer
 * differs.
 */
public class McpServerAdapter extends ServerAdapter {

    /**
     * Stdio handler and thread — only initialized for stdio transport. Held in
     * {@link AtomicReference} so {@code start()} and {@code stop()} can publish/observe them
     * safely (see SonarQube {@code java:S3077}).
     */
    private final AtomicReference<StdioJsonRpcHandler> stdioHandler = new AtomicReference<>();
    private final AtomicReference<Thread> stdioThread = new AtomicReference<>();

    private final ToolHandler toolHandler;
    private final List<McpSchema.Tool> tools;
    private final Map<String, String> toolLabels;
    private final ResourceHandler resourceHandler;
    private final PromptHandler promptHandler;

    public McpServerAdapter(Capability capability, McpServerSpec serverSpec) {
        super(capability, serverSpec);

        // Build MCP Tool definitions from the spec
        this.tools = new ArrayList<>();
        this.toolLabels = new HashMap<>();
        Context.getCurrentLogger().log(Level.INFO, "Building MCP Tool definitions from the spec");

        for (McpServerToolSpec toolSpec : serverSpec.getTools()) {
            if (toolSpec == null || toolSpec.getName() == null
                    || toolSpec.getName().isBlank()) {
                Context.getCurrentLogger().warning(
                        "Skipping malformed MCP tool declaration: tool or name is missing");
                continue;
            }

            this.tools.add(buildMcpTool(toolSpec));
            if (toolSpec.getLabel() != null) {
                this.toolLabels.put(toolSpec.getName(), toolSpec.getLabel());
            }
        }

        // Create the tool handler (transport-agnostic)
        this.toolHandler = new ToolHandler(capability, serverSpec.getTools(),
                serverSpec.getNamespace());

        // Create the resource handler (transport-agnostic)
        this.resourceHandler = new ResourceHandler(capability, serverSpec.getResources(),
                serverSpec.getNamespace());

        // Create the prompt handler (transport-agnostic)
        this.promptHandler = new PromptHandler(serverSpec.getPrompts());

        if (serverSpec.isStdio()) {
            initStdioTransport();
        } else {
            initHttpTransport(serverSpec);
        }
    }

    /**
     * Initialize the Streamable HTTP transport (Restlet).
     */
    private void initHttpTransport(McpServerSpec serverSpec) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(this);
        Map<String, Boolean> activeSessions = new ConcurrentHashMap<>();

        Context context = new Context();
        context.getAttributes().put("dispatcher", dispatcher);
        context.getAttributes().put("activeSessions", activeSessions);
        if (getCapability().getSpec().getInfo() != null
                && getCapability().getSpec().getInfo().getLabel() != null) {
            context.getAttributes().put("capabilityName",
                    getCapability().getSpec().getInfo().getLabel());
        }

        Router router = new Router(context);
        router.attachDefault(McpServerResource.class);

        Restlet chain = buildServerChain(router);

        String address = serverSpec.getAddress() != null ? serverSpec.getAddress() : "localhost";
        initServer(address, serverSpec.getPort(), chain);
    }

    @Override
    protected Restlet createOAuth2Restlet(OAuth2AuthenticationSpec oauth2, Restlet next) {
        return new McpOAuth2Restlet(oauth2, next);
    }

    /**
     * Initialize the stdio transport (stdin/stdout JSON-RPC).
     */
    private void initStdioTransport() {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(this);
        this.stdioHandler.set(new StdioJsonRpcHandler(dispatcher));
    }

    /**
     * Build an MCP Tool from a tool spec. Converts input parameters to a JSON Schema map for the
     * tool's inputSchema.
     */
    private McpSchema.Tool buildMcpTool(McpServerToolSpec toolSpec) {
        // Build JSON Schema properties from input parameters
        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        // Resolve inputParameters: prefer tool-level, fall back to aggregate function
        List<InputParameterSpec> inputParams = toolSpec.getInputParameters();
        if ((inputParams == null || inputParams.isEmpty()) && toolSpec.getRef() != null) {
            AggregateFunction fn = getCapability().lookupFunction(toolSpec.getRef());
            inputParams = fn.getInputParameters();
        }

        if (inputParams != null) {
            for (InputParameterSpec param : inputParams) {
                Map<String, Object> property = new HashMap<>();
                property.put("type", param.getType() != null ? param.getType() : "string");

                if (param.getDescription() != null) {
                    property.put("description", param.getDescription());
                }

                Context.getCurrentLogger().log(Level.INFO,
                        "Adding parameter to schema: " + param.getName());
                schemaProperties.put(param.getName(), property);

                if (param.isRequired()) {
                    required.add(param.getName());
                }
            }
        }

        // Build the input schema using McpSchema.JsonSchema
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema("object",
                schemaProperties.isEmpty() ? null : schemaProperties,
                required.isEmpty() ? null : required, null, null, null);

        // Build ToolAnnotations from spec hints and label
        McpSchema.ToolAnnotations annotations = buildToolAnnotations(toolSpec);

        return McpSchema.Tool.builder().name(toolSpec.getName())
                .description(toolSpec.getDescription()).inputSchema(inputSchema)
                .annotations(annotations).build();
    }

    /**
     * Build MCP ToolAnnotations from the tool spec's hints and label. Returns null if neither hints
     * nor label are present.
     */
    McpSchema.ToolAnnotations buildToolAnnotations(McpServerToolSpec toolSpec) {
        McpToolHintsSpec hints = toolSpec.getHints();
        String label = toolSpec.getLabel();

        if (hints == null && label == null) {
            return null;
        }

        return new McpSchema.ToolAnnotations(label,
                hints != null ? hints.getReadOnly() : null,
                hints != null ? hints.getDestructive() : null,
                hints != null ? hints.getIdempotent() : null,
                hints != null ? hints.getOpenWorld() : null, null);
    }

    public McpServerSpec getMcpServerSpec() {
        return (McpServerSpec) getSpec();
    }

    public ToolHandler getToolHandler() {
        return toolHandler;
    }

    public ResourceHandler getResourceHandler() {
        return resourceHandler;
    }

    public PromptHandler getPromptHandler() {
        return promptHandler;
    }

    public List<McpSchema.Tool> getTools() {
        return tools;
    }

    public Map<String, String> getToolLabels() {
        return toolLabels;
    }

    @Override
    public void start() throws Exception {
        if (getMcpServerSpec().isStdio()) {
            Thread thread = new Thread(stdioHandler.get(), "mcp-stdio");
            thread.setDaemon(true);
            thread.start();
            stdioThread.set(thread);
            System.err.println("MCP Server started on stdio" + " (namespace: "
                    + getMcpServerSpec().getNamespace() + ")");
            Context.getCurrentLogger().log(Level.INFO, "MCP Server started on stdio"
                    + " (namespace: " + getMcpServerSpec().getNamespace() + ")");
        } else {
            super.start();
            System.out.println("MCP Server started on " + getMcpServerSpec().getAddress() + ":"
                    + getMcpServerSpec().getPort() + " (namespace: "
                    + getMcpServerSpec().getNamespace() + ")");
            Context.getCurrentLogger().log(Level.INFO,
                    "MCP Server started on " + getMcpServerSpec().getAddress() + ":"
                            + getMcpServerSpec().getPort() + " (namespace: "
                            + getMcpServerSpec().getNamespace() + ")");
        }
    }

    @Override
    public void stop() throws Exception {
        if (getMcpServerSpec().isStdio()) {
            StdioJsonRpcHandler handler = stdioHandler.get();
            if (handler != null) {
                handler.shutdown();
                Thread thread = stdioThread.get();
                if (thread != null) {
                    thread.join(5000); // Wait for the stdio thread to finish
                }
                Context.getCurrentLogger().log(Level.INFO, "MCP Server stopped on stdio");
            }
        } else {
            super.stop();
            Context.getCurrentLogger().log(Level.INFO, "MCP Server stopped on "
                    + getMcpServerSpec().getAddress() + ":" + getMcpServerSpec().getPort());
        }
    }

}
