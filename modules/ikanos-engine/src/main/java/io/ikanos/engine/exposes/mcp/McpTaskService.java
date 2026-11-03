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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.exposes.mcp.model.JsonRpcError;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.ikanos.spec.util.IkanosMappers;

/**
 * MCP tasks (the {@code io.modelcontextprotocol/tasks} extension, SEP-2663) on top of the durable
 * engine.
 *
 * <p>A task is one execution of the {@link JournaledRunner}. Its work, the tool call a
 * synchronous {@code tools/call} would make, runs as a single journaled action on a virtual
 * thread, so the task's result is exactly the {@code CallToolResult} a synchronous call would have
 * returned. The task is answered only once its execution is recorded in the execution index, so a
 * {@code tasks/get} for the returned ID always resolves.</p>
 *
 * <p>The task ID is a random 128-bit value, distinct from the execution ID. Journaling each step
 * of the flow, input requests and {@code tasks/update} arrive with the next increments.</p>
 */
public final class McpTaskService {

    /** Path of the single action a task runs. */
    static final NodePath TOOL_CALL = NodePath.of("tool-call");

    /** Failure class of a call rejected for invalid parameters: a JSON-RPC error, not a tool error. */
    static final String INVALID_PARAMS = "invalid-params";

    /** Suggested polling interval, in milliseconds. */
    static final long POLL_INTERVAL_MS = 1000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private final JournaledRunner runner;
    private final Clock clock;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private static final class Task {
        final ExecutionId executionId;
        final Instant createdAt;
        final AtomicReference<Instant> lastUpdatedAt;
        volatile Future<?> work;

        Task(ExecutionId executionId, Instant createdAt) {
            this.executionId = executionId;
            this.createdAt = createdAt;
            this.lastUpdatedAt = new AtomicReference<>(createdAt);
        }
    }

    public McpTaskService(JournaledRunner runner, Clock clock) {
        this.runner = runner;
        this.clock = clock;
    }

    /**
     * Creates a task that runs {@code work} and returns its {@code CreateTaskResult}.
     *
     * @param entry the exposed entry, recorded with the execution (for example
     *        {@code mcp:shipyard-ops/clear-for-departure})
     * @param work the tool call; an {@link IllegalArgumentException} ends the task {@code failed}
     *        with {@code -32602}, as the synchronous call would answer
     */
    public ObjectNode create(String entry, Callable<JsonNode> work) {
        String taskId = newTaskId();
        Instant now = clock.instant();
        Task task = new Task(ExecutionId.random(), now);
        ExecutionRequest request = ExecutionRequest.of(task.executionId, null);
        runner.create(request, entry);
        tasks.put(taskId, task);
        task.work = workers.submit(() -> {
            try {
                runner.run(request, interpreter(), action -> {
                    try {
                        return work.call();
                    } catch (IllegalArgumentException e) {
                        throw new ActionFailedException(new Failure(INVALID_PARAMS, e.getMessage()), e);
                    }
                });
            } finally {
                task.lastUpdatedAt.set(clock.instant());
            }
        });
        ObjectNode result = describe(taskId, task, "working");
        result.put(McpResultType.FIELD, McpResultType.TASK.wireValue());
        return result;
    }

    /** The current state of a task for {@code tasks/get}, or empty for an unknown ID. */
    public Optional<ObjectNode> get(String taskId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }
        ExecutionStatus status = runner.status(task.executionId).orElse(ExecutionStatus.RUNNING);
        ObjectNode node = switch (status) {
            case RUNNING -> describe(taskId, task, "working");
            case SUSPENDED -> describe(taskId, task, "input_required");
            case CANCELLED -> describe(taskId, task, "cancelled");
            case COMPLETED, FAILED -> terminal(taskId, task);
        };
        node.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
        return Optional.of(node);
    }

    /** Cancels a task; returns {@code false} for an unknown ID. */
    public boolean cancel(String taskId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null) {
            return false;
        }
        runner.cancel(task.executionId);
        Future<?> work = task.work;
        if (work != null) {
            work.cancel(true);
        }
        task.lastUpdatedAt.set(clock.instant());
        return true;
    }

    /** Whether any task is known; used to advertise the extension only when it can be served. */
    int size() {
        return tasks.size();
    }

    private ObjectNode terminal(String taskId, Task task) {
        ExecutionResult result = runner.result(task.executionId).orElse(null);
        if (result instanceof ExecutionResult.Completed completed) {
            ObjectNode node = describe(taskId, task, "completed");
            node.set("result", (JsonNode) completed.result());
            return node;
        }
        ObjectNode node = describe(taskId, task, "failed");
        Failure failure = result instanceof ExecutionResult.Failed failed ? failed.failure()
                : new Failure(Failure.UNEXPECTED, "The task ended without a result");
        ObjectNode error = node.putObject("error");
        error.put("code", INVALID_PARAMS.equals(failure.errorClass())
                ? JsonRpcError.INVALID_PARAMS.getCode() : JsonRpcError.INTERNAL_ERROR.getCode());
        error.put("message", INVALID_PARAMS.equals(failure.errorClass())
                ? "Invalid params: " + failure.message() : "Task execution failed");
        return node;
    }

    private ObjectNode describe(String taskId, Task task, String status) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("taskId", taskId);
        node.put("status", status);
        node.put("createdAt", task.createdAt.toString());
        node.put("lastUpdatedAt", task.lastUpdatedAt.get().toString());
        node.putNull("ttlMs");
        if ("working".equals(status) || "input_required".equals(status)) {
            node.put("pollIntervalMs", POLL_INTERVAL_MS);
        }
        return node;
    }

    private static Interpreter interpreter() {
        return view -> {
            var outcome = view.outcome(TOOL_CALL);
            if (outcome.isEmpty()) {
                return List.of(new Command.ScheduleAction(TOOL_CALL, "tools/call", null));
            }
            if (outcome.get() instanceof ActionOutcome.Failed failed) {
                return List.of(new Command.Fail(failed.failure()));
            }
            return List.of(new Command.Complete(((ActionOutcome.Succeeded) outcome.get()).value()));
        };
    }

    static String newTaskId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
