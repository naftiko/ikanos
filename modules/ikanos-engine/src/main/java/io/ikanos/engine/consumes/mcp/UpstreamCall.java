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
package io.ikanos.engine.consumes.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.execution.DurableScope;
import io.ikanos.engine.execution.ExecutionInputs;
import io.ikanos.engine.execution.Executions;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code tools/call} to an upstream MCP server that may answer with a task or ask for input
 * (Execution &amp; Resolution Model §7.14.12).
 *
 * <p>Inside a journaled run (a task, or a journal-backed inline call), the call declares the tasks
 * extension and runs a small journaled run of its own: {@code create} sends the call; then, round
 * after round, {@code await@n} polls an upstream task, {@code answer@n} retries a multi round-trip
 * request with the answers and the upstream's {@code requestState}, or {@code update@n} sends the
 * answers to an upstream task through {@code tasks/update}. A restart restores what was journaled,
 * so the tool is never called twice. Without a journal, the call is sent directly and declares no
 * tasks; an upstream question carries the upstream's {@code requestState} in the caller's sealed
 * state, and the replayed call sends it back.</p>
 *
 * <p>When the tool declares {@code mayElicit: true}, the call also declares the elicitation
 * capability, and an upstream's questions pass through to the caller, keyed under the calling
 * step's path. An upstream that asks without the declaration fails the call. In a journaled run,
 * a send carries only the answers of the caller's latest round, and only once: an upstream that
 * asks the same key again, after rejecting an answer, asks the caller again.</p>
 */
final class UpstreamCall {

    /** Key, under the calling step's path, of the upstream's {@code requestState} when no journal is kept. */
    static final String UPSTREAM_STATE = "upstream-state";

    /** Bounds of the polling interval of an upstream task. */
    static final Duration MIN_POLL = Duration.ofMillis(250);
    static final Duration MAX_POLL = Duration.ofSeconds(30);

    private static final String TASKS = "io.modelcontextprotocol/tasks";

    /** Field of a send and its outcome: the caller's round whose answers it sent. */
    private static final String ANSWERED_IN = "answeredIn";
    private static final ObjectMapper JSON = IkanosMappers.json();

    private final McpClientAdapter adapter;
    private final McpStreamableHttpTransport transport;
    private final String toolName;
    private final ObjectNode arguments;
    private final Map<String, Object> templates;
    private final boolean mayElicit;
    private final String prefix;
    private final Map<String, JsonNode> answers;
    private final Map<String, JsonNode> carried;
    private final Map<String, JsonNode> latest;
    private final int latestRound;

    UpstreamCall(McpClientAdapter adapter, McpStreamableHttpTransport transport, String toolName,
            ObjectNode arguments, Map<String, Object> templates, boolean mayElicit) {
        this.adapter = adapter;
        this.transport = transport;
        this.toolName = toolName;
        this.arguments = arguments;
        this.templates = templates;
        this.mayElicit = mayElicit;
        this.prefix = Executions.currentKeyPrefix();
        this.answers = answersUnder(prefix, ExecutionInputs.answers());
        this.carried = answersUnder(prefix, ExecutionInputs.carried());
        this.latest = answersUnder(prefix, ExecutionInputs.latestAnswers());
        this.latestRound = ExecutionInputs.latestRound();
    }

    /** Runs the call and returns the upstream's {@code CallToolResult}. */
    JsonNode run() {
        if (!DurableScope.isJournaled()) {
            return direct();
        }
        ExecutionResult result = DurableScope.runJournaled(interpreter(), action -> {
            String step = action.path().value();
            ObjectNode payload = (ObjectNode) action.action();
            try {
                if (step.equals("create")) {
                    return reply(call(null, null, true));
                }
                if (step.startsWith("answer")) {
                    ObjectNode reply = reply(call(payload.get("requestState"),
                            payload.get("responses"), true));
                    reply.set(ANSWERED_IN, payload.get(ANSWERED_IN));
                    return reply;
                }
                if (step.startsWith("update")) {
                    update(payload.path("taskId").asString(), payload.get("responses"));
                    ObjectNode task = payload.deepCopy();
                    task.remove("responses");
                    task.put("kind", "task");
                    return task;
                }
                return await(payload);
            } catch (McpClientException e) {
                throw new ActionFailedException(new Failure("upstream", e.getMessage()), e);
            }
        }).orElseThrow();
        if (result instanceof ExecutionResult.Completed completed) {
            return (JsonNode) completed.result();
        }
        if (result instanceof ExecutionResult.Suspended suspended) {
            throw questions((JsonNode) suspended.awaiting().get(0).request(), Map.of());
        }
        throw new McpClientException(((ExecutionResult.Failed) result).failure().message());
    }

    /** Without a journal: one call, an upstream question carried in the caller's sealed state. */
    private JsonNode direct() {
        // Carried apart from the answers: an upstream question keyed like it is still answered.
        JsonNode state = carried.get(UPSTREAM_STATE);
        ObjectNode responses = JSON.createObjectNode();
        answers.forEach(responses::set);
        JsonNode result = call(state, state != null ? responses : null, false);
        String type = resultType(result);
        if (McpResultType.INPUT_REQUIRED.wireValue().equals(type)) {
            requireDeclared();
            throw questions(result.path("inputRequests"),
                    Map.of(prefix + UPSTREAM_STATE, result.path("requestState")));
        }
        if (!McpResultType.COMPLETE.wireValue().equals(type)) {
            throw new McpClientException(describe() + " answered '" + type
                    + "' outside a journaled run; declare a durability mode to wait on it");
        }
        return result;
    }

    /** The sub-run's Interpreter: each round's reply decides the next action. */
    private Interpreter interpreter() {
        return view -> {
            JsonNode reply = outcome(view, NodePath.of("create"));
            if (reply == null) {
                return List.of(new Command.ScheduleAction(NodePath.of("create"),
                        JSON.createObjectNode(), null));
            }
            // The caller's round whose answers the last send used: an upstream that asks again
            // gets only answers of a later round, never one it already had.
            int used = 0;
            for (int round = 1;; round++) {
                String kind = reply.path("kind").asString();
                switch (kind) {
                    case "result" -> {
                        return List.of(new Command.Complete(reply.get("result")));
                    }
                    case "failed" -> {
                        return List.of(new Command.Fail(new Failure("upstream",
                                reply.path("message").asString("The upstream task failed"))));
                    }
                    case "task" -> {
                        NodePath await = NodePath.of("await").visit(round);
                        JsonNode next = outcome(view, await);
                        if (next == null) {
                            return List.of(new Command.ScheduleAction(await, reply, null));
                        }
                        reply = next;
                    }
                    case "ask", "task-ask" -> {
                        boolean task = kind.equals("task-ask");
                        NodePath send = NodePath.of(task ? "update" : "answer").visit(round);
                        JsonNode next = outcome(view, send);
                        if (next == null) {
                            ObjectNode responses = latestRound > used
                                    ? responsesTo(reply.path("inputRequests"))
                                    : JSON.createObjectNode();
                            if (responses.isEmpty()) {
                                return List.of(new Command.AwaitUpdate(
                                        NodePath.of("answers").visit(round),
                                        reply.path("inputRequests")));
                            }
                            ObjectNode payload = JSON.createObjectNode();
                            payload.set("responses", responses);
                            payload.put(ANSWERED_IN, latestRound);
                            if (task) {
                                payload.put("taskId", reply.path("taskId").asString());
                                payload.set("namespace", reply.get("namespace"));
                                if (reply.has("credentials")) {
                                    payload.set("credentials", reply.get("credentials"));
                                }
                                payload.set("pollIntervalMs", reply.get("pollIntervalMs"));
                                payload.set("ttlMs", reply.get("ttlMs"));
                            } else {
                                payload.set("requestState", reply.get("requestState"));
                            }
                            return List.of(new Command.ScheduleAction(send, payload, null));
                        }
                        used = next.path(ANSWERED_IN).asInt(used);
                        reply = next;
                    }
                    default -> {
                        return List.of(new Command.Fail(new Failure("upstream",
                                "Unexpected upstream reply: " + kind)));
                    }
                }
            }
        };
    }

    /**
     * The upstream task a sub-run waits on, walked as {@link #interpreter} walks it: the last
     * reply, when it names a task polled or asking and nothing answered it since; otherwise
     * {@code null}, for a sub-run that ended, that waits on a multi round-trip request, or that
     * is not an upstream call's.
     */
    static JsonNode openTask(ExecutionView view) {
        JsonNode reply = outcome(view, NodePath.of("create"));
        for (int round = 1; reply != null; round++) {
            String kind = reply.path("kind").asString();
            NodePath next;
            switch (kind) {
                case "task" -> next = NodePath.of("await").visit(round);
                case "task-ask" -> next = NodePath.of("update").visit(round);
                case "ask" -> next = NodePath.of("answer").visit(round);
                default -> {
                    return null;
                }
            }
            JsonNode after = outcome(view, next);
            if (after == null) {
                return kind.startsWith("task") && reply.hasNonNull("taskId") ? reply : null;
            }
            reply = after;
        }
        return null;
    }

    private static JsonNode outcome(ExecutionView view, NodePath path) {
        var outcome = view.outcome(path);
        if (outcome.isEmpty()) {
            return null;
        }
        if (outcome.get() instanceof ActionOutcome.Failed failed) {
            ObjectNode node = JSON.createObjectNode();
            node.put("kind", "failed");
            node.put("message", failed.failure().message());
            return node;
        }
        return (JsonNode) ((ActionOutcome.Succeeded) outcome.get()).value();
    }

    /** Sends {@code tools/call}, with the answers to an earlier round when there are any. */
    private JsonNode call(JsonNode requestState, JsonNode responses, boolean journaled) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        if (requestState != null && !requestState.isNull() && !requestState.isMissingNode()) {
            params.set("requestState", requestState);
            params.set("inputResponses", responses != null ? responses : JSON.createObjectNode());
        }
        ObjectNode capabilities = params.putObject("_meta")
                .putObject("io.modelcontextprotocol/clientCapabilities");
        if (mayElicit) {
            capabilities.putObject("elicitation");
        }
        if (journaled) {
            capabilities.putObject("extensions").putObject(TASKS);
        }
        return transport.call("tools/call", toolName, params, templates);
    }

    /** The reply of a {@code tools/call}, as the sub-run journals it. */
    private ObjectNode reply(JsonNode result) {
        String type = resultType(result);
        ObjectNode reply = JSON.createObjectNode();
        if (McpResultType.TASK.wireValue().equals(type)) {
            reply.put("kind", "task");
            reply.put("taskId", result.path("taskId").asString());
            // Names the adapter, so cancelling the caller can cancel this task (UpstreamTasks).
            reply.put("namespace", adapter.getNamespace());
            // And what its credentials take from this invocation, sealed, for that cancel.
            String credentials = adapter.sealCredentials(templates);
            if (credentials != null) {
                reply.put("credentials", credentials);
            }
            reply.set("pollIntervalMs", result.get("pollIntervalMs"));
            reply.set("ttlMs", result.get("ttlMs"));
        } else if (McpResultType.INPUT_REQUIRED.wireValue().equals(type)) {
            requireDeclared();
            reply.put("kind", "ask");
            reply.set("inputRequests", result.path("inputRequests"));
            reply.set("requestState", result.path("requestState"));
        } else {
            reply.put("kind", "result");
            reply.set("result", result);
        }
        return reply;
    }

    /** Polls an upstream task until it ends, asks, or outlives its ttl. */
    private ObjectNode await(ObjectNode task) throws InterruptedException {
        String taskId = task.path("taskId").asString();
        long interval = Math.clamp(task.path("pollIntervalMs").asLong(1000),
                MIN_POLL.toMillis(), MAX_POLL.toMillis());
        long ttl = task.path("ttlMs").asLong(0);
        long deadline = ttl > 0 ? System.currentTimeMillis() + ttl : Long.MAX_VALUE;
        try {
            while (true) {
                ObjectNode params = JSON.createObjectNode();
                params.put("taskId", taskId);
                JsonNode state = transport.call("tasks/get", taskId, params, templates);
                String status = state.path("status").asString();
                switch (status) {
                    case "completed" -> {
                        ObjectNode reply = JSON.createObjectNode();
                        reply.put("kind", "result");
                        reply.set("result", state.path("result"));
                        return reply;
                    }
                    case "failed", "cancelled" -> {
                        ObjectNode reply = JSON.createObjectNode();
                        reply.put("kind", "failed");
                        reply.put("message", describe() + " task " + status
                                + (state.has("error") ? ": " + state.path("error").path("message")
                                        .asString() : ""));
                        return reply;
                    }
                    case "input_required" -> {
                        requireDeclared();
                        ObjectNode reply = task.deepCopy();
                        reply.put("kind", "task-ask");
                        reply.set("inputRequests", state.path("inputRequests"));
                        return reply;
                    }
                    default -> {
                        if (System.currentTimeMillis() > deadline) {
                            ObjectNode reply = JSON.createObjectNode();
                            reply.put("kind", "failed");
                            reply.put("message", describe() + " task expired before it ended");
                            return reply;
                        }
                        if (state.has("pollIntervalMs")) {
                            interval = Math.clamp(state.path("pollIntervalMs").asLong(interval),
                                    MIN_POLL.toMillis(), MAX_POLL.toMillis());
                        }
                        Thread.sleep(interval);
                    }
                }
            }
        } catch (InterruptedException e) {
            // Our own task was cancelled: cancel the upstream one too, best effort.
            try {
                ObjectNode params = JSON.createObjectNode();
                params.put("taskId", taskId);
                transport.call("tasks/cancel", taskId, params, templates);
            } catch (RuntimeException ignored) {
                // The upstream may have ended or be unreachable: nothing more to do.
            }
            throw e;
        }
    }

    private void update(String taskId, JsonNode responses) {
        ObjectNode params = JSON.createObjectNode();
        params.put("taskId", taskId);
        params.set("inputResponses", responses);
        transport.call("tasks/update", taskId, params, templates);
    }

    /**
     * The answers the caller gave to these upstream requests in its latest round, keyed as the
     * upstream asked.
     */
    private ObjectNode responsesTo(JsonNode requests) {
        ObjectNode responses = JSON.createObjectNode();
        for (String key : requests.propertyNames()) {
            JsonNode answer = latest.get(key);
            if (answer != null) {
                responses.set(key, answer);
            }
        }
        return responses;
    }

    /** The upstream's questions, keyed under the calling step's path, passed through unchanged. */
    private InputRequiredException questions(JsonNode requests, Map<String, JsonNode> carry) {
        List<InputRequiredException.Question> questions = new ArrayList<>();
        for (String key : requests.propertyNames()) {
            questions.add(new InputRequiredException.Question(prefix + key, null,
                    requests.get(key)));
        }
        return new InputRequiredException(questions, carry);
    }

    private void requireDeclared() {
        if (!mayElicit) {
            throw new McpClientException(describe() + " asked for input, which it may only do "
                    + "when the consumed tool declares mayElicit: true");
        }
    }

    private String describe() {
        return "Upstream MCP tool " + adapter.getNamespace() + "." + toolName;
    }

    private static String resultType(JsonNode result) {
        return result.path(McpResultType.FIELD).asString(McpResultType.COMPLETE.wireValue());
    }

    /** The answers whose key starts with {@code prefix}, the prefix removed. */
    private static Map<String, JsonNode> answersUnder(String prefix,
            Map<NodePath, Object> answers) {
        Map<String, JsonNode> own = new LinkedHashMap<>();
        answers.forEach((path, value) -> {
            if (path.value().startsWith(prefix)) {
                own.put(path.value().substring(prefix.length()), JSON.valueToTree(value));
            }
        });
        return own;
    }
}
