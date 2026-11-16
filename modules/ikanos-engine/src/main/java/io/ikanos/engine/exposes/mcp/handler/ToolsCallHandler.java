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
package io.ikanos.engine.exposes.mcp.handler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.ErrorReference;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.engine.exposes.mcp.McpToolJson;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.model.McpRequestContext;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.engine.exposes.mcp.search.ToolSearchMode;
import io.ikanos.engine.exposes.mcp.search.ToolSearchQuery;
import io.ikanos.engine.exposes.mcp.search.ToolSearchUnavailableException;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.restlet.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;
import io.ikanos.engine.exposes.mcp.model.RequestClientCapabilities;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.mcp.McpCallContext;

/**
 * The tools/call handler.
 *
 * <p>In deferred mode, two meta-tools exist besides the declared tools: the search meta-tool,
 * which returns the full definitions of the best-matching tools, and the call meta-tool, which
 * calls one of them by name. A direct call of a declared tool works in every mode.</p>
 */
public class ToolsCallHandler extends McpCallHandler {

    /** For the static methods, which have no handler instance: the same mapper as MAPPER. */
    private static final ObjectMapper JSON = IkanosMappers.json();

    public static final String METHOD_NAME = "tools/call";

    static final String NO_MATCH_HINT = "No tools matched. Try broader keywords or remove filters.";
    static final String SEARCH_UNAVAILABLE = "Tool search is unavailable on this endpoint.";

    public ToolsCallHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        return handle(requestBody, McpRequestContext.of(adapter.getToolSearchCatalog()));
    }

    @Override
    public HandlerResult handle(JsonNode requestBody, McpRequestContext context) {
        JsonNode idNode = requestBody.get("id");
        JsonNode params = requestBody.get("params");

        if (params == null) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: missing params"));
        }

        String toolName = params.path("name").asString("");
        JsonNode argumentsNode = params.get("arguments");
        ToolSearchCatalog catalog = adapter.getToolSearchCatalog();

        if (context.mode() == ToolSearchMode.DEFERRED) {
            if (catalog.isSearchTool(toolName)) {
                return new HandlerSuccessResult(searchTools(catalog, argumentsNode, context));
            }
            if (catalog.isCallTool(toolName)) {
                return new HandlerSuccessResult(callTool(catalog, argumentsNode));
            }
        }
        return callDeclaredTool(idNode, params, toolName, argumentsNode);
    }

    /** Calls a declared tool by name, as a client does with a plain {@code tools/call}. */
    @SuppressWarnings("unchecked")
    private HandlerResult callDeclaredTool(JsonNode idNode, JsonNode params, String toolName,
            JsonNode argumentsNode) {
        RequestClientCapabilities capabilities = RequestClientCapabilities.from(params);
        String principal = McpCallContext.principal();

        try {
            Map<String, Object> arguments = argumentsNode != null
                    ? MAPPER.treeToValue(argumentsNode, Map.class)
                    : new ConcurrentHashMap<>();

            // Tasks (SEP-2663): the server decides per request. A task is returned only when the
            // tool's flow declares mode: task, the capability is durable, and this request
            // declares the tasks extension; otherwise the call is answered synchronously.
            if (adapter.getTaskService() != null && adapter.isTaskTool(toolName)) {
                if (capabilities.supportsTasks()) {
                    // A task that may ask needs a client that can answer: fail before creating
                    // one that could never be answered (SEP-2663 input requests).
                    if (!capabilities.supportsElicitation() && adapter.mayElicit(toolName)) {
                        ObjectNode required = MAPPER.createObjectNode();
                        required.putObject("elicitation");
                        return missingCapability(idNode, "Tool '" + toolName
                                + "' asks for input; it needs the elicitation capability",
                                required);
                    }
                    String entry =
                            "mcp:" + adapter.getMcpServerSpec().getNamespace() + "/" + toolName;
                    return new HandlerSuccessResult(adapter.getTaskService().create(entry,
                            principal, argumentsNode, () -> callTool(toolName, arguments)));
                }
                // A client without tasks is answered synchronously only when the flow is expected
                // to finish within the synchronous budget.
                Long expected = adapter.expectedDurationMs(toolName);
                if (expected != null && expected > SYNC_BUDGET_MS) {
                    return missingCapability(idNode, "Tool '" + toolName
                            + "' runs longer than a synchronous call allows; it needs the tasks extension",
                            requiredExtension(RequestClientCapabilities.TASKS_EXTENSION));
                }
            }

            return inRounds(idNode, params, "Tool '" + toolName + "'", toolName,
                    argumentsNode, () -> callTool(toolName, arguments));
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling tools call", e);
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: " + e.getMessage()));
        } catch (Exception e) {
            return new HandlerSuccessResult(executionFailure(e));
        }
    }

    /**
     * Runs the search meta-tool. Input errors are tool errors ({@code isError}), which the model
     * can act on, not JSON-RPC errors. A tag filter on the endpoint URL also limits the search,
     * so a client scoped to some tags never finds the others.
     */
    ObjectNode searchTools(ToolSearchCatalog catalog, JsonNode arguments,
            McpRequestContext context) {
        long start = System.nanoTime();
        Span span = TelemetryBootstrap.get().startToolSearchSpan(catalog.namespace());
        ObjectNode result = null;
        try (Scope scope = span.makeCurrent()) {
            result = runSearch(catalog, arguments, context, span);
            return result;
        } catch (RuntimeException e) {
            TelemetryBootstrap.recordError(span, e);
            throw e;
        } finally {
            String outcome = "error";
            if (result != null && !result.path("isError").asBoolean()) {
                JsonNode tools = result.path("structuredContent").path("tools");
                span.setAttribute(TelemetryBootstrap.ATTR_TOOL_SEARCH_RESULT_COUNT,
                        (long) tools.size());
                if (!tools.isEmpty()) {
                    span.setAttribute(TelemetryBootstrap.ATTR_TOOL_SEARCH_TOP_TOOL,
                            tools.get(0).path("name").asString());
                }
                outcome = tools.isEmpty() ? "miss" : "hit";
            }
            TelemetryBootstrap.endSpan(span);
            TelemetryBootstrap.get().getMetrics().recordToolSearch(catalog.namespace(), outcome,
                    (System.nanoTime() - start) / 1_000_000_000.0);
        }
    }

    private ObjectNode runSearch(ToolSearchCatalog catalog, JsonNode arguments,
            McpRequestContext context, Span span) {
        JsonNode args = arguments != null ? arguments : MAPPER.createObjectNode();
        String text = args.path("query").isString() ? args.path("query").asString() : null;
        if (text == null || text.isBlank()) {
            return toolError("query is required: describe what you want to do, in a few words.");
        }
        if (text.length() > ToolSearchCatalog.MAX_QUERY_LENGTH) {
            return toolError("query is too long: use at most " + ToolSearchCatalog.MAX_QUERY_LENGTH
                    + " characters.");
        }

        Set<String> tags = new LinkedHashSet<>();
        JsonNode tagsNode = args.get("tags");
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isArray()) {
                return toolError("tags must be an array of strings.");
            }
            tagsNode.forEach(tag -> tags.add(tag.asString()));
        }

        JsonNode readOnlyNode = args.get("readOnly");
        if (readOnlyNode != null && !readOnlyNode.isNull() && !readOnlyNode.isBoolean()) {
            return toolError("readOnly must be true or false.");
        }
        boolean readOnly = readOnlyNode != null && readOnlyNode.asBoolean(false);

        int limit = catalog.maxResults();
        JsonNode limitNode = args.get("limit");
        if (limitNode != null && !limitNode.isNull()) {
            if (!limitNode.isIntegralNumber() || limitNode.asInt() < 1) {
                return toolError("limit must be a whole number of at least 1.");
            }
            limit = Math.min(limitNode.asInt(), ToolSearchCatalog.MAX_LIMIT);
        }

        span.setAttribute(TelemetryBootstrap.ATTR_TOOL_SEARCH_FILTERS,
                filterNames(tagsNode, context, readOnlyNode, limitNode));
        try {
            // The URL tag filter and the tags argument are two conditions: a tool must meet both.
            List<String> names = catalog.search(new ToolSearchQuery(text, tags, readOnly, limit),
                    context.tagFilter());
            return searchResult(names, catalog);
        } catch (ToolSearchUnavailableException e) {
            span.setAttribute(TelemetryBootstrap.ATTR_TOOL_SEARCH_UNAVAILABLE, true);
            String ref = ErrorReference.record(Level.SEVERE, "MCP tool search is unavailable", e);
            return toolError(ErrorReference.withReference(SEARCH_UNAVAILABLE, ref));
        }
    }

    /** The names of the filters a search used, for telemetry: never their values. */
    private static String filterNames(JsonNode tags, McpRequestContext context, JsonNode readOnly,
            JsonNode limit) {
        List<String> names = new ArrayList<>();
        if (tags != null && !tags.isNull()) {
            names.add("tags");
        }
        if (!context.tagFilter().isEmpty()) {
            names.add("urlTags");
        }
        if (readOnly != null && readOnly.asBoolean(false)) {
            names.add("readOnly");
        }
        if (limit != null && !limit.isNull()) {
            names.add("limit");
        }
        return String.join(",", names);
    }

    /**
     * The search result: the found tools' full definitions in {@code structuredContent}, written
     * by the same serialiser as {@code tools/list}, and the same JSON as text for clients that
     * read text only.
     */
    private ObjectNode searchResult(List<String> names, ToolSearchCatalog catalog) {
        ObjectNode structured = MAPPER.createObjectNode();
        ArrayNode tools = structured.putArray("tools");
        Map<String, String> labels = adapter.getToolLabels();
        for (String name : names) {
            catalog.tool(name).ifPresent(tool -> tools.add(McpToolJson.write(tool, labels, MAPPER)));
        }

        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode text = result.putArray("content").addObject();
        text.put("type", "text");
        text.put("text", tools.isEmpty() ? NO_MATCH_HINT : MAPPER.writeValueAsString(structured));
        result.set("structuredContent", structured);
        result.put("isError", false);
        result.put("resultType", "complete");
        return result;
    }

    /**
     * Runs the call meta-tool: calls each named tool with its arguments, in order, exactly as a
     * direct {@code tools/call} would, so results, errors and telemetry are the same. Calls run
     * in sequence, and a failing call does not stop the others: each gets its own result, with
     * {@code isError} when it failed. Only a malformed request, such as more calls than
     * {@code maxToolCalls}, is a tool error as a whole.
     */
    ObjectNode callTool(ToolSearchCatalog catalog, JsonNode arguments) {
        JsonNode args = arguments != null ? arguments : MAPPER.createObjectNode();
        JsonNode calls = args.get("calls");
        if (calls == null || !calls.isArray() || calls.isEmpty()) {
            return toolError("calls is required: a list of {name, arguments} for tools returned"
                    + " by " + catalog.searchToolName() + ".");
        }
        if (calls.size() > catalog.maxToolCalls()) {
            return toolError("Too many calls: " + catalog.callToolName() + " runs at most "
                    + catalog.maxToolCalls() + " tools per request.");
        }

        ObjectNode structured = MAPPER.createObjectNode();
        ArrayNode results = structured.putArray("results");
        for (JsonNode call : calls) {
            String name = call.path("name").isString() ? call.path("name").asString() : "";
            ObjectNode entry = results.addObject();
            entry.put("name", name);
            entry.set("result", callOne(catalog, name, call.get("arguments")));
        }

        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode text = result.putArray("content").addObject();
        text.put("type", "text");
        text.put("text", MAPPER.writeValueAsString(structured));
        result.set("structuredContent", structured);
        result.put("isError", false);
        result.put("resultType", "complete");
        return result;
    }

    /** One call of the call meta-tool; every failure is a tool error for that call only. */
    @SuppressWarnings("unchecked")
    private ObjectNode callOne(ToolSearchCatalog catalog, String name, JsonNode toolArguments) {
        if (name.isBlank()) {
            return toolError("name is required: the name of a tool returned by "
                    + catalog.searchToolName() + ".");
        }
        if (catalog.isSearchTool(name) || catalog.isCallTool(name)) {
            return toolError(catalog.callToolName() + " cannot call " + name + ".");
        }
        if (catalog.tool(name).isEmpty()) {
            return toolError("Unknown tool: " + name + ". Use " + catalog.searchToolName()
                    + " to find tools.");
        }
        if (!catalog.isSearchable(name)) {
            // A listed tool is called directly, so the client sees its own approval hints.
            return toolError(name + " is listed with the other tools: call it directly, not"
                    + " through " + catalog.callToolName() + ".");
        }
        if (toolArguments != null && !toolArguments.isNull() && !toolArguments.isObject()) {
            return toolError("arguments must be an object.");
        }
        TelemetryBootstrap.get().getMetrics().recordToolSearchCall(catalog.namespace());
        try {
            Map<String, Object> callArguments = toolArguments != null && toolArguments.isObject()
                    ? MAPPER.treeToValue(toolArguments, Map.class)
                    : new ConcurrentHashMap<>();
            McpSchema.CallToolResult toolResult =
                    adapter.getToolHandler().handleToolCall(name, callArguments);
            ObjectNode result = MAPPER.valueToTree(toolResult);
            result.put("resultType", "complete");
            return result;
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.FINE, "Invalid arguments for " + name, e);
            return toolError("Invalid arguments for " + name + ": " + e.getMessage());
        } catch (Exception e) {
            return executionFailure(e);
        }
    }

    /** A tool execution failure: a generic message with a reference id, detail logged. */
    static ObjectNode executionFailure(Exception e) {
        String ref = ErrorReference.record(Level.SEVERE, "Error handling tools call", e);
        return toolError(ErrorReference.withReference("Tool execution failed", ref));
    }

    /** A tool error, returned as a tool result with {@code isError: true}. */
    static ObjectNode toolError(String message) {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", message);
        result.put("isError", true);
        result.put("resultType", "complete");
        return result;
    }

    @Override
    protected boolean mayAsk(String toolName) {
        return adapter.mayElicit(toolName);
    }

    /** Longest expected duration, in milliseconds, still answered synchronously. */
    static final long SYNC_BUDGET_MS = 30_000;

    private ObjectNode requiredExtension(String extension) {
        ObjectNode required = MAPPER.createObjectNode();
        required.putObject("extensions").putObject(extension);
        return required;
    }

    /**
     * Call a tool and return its {@code CallToolResult}. A tool execution error becomes a result
     * with {@code isError: true}; invalid parameters are rethrown as an
     * {@link IllegalArgumentException}, which callers turn into a JSON-RPC error. A task runs this
     * same method, so its result never differs from the synchronous one.
     */
    ObjectNode callTool(String toolName, Map<String, Object> arguments) {
        return callTool(adapter, toolName, arguments);
    }

    /**
     * Calls a tool of {@code adapter}, as {@link #callTool(String, Map)} does: the work of a task,
     * including one started again after a restart.
     */
    public static ObjectNode callTool(McpServerAdapter adapter, String toolName,
            Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult toolResult =
                    adapter.getToolHandler().handleToolCall(toolName, arguments);
            ObjectNode result = JSON.valueToTree(toolResult);

            result.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
            return result;
        } catch (IllegalArgumentException | InputRequiredException e) {
            throw e;
        } catch (Exception e) {
            return executionFailure(e);
        }
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
