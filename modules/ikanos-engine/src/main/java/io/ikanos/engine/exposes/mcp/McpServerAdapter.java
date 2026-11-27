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
package io.ikanos.engine.exposes.mcp;

import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.engine.exposes.mcp.search.ToolSearchProviders;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.engine.consumes.mcp.UpstreamTasks;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolHintsSpec;
import io.modelcontextprotocol.spec.McpSchema;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.time.Clock;
import io.ikanos.engine.execution.Durability;
import io.ikanos.engine.exposes.CallerClaims;
import io.ikanos.engine.exposes.mcp.handler.ToolsCallHandler;
import io.ikanos.engine.exposes.mcp.files.McpFileDownloads;
import io.ikanos.engine.util.BinarySize;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.engine.consumes.sql.SqlWriteExposure;
import io.ikanos.engine.execution.Elicitation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP Server Adapter implementation.
 * 
 * Supports two transports, selected via the spec's {@code transport} field:
 * <ul>
 * <li>{@code http} (default) — Restlet-based Streamable HTTP server</li>
 * <li>{@code stdio} — stdin/stdout JSON-RPC for local IDE integration</li>
 * </ul>
 * 
 * In both modes, tool definitions and the {@link ToolHandler}, {@link ResourceHandler},
 * {@link PromptHandler} and {@link SkillHandler} are shared. Only the I/O layer differs.
 */
public class McpServerAdapter extends ServerAdapter {

    private static final ObjectMapper JSON = IkanosMappers.json();

    /**
     * Longest wait for running tasks to reach their next action when the adapter stops. A task
     * still in its action after it is cut short there, and resumed by the next start.
     */
    static final java.time.Duration DRAIN_TIMEOUT = java.time.Duration.ofSeconds(30);

    /**
     * Stdio handler and thread — only initialized for stdio transport. Held in
     * {@link AtomicReference} so {@code start()} and {@code stop()} can publish/observe them
     * safely (see SonarQube {@code java:S3077}).
     */
    private final AtomicReference<StdioJsonRpcHandler> stdioHandler = new AtomicReference<>();
    private final AtomicReference<Thread> stdioThread = new AtomicReference<>();

    /** OAuth 2.1 restlet, set when the HTTP transport uses {@code oauth2} authentication. */
    private final AtomicReference<McpOAuth2Restlet> oauth2Restlet = new AtomicReference<>();

    private final ToolHandler toolHandler;
    private final List<McpSchema.Tool> tools;
    private final Map<String, String> toolLabels;
    private final Map<String, List<String>> toolTags;
    private final ToolSearchCatalog toolSearchCatalog;
    private final ResourceHandler resourceHandler;
    private final PromptHandler promptHandler;
    private final SkillHandler skillHandler;
    private final McpTaskService taskService;
    private final McpRequestState requestState;
    private final McpFileDownloads fileDownloads;

    /** The tools that take a file: listed only to clients of the files extension. */
    private final Set<String> fileTools;

    /** The inline upload limit of the files extension, {@code 0} when the block declares none. */
    private final long maxInlineBytes;

    public McpServerAdapter(Capability capability, McpServerSpec serverSpec) {
        super(capability, serverSpec);
        this.requestState = McpRequestState.withKey(capability.getSealingKey(),
                capability.getSpecDigest());
        AccessControl.checkAtStartup(serverSpec, serverSpec.isStdio(),
                AccessControl.declaredIn(serverSpec));

        // Files extension (experimental): only when the exposes block declares files
        this.fileDownloads = McpFileDownloads.of(serverSpec, Clock.systemUTC());
        this.maxInlineBytes = serverSpec.getFiles() != null
                ? BinarySize.parse(serverSpec.getFiles().uploadsOrDefault()
                        .maxInlineBytesOrDefault())
                : 0;

        // Create the tool handler first (transport-agnostic): it owns each tool's output
        // contract, which buildMcpTool() advertises as the MCP outputSchema.
        this.toolHandler = new ToolHandler(capability, serverSpec.getTools(),
                serverSpec.getNamespace(), serverSpec.getMaxBinarySize(), fileDownloads,
                maxInlineBytes);
        this.fileTools = fileTools(serverSpec, toolHandler);

        // Build MCP Tool definitions from the spec
        this.tools = new ArrayList<>();
        this.toolLabels = new HashMap<>();
        this.toolTags = new HashMap<>();
        Context.getCurrentLogger().log(Level.INFO, "Building MCP Tool definitions from the spec");

        for (McpServerToolSpec toolSpec : serverSpec.getTools().values()) {
            if (toolSpec == null || toolSpec.getName() == null
                    || toolSpec.getName().isBlank()) {
                Context.getCurrentLogger().warning(
                        "Skipping malformed MCP tool declaration: tool or name is missing");
                continue;
            }

            checkReadOnlyHint(capability, serverSpec, toolSpec);
            this.tools.add(buildMcpTool(toolSpec));
            if (toolSpec.getDisplay() != null) {
                this.toolLabels.put(toolSpec.getName(), toolSpec.getDisplay());
            }
            this.toolTags.put(toolSpec.getName(), List.copyOf(toolSpec.getTags()));
        }

        // Tool search: validated here, so a bad toolSearch block fails the load. The index itself
        // is only built on the first search, by the provider found on the classpath, if any.
        this.toolSearchCatalog = new ToolSearchCatalog(serverSpec, tools, toolLabels, toolTags,
                ToolSearchProviders.find());

        // Create the resource handler (transport-agnostic)
        this.resourceHandler = new ResourceHandler(capability, serverSpec.getResources(),
                serverSpec.getNamespace(), serverSpec.getMaxBinarySize());

        // MCP Apps: every tool ui.resourceUri must resolve to a declared static ui:// view
        McpAppsMetadata.validate(serverSpec.getTools().values(),
                serverSpec.getResources().values(), resourceHandler);

        // Create the prompt handler (transport-agnostic)
        this.promptHandler = new PromptHandler(serverSpec.getPrompts());

        // Skills (SEP-2640): validated and snapshotted once, here, so a bad skill fails startup
        this.skillHandler = new SkillHandler(serverSpec.getSkills(), serverSpec.getTools().keySet(),
                serverSpec.getResources().values().stream()
                        .map(McpServerResourceSpec::getUri).toList());

        // Tasks (SEP-2663) need somewhere to keep executions: only in a durable mode.
        Durability durability = capability.getDurability();
        this.taskService = durability != null && durability.isDurable()
                ? new McpTaskService(durability.runner(), Clock.systemUTC(),
                        durability.retentionMs())
                        .whenCancelled(id -> UpstreamTasks.cancelUnder(capability,
                                durability.runner(), id))
                : null;

        if (serverSpec.isStdio()) {
            initStdioTransport();
        } else {
            initHttpTransport(serverSpec);
        }
    }

    /**
     * The tools that take a file. A file input needs the files extension, so a tool that takes
     * one on an adapter without {@code files} fails the load: no client could send it a file.
     */
    static Set<String> fileTools(McpServerSpec serverSpec, ToolHandler toolHandler) {
        Set<String> names = new HashSet<>();
        for (McpServerToolSpec tool : serverSpec.getTools().values()) {
            if (tool != null && tool.getName() != null && FileInputs.takesFiles(
                    toolHandler.getInputParameters(tool.getName()))) {
                if (serverSpec.getFiles() == null) {
                    throw new IllegalArgumentException("MCP tool '" + tool.getName()
                            + "' takes a file (type: file), which needs 'files' on its exposes"
                            + " block '" + serverSpec.getNamespace() + "'");
                }
                names.add(tool.getName());
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Initialize the Streamable HTTP transport (Restlet).
     */
    private void initHttpTransport(McpServerSpec serverSpec) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(this);

        Context context = new Context();
        context.getAttributes().put("dispatcher", dispatcher);

        Router router = new Router(context);
        router.attachDefault(McpServerResource.class);

        Restlet chain = buildServerChain(limitRequestsInProgress(router));
        AgentIdentityResolver identityResolver = dispatcher.getIdentityResolver();
        if (identityResolver.isForwarded()) {
            // The gateway is the authentication boundary: refuse other peers before anything else.
            chain = new TrustedPeerFilter(context, identityResolver, chain);
        }

        String address = serverSpec.getAddress() != null ? serverSpec.getAddress() : "localhost";
        initServer(address, serverSpec.getPort(), chain);
    }

    @Override
    protected Restlet createOAuth2Restlet(OAuth2AuthenticationSpec oauth2, Restlet next) {
        McpOAuth2Restlet restlet = new McpOAuth2Restlet(oauth2, next);
        this.oauth2Restlet.set(restlet);
        return restlet;
    }

    /**
     * The Protected Resource Metadata path (RFC 9728) sits under {@code /.well-known/}, outside
     * any prefix this adapter is mounted under, so a shared listener must route it here.
     */
    @Override
    public List<String> getWellKnownPaths() {
        McpOAuth2Restlet restlet = oauth2Restlet.get();
        return restlet != null ? List.of(restlet.getMetadataPath()) : List.of();
    }

    /**
     * Initialize the stdio transport (stdin/stdout JSON-RPC).
     */
    private void initStdioTransport() {
        // stdio has no authentication chain: refuse awsSigV4 here, as buildServerChain does
        refuseAwsSigV4();
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(this);
        this.stdioHandler.set(new StdioJsonRpcHandler(dispatcher));
    }

    /**
     * Build an MCP Tool from a tool spec. Converts input parameters to a JSON Schema map for the
     * tool's inputSchema.
     */
    private McpSchema.Tool buildMcpTool(McpServerToolSpec toolSpec) {
        // The same input parameters as tools/call: tool-level, else the aggregate flow's
        List<InputParameterSpec> inputParams = toolHandler.getInputParameters(toolSpec.getName());

        Map<String, Object> inputSchema = McpToolInputSchema.forParameters(inputParams);

        // Build ToolAnnotations from spec hints and label
        McpSchema.ToolAnnotations annotations = buildToolAnnotations(toolSpec);

        // Advertise the output contract (if any) as the MCP outputSchema
        McpToolOutputSchema.Contract contract = toolHandler.getOutputContract(toolSpec.getName());

        return McpSchema.Tool.builder(toolSpec.getName(), inputSchema)
                .description(toolSpec.getDescription())
                .outputSchema(contract != null ? contract.schema() : null)
                .annotations(annotations).meta(McpAppsMetadata.toolMeta(toolSpec.getUi()))
                .build();
    }

    /**
     * Build MCP ToolAnnotations from the tool's resolved hints and label. Each hint comes from the
     * tool (already merged over its flow's semantics for a {@code ref}), else from the server's
     * default {@code hints}. Returns null if neither hints nor label are present.
     */
    McpSchema.ToolAnnotations buildToolAnnotations(McpServerToolSpec toolSpec) {
        McpToolHintsSpec hints = getMcpServerSpec().resolveToolHints(toolSpec);
        String display = toolSpec.getDisplay();

        if (hints == null && display == null) {
            return null;
        }

        return new McpSchema.ToolAnnotations(display,
                hints != null ? hints.getReadOnly() : null,
                hints != null ? hints.getDestructive() : null,
                hints != null ? hints.getIdempotent() : null,
                hints != null ? hints.getOpenWorld() : null, null);
    }

    /** The task service, or {@code null} when the capability runs under {@code none}. */
    public McpTaskService getTaskService() {
        return taskService;
    }

    /**
     * Starts again the unfinished tasks of this adapter's tools that the durable store kept, such
     * as those of the {@code local} mode after a restart. A task belongs to this adapter when its
     * entry names the adapter's namespace; one whose tool no longer exists fails when called.
     *
     * @return the number of tasks started again
     */
    public int resumeTasks() {
        if (taskService == null) {
            return 0;
        }
        return taskService.resume(taskWork());
    }

    /**
     * The tool call of a task of this adapter, from the task's entry and recorded arguments, or
     * {@code null} for an entry of another adapter: what a resumed or answered task runs.
     */
    public BiFunction<String, JsonNode, Callable<JsonNode>> taskWork() {
        String prefix = "mcp:" + getMcpServerSpec().getNamespace() + "/";
        return (entry, arguments) -> {
            if (!entry.startsWith(prefix)) {
                return null;
            }
            String toolName = entry.substring(prefix.length());
            @SuppressWarnings("unchecked")
            Map<String, Object> callArguments = arguments == null || !arguments.isObject()
                    ? new HashMap<>() : JSON.convertValue(arguments, Map.class);
            // The caller's claims are not recorded with the task (#998). A tasks/update answer
            // runs it with the claims of the request that answered, validated by the transport;
            // a task resumed at startup has none, so a template that reads {{auth.claims...}}
            // fails as a missing claim rather than run without them, until #1115 restores them.
            Map<String, Object> caller = CallerClaims.current();
            return () -> CallerClaims.runAs(caller,
                    () -> ToolsCallHandler.callTool(this, toolName, callArguments));
        };
    }

    /**
     * Refuse a tool advertised to clients as read-only, by its own {@code hints}, its flow's
     * semantics or the exposes block's default {@code hints}, that reaches a SQL procedure
     * declared {@code readOnly: false}: a client would call it without asking, and it writes.
     */
    static void checkReadOnlyHint(Capability capability, McpServerSpec serverSpec,
            McpServerToolSpec toolSpec) {
        McpToolHintsSpec hints = serverSpec.resolveToolHints(toolSpec);
        if (hints == null || !Boolean.TRUE.equals(hints.getReadOnly())) {
            return;
        }
        String write = SqlWriteExposure.writeReached(capability, toolSpec.getRef(),
                toolSpec.getCall(), toolSpec.getSteps());
        if (write != null) {
            throw new IllegalArgumentException("MCP tool '" + toolSpec.getName()
                    + "' of namespace '" + serverSpec.getNamespace() + "' resolves"
                    + " hints.readOnly: true, declared or inherited from the exposes block, but"
                    + " reaches the SQL write procedure '" + write + "'; declare"
                    + " hints.readOnly: false on the tool");
        }
    }

    /**
     * Whether the tool's flow may ask its caller for input: it has an {@code elicit} step, or
     * calls a flow that may, at any depth.
     */
    public boolean mayElicit(String toolName) {
        McpServerToolSpec toolSpec = getMcpServerSpec().getTools().get(toolName);
        return toolSpec != null
                && Elicitation.mayAsk(getCapability(), toolSpec.getRef(), toolSpec.getCall(),
                        toolSpec.getSteps());
    }

    /**
     * Whether the resource that serves {@code uri} may ask its caller for input: the first one
     * whose URI, or URI template, matches it, as {@code resources/read} picks it: a static
     * resource also serves the files under its URI ({@link ResourceHandler#matchesStatic}).
     */
    public boolean resourceMayElicit(String uri) {
        for (McpServerResourceSpec resource : getMcpServerSpec().getResources().values()) {
            if (resource.isStatic()) {
                if (ResourceHandler.matchesStatic(resource.getUri(), uri)) {
                    return false;
                }
            } else if (ResourceHandler.matchTemplate(resource.getUri(), uri) != null) {
                return Elicitation.mayAsk(getCapability(), null, resource.getCall(),
                        resource.getSteps());
            }
        }
        return false;
    }

    /**
     * Whether the tool's flow declares {@code semantics.interaction.mode: task}. Only a tool that
     * references a flow with {@code ref:} can: inline steps carry no flow contract.
     */
    public boolean isTaskTool(String toolName) {
        McpServerToolSpec toolSpec = getMcpServerSpec().getTools().get(toolName);
        if (toolSpec == null || toolSpec.getRef() == null) {
            return false;
        }
        AggregateFlow flow = getCapability().lookupFlow(toolSpec.getRef());
        return flow != null && flow.getSemantics() != null
                && flow.getSemantics().getInteraction() != null
                && flow.getSemantics().getInteraction().isTask();
    }

    /** Seals and opens the {@code requestState} of multi round-trip requests. */
    public McpRequestState getRequestState() {
        return requestState;
    }

    /**
     * The {@code semantics.interaction.expectedDurationMs} of the tool's flow, or {@code null}
     * when it declares none.
     */
    public Long expectedDurationMs(String toolName) {
        McpServerToolSpec toolSpec = getMcpServerSpec().getTools().get(toolName);
        if (toolSpec == null || toolSpec.getRef() == null) {
            return null;
        }
        AggregateFlow flow = getCapability().lookupFlow(toolSpec.getRef());
        return flow != null && flow.getSemantics() != null
                && flow.getSemantics().getInteraction() != null
                        ? flow.getSemantics().getInteraction().getExpectedDurationMs()
                        : null;
    }

    /** Whether tasks can be served: a durable mode and at least one task tool. */
    public boolean servesTasks() {
        return taskService != null
                && getMcpServerSpec().getTools().keySet().stream().anyMatch(this::isTaskTool);
    }

    public McpServerSpec getMcpServerSpec() {
        return (McpServerSpec) getSpec();
    }

    public ToolHandler getToolHandler() {
        return toolHandler;
    }

    /**
     * @return the download side of the MCP files extension, or {@code null} when the exposes
     *         block declares no {@code files}
     */
    public McpFileDownloads getFileDownloads() {
        return fileDownloads;
    }

    public ResourceHandler getResourceHandler() {
        return resourceHandler;
    }

    public PromptHandler getPromptHandler() {
        return promptHandler;
    }

    public SkillHandler getSkillHandler() {
        return skillHandler;
    }

    public List<McpSchema.Tool> getTools() {
        return tools;
    }

    /**
     * The declared tags of each tool, by tool name. A tool without tags maps to an empty list.
     *
     * @return the tags by tool name
     */
    public Map<String, List<String>> getToolTags() {
        return toolTags;
    }

    /**
     * @return whether the tool takes a file ({@code type: file}): it is listed only to a client
     *         that declares the files extension
     */
    public boolean takesFiles(String toolName) {
        return fileTools.contains(toolName);
    }

    /**
     * @return the largest total decoded size of the inline files of one {@code tools/call}, as
     *         {@code server/discover} advertises it, or {@code 0} without a {@code files} block
     */
    public long getMaxInlineBytes() {
        return maxInlineBytes;
    }

    /** @return this endpoint's tool search: its modes, meta-tools, pinned tools and index */
    public ToolSearchCatalog getToolSearchCatalog() {
        return toolSearchCatalog;
    }

    public Map<String, String> getToolLabels() {
        return toolLabels;
    }

    @Override
    public void start() throws Exception {
        // Unfinished tasks kept by a durable store run again before new calls are served.
        int resumed = resumeTasks();
        if (resumed > 0) {
            Context.getCurrentLogger().log(Level.INFO, "Resumed " + resumed + " MCP task(s)"
                    + " (namespace: " + getMcpServerSpec().getNamespace() + ")");
        }
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
        // First, while the upstream clients and the store are still open: running tasks finish
        // their action in flight and stop, unfinished, for the next process to resume.
        if (taskService != null) {
            int cutShort = taskService.drain(DRAIN_TIMEOUT);
            if (cutShort > 0) {
                Context.getCurrentLogger().log(Level.WARNING, cutShort + " MCP task(s) still "
                        + "running after " + DRAIN_TIMEOUT.toSeconds() + " s; the next start "
                        + "resumes them (namespace: " + getMcpServerSpec().getNamespace() + ")");
            }
        }
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
