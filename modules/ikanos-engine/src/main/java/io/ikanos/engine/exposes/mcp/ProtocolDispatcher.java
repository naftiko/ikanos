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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.mcp.handler.McpCallHandler;
import io.ikanos.engine.exposes.mcp.handler.McpCallHandlersFactory;
import io.ikanos.engine.exposes.mcp.handler.TasksCancelHandler;
import io.ikanos.engine.exposes.mcp.handler.TasksGetHandler;
import io.ikanos.engine.exposes.mcp.handler.TasksUpdateHandler;
import io.ikanos.engine.exposes.mcp.handler.ToolsCallHandler;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpRequestContext;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.engine.exposes.mcp.search.ToolSearchMode;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.observability.McpMetaTraceContextBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.exception.ProcessorException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.restlet.Request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_REQUEST;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.METHOD_NOT_FOUND;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;
import static org.restlet.Context.getCurrentLogger;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Transport-agnostic MCP JSON-RPC protocol dispatcher.
 *
 * Handles MCP protocol methods (tools/list, tools/call, resources/list,
 * resources/read, resources/templates/list, prompts/list, prompts/get, ping)
 * and produces JSON-RPC response envelopes. Used by both the Streamable HTTP
 * handler and the stdio handler.
 */
public class ProtocolDispatcher {

    public static final String JSONRPC_VERSION = "2.0";
    public static final String MCP_PROTOCOL_VERSION = "2026-07-28";

    /**
     * Every MCP protocol version this server speaks. This is the single source of truth for
     * version negotiation: the {@code server/discover} result, the per-request version check and
     * every {@code UnsupportedProtocolVersionError} are built from it, so supporting a new
     * version is a one-line change here.
     */
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(MCP_PROTOCOL_VERSION);

    /** Methods that act on a task, which answer to the authorization of the task's tool. */
    private static final Set<String> TASK_METHODS = Set.of(TasksGetHandler.METHOD_NAME,
            TasksUpdateHandler.METHOD_NAME, TasksCancelHandler.METHOD_NAME);

    private final ObjectMapper mapper;
    private final Map<String, McpCallHandler> mcpHandlers = new HashMap<>();
    private final String capabilityName;
    private final AgentIdentityResolver identityResolver;
    private final String namespace;
    private final McpServerSpec serverSpec;
    private final McpServerAdapter adapter;
    private final Set<String> requiredScopes;
    private final ToolSearchCatalog toolSearchCatalog;

    /**
     * Writes {@link #SUPPORTED_PROTOCOL_VERSIONS} into {@code node} as a JSON array named
     * {@code fieldName}, e.g. {@code supportedVersions} or {@code supported}.
     *
     * @param node the object to add the array to
     * @param fieldName the name of the array field
     * @return the created array
     */
    public static ArrayNode putSupportedProtocolVersions(ObjectNode node, String fieldName) {
        ArrayNode versions = node.putArray(fieldName);
        SUPPORTED_PROTOCOL_VERSIONS.forEach(versions::add);
        return versions;
    }

    /** @return the resolver for this adapter's {@code trust} block. */
    AgentIdentityResolver getIdentityResolver() {
        return identityResolver;
    }

    public ProtocolDispatcher(McpServerAdapter adapter) {
        this.mapper = IkanosMappers.json();
        this.capabilityName = resolveCapabilityName(adapter);
        this.serverSpec = adapter.getMcpServerSpec();
        this.adapter = adapter;
        this.requiredScopes = serverSpec != null
                ? AccessControl.requiredScopes(AccessControl.declaredIn(serverSpec)) : Set.of();
        this.toolSearchCatalog = adapter.getToolSearchCatalog();
        this.identityResolver = new AgentIdentityResolver(
                adapter.getMcpServerSpec() != null ? adapter.getMcpServerSpec().getTrust() : null);
        this.namespace = adapter.getMcpServerSpec() != null
                ? adapter.getMcpServerSpec().getNamespace() : null;
        for (McpCallHandler handler : McpCallHandlersFactory.createAll(adapter)) {
            mcpHandlers.put(handler.getMethodName(), handler);
        }
    }

    /**
     * Resolves the capability's title, or {@code null} when absent. Used to tag the
     * SERVER span created by each transport (Streamable HTTP reads it from the Restlet
     * {@code Context} attributes instead; stdio has no such context, so it reads it here).
     */
    private static String resolveCapabilityName(McpServerAdapter adapter) {
        if (adapter.getCapability().getSpec().getInfo() != null
                && adapter.getCapability().getSpec().getInfo().getTitle() != null) {
            return adapter.getCapability().getSpec().getInfo().getTitle();
        }
        return null;
    }

    /**
     * Dispatch a JSON-RPC request and return the dispatch result.
     *
     * <p>A notification, a request object with {@code "jsonrpc":"2.0"}, a string {@code method}
     * and no {@code id} member, gets no response: JSON-RPC forbids one, so the result has no body, which the Streamable
     * HTTP transport answers with {@code 202 Accepted} (once its headers are valid) and stdio with
     * nothing. A notification is never executed, even for a method that has a handler. Any other
     * message without an {@code id}, such as a batch array or an object whose {@code jsonrpc} is
     * missing or not {@code "2.0"}, is still answered with an error.</p>
     *
     * @param request the parsed JSON-RPC request.
     * @return the JSON-RPC response envelope + rpcError wrapped in a {@link DispatchResult}, with
     *         no body for a notification.
     */
    public DispatchResult dispatch(JsonNode request) {
        return dispatch(request, McpRequestContext.of(toolSearchCatalog));
    }

    /**
     * Dispatch a JSON-RPC request with what the request asks of the endpoint beyond its body,
     * such as a tag filter on the endpoint URL. Behaves as {@link #dispatch(JsonNode)} otherwise.
     *
     * @param request the parsed JSON-RPC request.
     * @param context the request's context, handed to the method handler.
     * @return the JSON-RPC response envelope + rpcError wrapped in a {@link DispatchResult}, with
     *         no body for a notification.
     */
    public DispatchResult dispatch(JsonNode request, McpRequestContext context) {
        if (request == null) {
            return new DispatchResult(buildJsonRpcError(null, INVALID_REQUEST.getCode(),
                    "Invalid Request: request body is missing"), INVALID_REQUEST);
        }
        if (isNotification(request)) {
            return new DispatchResult((ObjectNode) null);
        }

        try {
            JsonNode idNode = request.get("id");
            String rpcMethod = request.path("method").asString("");

            McpCallHandler callHandler = mcpHandlers.get(rpcMethod);
            if (callHandler != null) {
                for (DispatchPreProcessor preProcessor : callHandler.getPreProcessors()) {
                    preProcessor.apply(request);
                }

                HandlerResult result = callHandler.handle(request, context);

                switch (result) {
                    case HandlerFailureResult failureResult -> {
                        // In case of failure, the response's body is already complete, we simply return it
                        return new DispatchResult(failureResult.body(), failureResult.rpcError());
                    }
                    case HandlerSuccessResult successResult -> {
                        ObjectNode rpcResponse = mapper.createObjectNode();
                        rpcResponse.put("jsonrpc", JSONRPC_VERSION);

                        if (idNode != null) {
                            rpcResponse.set("id", idNode);
                        }
                        rpcResponse.set("result", successResult.result());

                        for (DispatchPostProcessor postProcessor : callHandler.getPostProcessors()) {
                            postProcessor.apply(rpcResponse);
                        }

                        return new DispatchResult(rpcResponse);
                    }
                }
            } else {
                return new DispatchResult(buildJsonRpcError(idNode, METHOD_NOT_FOUND.getCode(),
                        "Method not found: " + rpcMethod), METHOD_NOT_FOUND);
            }
        } catch (ProcessorException e) {
            getCurrentLogger().log(Level.SEVERE, "An error has occurred while processing a request", e);
            return new DispatchResult(e.getResult(), e.getRpcError());
        } catch (Exception e) {
            return new DispatchResult(McpErrors.internalError(request.get("id"),
                    "Error processing request", e), INTERNAL_ERROR);
        }
    }

    /**
     * Whether the message is a well-formed JSON-RPC 2.0 notification: an object declaring
     * {@code "jsonrpc":"2.0"}, with a string {@code method} and no {@code id} member. A malformed
     * envelope is not a notification, so it still reaches validation and gets an error.
     */
    private static boolean isNotification(JsonNode request) {
        return request.isObject()
                && JSONRPC_VERSION.equals(request.path("jsonrpc").asString(""))
                && request.path("method").isString()
                && !request.has("id");
    }

    /**
     * Dispatch a JSON-RPC request within a SERVER span, extracting W3C trace context from the
     * request's {@code params._meta} object (transport-agnostic carrier per the MCP
     * {@code 2026-07-28} revision) and, when supplied, falling back to the given HTTP
     * {@code traceparent} header when {@code _meta} carries no trace context.
     *
     * <p>Used by both transports so span creation, MDC population, and error recording happen
     * identically for Streamable HTTP ({@link McpServerResource}) and stdio
     * ({@link StdioJsonRpcHandler}).</p>
     *
     * @param request     the parsed JSON-RPC request.
     * @param httpRequest the inbound Restlet HTTP request, or {@code null} on stdio.
     * @return the JSON-RPC response envelope + rpcError wrapped in a {@link DispatchResult}, with
     *         no body for a notification (see {@link #dispatch(JsonNode)}).
     */
    public DispatchResult dispatchWithTracing(JsonNode request, Request httpRequest) {
        if (request == null) {
            return new DispatchResult(buildJsonRpcError(null, INVALID_REQUEST.getCode(),
                    "Invalid Request: request body is missing"), INVALID_REQUEST);
        }

        String rpcMethod = request.path("method").asString("");
        Context extractedContext = (httpRequest != null)
                ? McpMetaTraceContextBridge.extractContext(request, httpRequest)
                : McpMetaTraceContextBridge.extractContext(request);

        Span span = TelemetryBootstrap.get().startServerSpan("mcp", rpcMethod,
                extractedContext, null, capabilityName);
        try (Scope scope = span.makeCurrent()) {
            TelemetryBootstrap.populateMdc(span);
            Optional<AgentIdentity> identity = identityResolver.resolve(httpRequest);
            identity.ifPresent(id -> TelemetryBootstrap.recordAgentIdentity(span, id.id(),
                    id.source(), id.actor()));
            McpRequestContext context;
            try {
                context = McpRequestContext.from(httpRequest, toolSearchCatalog);
            } catch (IllegalArgumentException e) {
                // An unknown ?toolSearch= value is a client configuration error: say so loudly
                // rather than silently listing every tool. A notification still gets no answer.
                return isNotification(request) ? new DispatchResult((ObjectNode) null)
                        : new DispatchResult(buildJsonRpcError(request.get("id"),
                                INVALID_REQUEST.getCode(), "Invalid Request: " + e.getMessage()),
                                INVALID_REQUEST);
            }
            // The variant on every server span shows the fleet mix and per-variant cost.
            span.setAttribute(TelemetryBootstrap.ATTR_TOOL_SEARCH_VARIANT, context.mode().value());
            // State bound to the caller (requestState, tasks) reads the owner from here. It
            // comes from the authentication, not from the audit identity, which trust may turn off.
            String owner = AgentIdentityResolver.ownerOf(namespace, httpRequest, identity);
            return McpCallContext.as(owner, () -> dispatch(request, context));
        } catch (Exception e) {
            TelemetryBootstrap.recordError(span, e);
            throw e;
        } finally {
            TelemetryBootstrap.clearMdc();
            TelemetryBootstrap.endSpan(span);
        }
    }

    /**
     * Returns the scopes or claims the caller's token must carry for this JSON-RPC request
     * (#999): for a {@code tools/call} of a tool that declares {@code authorization}, the tool's;
     * otherwise the adapter's. Only the HTTP transport enforces it, as stdio carries no token.
     *
     * @param request the parsed JSON-RPC request
     * @return the requirement, or {@code null} when none applies
     */
    AuthorizationSpec requirementFor(JsonNode request) {
        return requirementFor(request, null);
    }

    /**
     * Returns the scopes or claims the caller's token must carry for this JSON-RPC request, as
     * {@link #requirementFor(JsonNode)} does, and also, for {@code tasks/get},
     * {@code tasks/update} and {@code tasks/cancel} of a task the caller owns, the requirement
     * of the tool that task runs: a token of the same owner that lost the tool's scopes or
     * claims may not read, answer or cancel it. Another owner's task, or an unknown ID, gets the
     * adapter's requirement, so the answer never tells whether that task exists.
     *
     * @param request the parsed JSON-RPC request
     * @param httpRequest the inbound HTTP request, which names the task's owner
     * @return the requirement, or {@code null} when none applies
     */
    AuthorizationSpec requirementFor(JsonNode request, Request httpRequest) {
        if (serverSpec == null) {
            return null;
        }
        String tool = null;
        String method = request != null ? request.path("method").asString("") : "";
        if (ToolsCallHandler.METHOD_NAME.equals(method)) {
            tool = request.path("params").path("name").asString("");
        } else if (httpRequest != null && TASK_METHODS.contains(method)) {
            tool = taskToolName(request.path("params").path("taskId").asString(null), httpRequest);
        }
        // One lookup with the embedded check (#1126): the tool as ToolHandler registers it
        return tool != null ? adapter.requirementFor(tool)
                : AccessControl.effective(serverSpec, null);
    }

    /**
     * Returns every requirement the caller's token must meet for this JSON-RPC request, in the
     * order they are checked. Most requests have one, {@link #requirementFor(JsonNode, Request)}.
     * A call of the call meta-tool in deferred mode runs other tools, so it gets the requirement
     * of each tool it names, as a direct {@code tools/call} of that tool would: the meta-tool's
     * name alone gives the adapter's requirement, and a token without a tool's scopes or claims
     * could otherwise run that tool through the meta-tool. A name that is no tool gets the
     * adapter's requirement, as a direct call of an unknown tool does, and so does a batch the
     * meta-tool refuses as a whole (no calls, or more than {@code maxToolCalls}).
     *
     * @param request the parsed JSON-RPC request
     * @param httpRequest the inbound HTTP request, which selects the tool search mode
     * @return the requirements, without duplicates; a {@code null} element requires nothing
     */
    List<AuthorizationSpec> requirementsFor(JsonNode request, Request httpRequest) {
        List<String> targets = callToolTargets(request, httpRequest);
        if (targets == null) {
            return Collections.singletonList(requirementFor(request, httpRequest));
        }
        Set<AuthorizationSpec> requirements = new LinkedHashSet<>();
        if (targets.isEmpty()) {
            requirements.add(AccessControl.effective(serverSpec, null));
        }
        for (String name : targets) {
            requirements.add(adapter.requirementFor(name));
        }
        return new ArrayList<>(requirements);
    }

    /**
     * The names of the tools a call of the call meta-tool runs, as {@code ToolsCallHandler}
     * reads them, or {@code null} when the request is not such a call: another method or tool,
     * listed mode, or an unknown {@code ?toolSearch=} value, which dispatch refuses. The list is
     * empty when the meta-tool refuses the batch as a whole.
     */
    private List<String> callToolTargets(JsonNode request, Request httpRequest) {
        if (serverSpec == null || toolSearchCatalog == null || request == null
                || !ToolsCallHandler.METHOD_NAME.equals(request.path("method").asString(""))) {
            return null;
        }
        JsonNode params = request.path("params");
        if (!toolSearchCatalog.isCallTool(params.path("name").asString(""))) {
            return null;
        }
        try {
            if (McpRequestContext.from(httpRequest, toolSearchCatalog).mode()
                    != ToolSearchMode.DEFERRED) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        JsonNode calls = params.path("arguments").path("calls");
        List<String> names = new ArrayList<>();
        if (calls.isArray() && calls.size() <= toolSearchCatalog.maxToolCalls()) {
            for (JsonNode call : calls) {
                names.add(call.path("name").isString() ? call.path("name").asString() : "");
            }
        }
        return names;
    }

    /**
     * Every scope the adapter or one of its tools requires. A challenge for missing scopes also
     * asks for the ones the token holds among them, as MCP's Scope Challenge Handling asks.
     */
    Set<String> requiredScopes() {
        return requiredScopes;
    }

    /**
     * The name of the tool a task of the caller runs, or {@code null} for an unknown or foreign
     * task.
     */
    private String taskToolName(String taskId, Request httpRequest) {
        McpTaskService tasks = adapter.getTaskService();
        if (tasks == null || taskId == null) {
            return null;
        }
        // The owner the task was created under: from the token under oauth2, as in dispatch
        String owner = AgentIdentityResolver.ownerOf(namespace, httpRequest, Optional.empty());
        String prefix = "mcp:" + namespace + "/";
        return tasks.entryOf(taskId, owner)
                .filter(entry -> entry.startsWith(prefix))
                .map(entry -> entry.substring(prefix.length()))
                .orElse(null);
    }

    /**
     * Returns the method handler of a specific method.
     * @param methodName the method's name.
     * @return the handler if found, null otherwise.
     */
    public McpCallHandler getMethodHandler(String methodName) {
        return mcpHandlers.get(methodName);
    }

    ObjectMapper getMapper() {
        return mapper;
    }
}
