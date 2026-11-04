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
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.execution.ExecutionInputs;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.mcp.McpCallContext;
import io.ikanos.engine.exposes.mcp.McpRequestState;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.ikanos.engine.exposes.mcp.model.RequestClientCapabilities;
import io.ikanos.spec.util.IkanosMappers;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * A common base class for all MCP handlers.
 */
public abstract class McpCallHandler {

    protected final ObjectMapper MAPPER = IkanosMappers.json();
    protected final McpServerAdapter adapter;
    private final List<McpHeader> requiredHeaders;
    private final List<DispatchPreProcessor> preProcessors;
    private final List<DispatchPostProcessor> postProcessors;

    public McpCallHandler(McpServerAdapter adapter,
                             List<McpHeader> requiredHeaders,
                             List<DispatchPreProcessor> preProcessors,
                             List<DispatchPostProcessor> postProcessors) {
        this.adapter = adapter;
        this.requiredHeaders = requiredHeaders;
        this.preProcessors = preProcessors;
        this.postProcessors = postProcessors;
    }

    /**
     * Handles an MCP call for this handler.
     * @param requestBody the JsonRpc request's body.
     * @return the handler's result.
     */
    public abstract HandlerResult handle(JsonNode requestBody);

    /**
     * Get the list of headers that are required by this handler.
     * @return the list of headers required by this handler.
     */
    public List<McpHeader> getRequiredHeaders() {
        return requiredHeaders;
    }

    /**
     * Get the list of pre-processors to apply to incoming requests before invoking
     * the handler.
     * @return the list of pre-processors to run.
     */
    public List<DispatchPreProcessor> getPreProcessors() {
        return preProcessors;
    }

    /**
     * Get the list of post-processors to apply to outgoing responses after invoking
     * the handler.
     * @return the list of post-processors to run.
     */
    public List<DispatchPostProcessor> getPostProcessors() {
        return postProcessors;
    }

    /**
     * Runs one round of a multi round-trip request (MCP {@code 2026-07-28}). Answers collected in
     * earlier rounds travel in the sealed {@code requestState}, this round's in
     * {@code inputResponses}; both are made available to {@code elicit} steps while {@code run}
     * executes. When a step still asks for input, the result is an {@code InputRequiredResult},
     * or a {@code -32021} error when the request did not declare the elicitation capability.
     *
     * @param label     what is asked for, for the error message (e.g. {@code "Tool 'x'"})
     * @param name      the tool name or resource URI, bound into the request state
     * @param arguments the request arguments, bound into the request state; may be {@code null}
     * @throws IllegalArgumentException when the presented request state fails verification
     * @throws Exception what {@code run} throws, other than a request for input
     */
    protected HandlerResult inRounds(JsonNode idNode, JsonNode params, String label, String name,
            JsonNode arguments, Supplier<ObjectNode> run) throws Exception {
        String principal = McpCallContext.principal();
        String digest = McpRequestState.digest(getMethodName(), name, arguments);
        ObjectNode answers = collectAnswers(params, principal, digest);
        Map<NodePath, Object> updates = new LinkedHashMap<>();
        answers.properties().forEach(e -> updates.put(new NodePath(e.getKey()), e.getValue()));
        try {
            return new HandlerSuccessResult(ExecutionInputs.with(updates, run::get));
        } catch (InputRequiredException e) {
            if (!RequestClientCapabilities.from(params).supportsElicitation()) {
                ObjectNode required = MAPPER.createObjectNode();
                required.putObject("elicitation");
                return missingCapability(idNode,
                        label + " asks for input; it needs the elicitation capability", required);
            }
            return new HandlerSuccessResult(inputRequired(e, answers, principal, digest));
        }
    }

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

    /** A {@code -32021} error naming the client capabilities this request needs. */
    protected HandlerResult missingCapability(JsonNode idNode, String message,
            ObjectNode required) {
        ObjectNode data = MAPPER.createObjectNode();
        data.set("requiredCapabilities", required);
        return new HandlerFailureResult(MISSING_REQUIRED_CLIENT_CAPABILITY,
                buildJsonRpcError(idNode, MISSING_REQUIRED_CLIENT_CAPABILITY.getCode(), message,
                        data));
    }

    /**
     * Get the handler's method name.
     * @return the handler's method name.
     */
    public abstract String getMethodName();
}
