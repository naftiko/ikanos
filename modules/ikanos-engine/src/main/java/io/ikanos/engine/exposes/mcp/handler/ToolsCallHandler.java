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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.ErrorReference;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.modelcontextprotocol.spec.McpSchema;
import org.restlet.Context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;
import io.ikanos.engine.exposes.mcp.model.RequestClientCapabilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.execution.ExecutionInputs;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.mcp.McpCallContext;
import io.ikanos.engine.exposes.mcp.McpRequestState;

/**
 * The tools/call handler.
 */
public class ToolsCallHandler extends McpCallHandler {

    public static final String METHOD_NAME = "tools/call";

    public ToolsCallHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders, List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    @SuppressWarnings("unchecked")
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode idNode = requestBody.get("id");
        JsonNode params = requestBody.get("params");

        if (params == null) {
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: missing params"));
        }

        String toolName = params.path("name").asString("");
        JsonNode argumentsNode = params.get("arguments");
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
                    String entry =
                            "mcp:" + adapter.getMcpServerSpec().getNamespace() + "/" + toolName;
                    return new HandlerSuccessResult(adapter.getTaskService().create(entry,
                            principal, () -> callTool(toolName, arguments)));
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

            // Multi round-trip requests: answers collected in earlier rounds travel in the sealed
            // requestState; this round's answers in inputResponses.
            String digest = McpRequestState.digest(METHOD_NAME, toolName, argumentsNode);
            ObjectNode answers = collectAnswers(params, principal, digest);
            Map<NodePath, Object> updates = new LinkedHashMap<>();
            answers.properties().forEach(e -> updates.put(new NodePath(e.getKey()), e.getValue()));

            try {
                return new HandlerSuccessResult(
                        ExecutionInputs.with(updates, () -> callTool(toolName, arguments)));
            } catch (InputRequiredException e) {
                if (!capabilities.supportsElicitation()) {
                    ObjectNode required = MAPPER.createObjectNode();
                    required.putObject("elicitation");
                    return missingCapability(idNode, "Tool '" + toolName
                            + "' asks for input; it needs the elicitation capability", required);
                }
                return new HandlerSuccessResult(inputRequired(e, answers, principal, digest));
            }
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling tools call", e);
            return new HandlerFailureResult(INVALID_PARAMS, buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: " + e.getMessage()));
        } catch (Exception e) {
            String ref = ErrorReference.record(Level.SEVERE, "Error handling tools call", e);
            return new HandlerSuccessResult(toolError(ref));
        }
    }

    /** Longest expected duration, in milliseconds, still answered synchronously. */
    static final long SYNC_BUDGET_MS = 30_000;

    /**
     * Answers carried by the sealed {@code requestState}, plus this round's {@code inputResponses}
     * for keys that are outstanding. Responses to other keys are ignored, as MRTR requires.
     *
     * @throws IllegalArgumentException when the state fails verification
     */
    ObjectNode collectAnswers(JsonNode params, String principal, String digest) {
        String token = params.path("requestState").asString(null);
        if (token == null || token.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        McpRequestState.Content state = adapter.getRequestState().open(token, principal, digest);
        ObjectNode answers = state.answers().deepCopy();
        JsonNode responses = params.path("inputResponses");
        for (String key : state.pending()) {
            if (responses.has(key)) {
                answers.set(key, responses.get(key));
            }
        }
        return answers;
    }

    /** The {@code InputRequiredResult} for the questions a run stopped at. */
    ObjectNode inputRequired(InputRequiredException e, ObjectNode answers, String principal,
            String digest) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put(McpResultType.FIELD, McpResultType.INPUT_REQUIRED.wireValue());
        ObjectNode requests = result.putObject("inputRequests");
        List<String> pending = new ArrayList<>();
        for (InputRequiredException.Question question : e.questions()) {
            ObjectNode request = requests.putObject(question.key());
            request.put("method", "elicitation/create");
            ObjectNode requestParams = request.putObject("params");
            requestParams.put("mode", "form");
            requestParams.put("message", question.step().getMessage() != null
                    ? question.step().getMessage() : question.step().getName());
            ObjectNode schema = requestParams.putObject("requestedSchema");
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            if (question.step().getSchema() != null) {
                question.step().getSchema().forEach((name, property) -> {
                    properties.set(name, MAPPER.valueToTree(property));
                    required.add(name);
                });
            }
            pending.add(question.key());
        }
        result.put("requestState",
                adapter.getRequestState().seal(principal, digest, answers, pending));
        return result;
    }

    private HandlerResult missingCapability(JsonNode idNode, String message, ObjectNode required) {
        ObjectNode data = MAPPER.createObjectNode();
        data.set("requiredCapabilities", required);
        return new HandlerFailureResult(MISSING_REQUIRED_CLIENT_CAPABILITY,
                buildJsonRpcError(idNode, MISSING_REQUIRED_CLIENT_CAPABILITY.getCode(), message,
                        data));
    }

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
        try {
            McpSchema.CallToolResult toolResult =
                    adapter.getToolHandler().handleToolCall(toolName, arguments);
            ObjectNode result = MAPPER.valueToTree(toolResult);

            result.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
            return result;
        } catch (IllegalArgumentException | InputRequiredException e) {
            throw e;
        } catch (Exception e) {
            String ref = ErrorReference.record(Level.SEVERE, "Error handling tools call", e);
            return toolError(ref);
        }
    }

    /** A tool execution error, returned as a tool result with {@code isError: true}. */
    ObjectNode toolError(String ref) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", ErrorReference.withReference("Tool execution failed", ref));
        result.put("isError", true);
        result.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
        return result;
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
