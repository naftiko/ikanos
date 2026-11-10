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
package io.ikanos.engine.exposes.rest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import io.ikanos.Capability;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.consumes.mcp.UpstreamTasks;
import io.ikanos.engine.execution.Durability;
import io.ikanos.engine.execution.Elicitation;
import io.ikanos.engine.execution.ExecutionInputs;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.mcp.InlineRounds;
import io.ikanos.engine.exposes.mcp.InputRequests;
import io.ikanos.engine.exposes.mcp.McpRequestState;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * REST operations that ask their caller for input ({@code elicit} steps), answered in rounds.
 *
 * <p>An operation that asks answers {@code 202 Accepted}, with MCP's {@code inputRequests} as its
 * body and a {@code Location} under {@value #PREFIX}: a sealed handle the caller {@code POST}s
 * its {@code inputResponses} to. The answer is the operation's own response once it completes,
 * or another {@code 202} with the next questions. In a durable mode the handle names a hidden
 * execution ({@link InlineRounds}), so steps run before the question, a write included, are
 * restored rather than run again; {@code GET} on the handle replays the last answer and
 * {@code DELETE} cancels. Under {@code none}, the handle carries the operation's input
 * parameters and the answers so far, and each answer replays the operation from the start.</p>
 */
public final class RestInputRequests {

    /** Path prefix of the answer resource, reserved in every REST adapter. */
    public static final String PREFIX = "/_ikanos/executions/";

    /** Largest handle, in characters, carried in a URL under {@code none}. */
    static final int MAX_HANDLE = 64 * 1024;

    /** Request digest every handle is sealed with: what it is bound to sits in the handle. */
    static final String DIGEST = "rest-input";

    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private final Capability capability;
    private final String namespace;
    private final String mount;
    private final McpRequestState requestState;
    private final InlineRounds inline;
    private final Map<String, ResourceRestlet> restlets = new LinkedHashMap<>();

    /**
     * @param mount the path the adapter is mounted under ({@code path:}), or {@code null} or
     *        {@code /} at the root: the {@code Location} of an answer starts with it
     * @param stopping whether the process is shutting down
     */
    public RestInputRequests(Capability capability, String namespace, String mount,
            BooleanSupplier stopping) {
        this.capability = capability;
        this.namespace = namespace;
        this.mount = mount == null || "/".equals(mount) ? "" : mount;
        this.requestState = McpRequestState.withKey(capability.getSealingKey(),
                capability.getSpecDigest());
        Durability durability = capability.getDurability();
        this.inline = durability != null && durability.isDurable()
                ? new InlineRounds(durability.runner(), Clock.systemUTC(), stopping)
                        .whenCancelled(id -> UpstreamTasks.cancelUnder(capability,
                                durability.runner(), id))
                : null;
    }

    /** Registers the restlet of a resource, so an answer can reach its operations. */
    void register(String resourcePath, ResourceRestlet restlet) {
        restlets.put(resourcePath, restlet);
    }

    /** Whether the operation may ask its caller for input. */
    boolean applies(RestServerOperationSpec operation) {
        return Elicitation.mayAsk(capability, operation.getRef(), operation.getCall(),
                operation.getSteps());
    }

    /** Runs the first round of an operation that may ask. */
    void first(ResourceRestlet restlet, RestServerOperationSpec operation, Request request,
            Response response, Map<String, Object> parameters) {
        String principal = principal(request);
        ObjectNode bound = bound(restlet, operation);
        JsonNode arguments = MAPPER.valueToTree(parameters);
        if (inline != null) {
            InlineRounds.Round round = inline.run(entry(restlet, operation), principal, arguments,
                    null, null, () -> restlet.runForRounds(operation, parameters));
            render(round, principal, bound, response);
        } else {
            bound.set("parameters", arguments);
            replay(restlet, operation, parameters, MAPPER.createObjectNode(), principal, bound,
                    response);
        }
    }

    /** The answer resource: {@code POST} answers, {@code GET} replays, {@code DELETE} cancels. */
    Restlet answerResource() {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                Object handle = request.getAttributes().get("handle");
                answer(request, response, handle != null ? handle.toString() : "");
            }
        };
    }

    void answer(Request request, Response response, String handle) {
        String principal = principal(request);
        McpRequestState.Content state;
        try {
            state = requestState.open(handle, principal, DIGEST);
        } catch (IllegalArgumentException e) {
            gone(response);
            return;
        }
        JsonNode bound = state.bound();
        // Adapters of one capability share the sealing key: a handle of another adapter, which
        // may serve the same path and method with other work, is not this adapter's.
        ResourceRestlet restlet = bound != null
                && namespace.equals(bound.path("namespace").asString(null))
                        ? restlets.get(bound.path("resource").asString()) : null;
        RestServerOperationSpec operation = restlet == null ? null
                : restlet.getResourceSpec().getOperations().values().stream()
                        .filter(op -> op.getMethod().equals(bound.path("method").asString()))
                        .findFirst().orElse(null);
        if (operation == null) {
            gone(response);
            return;
        }
        Method method = request.getMethod();
        if (state.executionId() == null) {
            if (!Method.POST.equals(method)) {
                response.setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
                return;
            }
            ObjectNode answers = state.answers().deepCopy();
            JsonNode responses = responses(request);
            for (String key : state.pending()) {
                if (responses.has(key)) {
                    answers.set(key, responses.get(key));
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters =
                    MAPPER.convertValue(bound.path("parameters"), Map.class);
            replay(restlet, operation, parameters, answers, principal, (ObjectNode) bound,
                    response);
            return;
        }
        ExecutionId id = new ExecutionId(state.executionId());
        ExecutionRecord record = inline == null ? null
                : inline.find(id, principal)
                        .filter(found -> entry(restlet, operation).equals(found.entry()))
                        .orElse(null);
        if (record == null) {
            gone(response);
            return;
        }
        if (Method.GET.equals(method)) {
            ObjectNode questions = record.status() == ExecutionStatus.SUSPENDED
                    ? inline.waitingOn(id) : null;
            if (questions != null) {
                accepted(response, handle, questions);
            } else {
                inline.result(id).ifPresentOrElse(outcome -> write(outcome, response),
                        () -> gone(response));
            }
            return;
        }
        if (Method.DELETE.equals(method)) {
            inline.cancel(id);
            response.setStatus(Status.SUCCESS_NO_CONTENT);
            return;
        }
        if (!Method.POST.equals(method)) {
            response.setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            return;
        }
        if (record.status() == ExecutionStatus.CANCELLED
                || record.status() == ExecutionStatus.FAILED) {
            gone(response);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = inline.arguments(id)
                .map(arguments -> (Map<String, Object>) MAPPER.convertValue(arguments, Map.class))
                .orElseGet(LinkedHashMap::new);
        InlineRounds.Round round = inline.run(entry(restlet, operation), principal, null, id,
                responses(request), () -> restlet.runForRounds(operation, parameters));
        render(round, principal, (ObjectNode) bound, response);
    }

    /** Under {@code none}: runs the operation from the start with the answers so far. */
    private void replay(ResourceRestlet restlet, RestServerOperationSpec operation,
            Map<String, Object> parameters, ObjectNode answers, String principal,
            ObjectNode bound, Response response) {
        Map<NodePath, Object> updates = new LinkedHashMap<>();
        answers.properties().forEach(e -> updates.put(new NodePath(e.getKey()), e.getValue()));
        try {
            JsonNode outcome = ExecutionInputs.with(updates,
                    () -> restlet.runForRounds(operation, parameters));
            write(outcome, response);
        } catch (InputRequiredException e) {
            List<String> pending = new ArrayList<>();
            e.questions().forEach(question -> pending.add(question.key()));
            // State a replay needs, such as an upstream's requestState, travels with the answers.
            e.carry().forEach(answers::set);
            String handle = requestState.seal(principal, DIGEST, answers, pending, null, bound);
            if (handle.length() > MAX_HANDLE) {
                restlet.sendError(response, Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE,
                        "This operation asks for input, and its request is too large to carry "
                                + "in the answer URL; declare a durability mode",
                        "Request too large for an input request handle", e);
                return;
            }
            accepted(response, handle, InputRequests.of(e));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void render(InlineRounds.Round round, String principal, ObjectNode bound,
            Response response) {
        if (round.result() != null) {
            write(round.result(), response);
            return;
        }
        List<String> pending = new ArrayList<>();
        round.questions().propertyNames().forEach(pending::add);
        String handle = requestState.seal(principal, DIGEST, MAPPER.createObjectNode(), pending,
                round.executionId().value(), bound);
        accepted(response, handle, round.questions());
    }

    private void accepted(Response response, String handle, ObjectNode questions) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("status", "input_required");
        body.set("inputRequests", questions);
        response.setStatus(Status.SUCCESS_ACCEPTED);
        response.setLocationRef(mount + PREFIX + handle);
        response.setEntity(body.toString(), MediaType.APPLICATION_JSON);
    }

    private static void write(JsonNode outcome, Response response) {
        response.setStatus(Status.valueOf(outcome.path("status").asInt(200)));
        if (outcome.has("body")) {
            response.setEntity(outcome.path("body").asString(),
                    MediaType.valueOf(outcome.path("mediaType").asString("application/json")));
        }
    }

    private static void gone(Response response) {
        response.setStatus(Status.CLIENT_ERROR_GONE);
        response.setEntity("{\"error\":\"This input request has expired or is unknown; call "
                + "the operation again\"}", MediaType.APPLICATION_JSON);
    }

    private ObjectNode bound(ResourceRestlet restlet, RestServerOperationSpec operation) {
        ObjectNode bound = MAPPER.createObjectNode();
        bound.put("namespace", namespace);
        bound.put("resource", restlet.getResourceSpec().getPath());
        bound.put("method", operation.getMethod());
        return bound;
    }

    private String entry(ResourceRestlet restlet, RestServerOperationSpec operation) {
        return "rest:" + namespace + " " + operation.getMethod() + " "
                + restlet.getResourceSpec().getPath();
    }

    /**
     * Who the input request is bound to: the credential the request presented in its
     * {@code Authorization} header, or nobody, when the handle itself is the capability.
     */
    static String principal(Request request) {
        String authorization = request.getHeaders() != null
                ? request.getHeaders().getFirstValue("Authorization", true) : null;
        return authorization != null ? "rest " + authorization : "rest anonymous";
    }

    private static JsonNode responses(Request request) {
        try {
            if (request.getEntity() == null || !request.getEntity().isAvailable()) {
                return MAPPER.createObjectNode();
            }
            JsonNode body = MAPPER.readTree(request.getEntity().getText());
            return body.path("inputResponses").isObject() ? body.get("inputResponses")
                    : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
