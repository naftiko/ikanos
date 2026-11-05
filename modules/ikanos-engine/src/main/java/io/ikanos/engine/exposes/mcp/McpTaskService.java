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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
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
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.execution.DurableScope;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.mcp.model.JsonRpcError;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.ikanos.spec.util.IkanosMappers;

/**
 * MCP tasks (the {@code io.modelcontextprotocol/tasks} extension, SEP-2663) on top of the durable
 * engine.
 *
 * <p>A task is one execution of the {@link JournaledRunner}, recorded before the task is
 * returned: its index entry names the tool ({@code mcp:<namespace>/<tool>}) and carries the task
 * ID and a digest of the caller as labels, and its first journal entry holds the tool's
 * arguments. Its work, the tool call a synchronous {@code tools/call} would make, runs as a
 * journaled action on a virtual thread, inside which each step of the flow is journaled too
 * ({@link DurableScope}). So the task's result is exactly the {@code CallToolResult} a
 * synchronous call would have returned, and {@code tasks/get} and {@code tasks/cancel} answer
 * from the store, including for a task another process created.</p>
 *
 * <p>{@link #resume} starts the unfinished tasks of a store again, for instance after a restart in
 * the {@code local} mode: steps already journaled are restored, not run again. A task started by
 * another version of the capability fails instead, since its steps may no longer exist.</p>
 *
 * <p>The task ID is a random 128-bit value, distinct from the execution ID. Input requests and
 * {@code tasks/update} arrive with a later increment.</p>
 */
public final class McpTaskService {

    /** Path of the single action a task runs. */
    static final NodePath TOOL_CALL = NodePath.of("tool-call");

    /** Failure class of a call rejected for invalid parameters: a JSON-RPC error, not a tool error. */
    static final String INVALID_PARAMS = "invalid-params";

    /** Failure class of a task whose flow asked for input: tasks/update is not served yet. */
    static final String INPUT_REQUIRED = "input-required";

    /** Failure class of a task started by another version of the capability. */
    static final String SPEC_CHANGED = "spec-changed";

    /** Label holding the task ID on the task's execution. */
    static final String TASK_ID = "taskId";

    /** Label holding the SHA-256 of the principal that created the task. */
    static final String PRINCIPAL = "principal";

    /** Suggested polling interval, in milliseconds. */
    static final long POLL_INTERVAL_MS = 1000;

    /** Most unfinished tasks resumed at once. */
    static final int RESUME_LIMIT = 10_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private final JournaledRunner runner;
    private final Clock clock;
    private final Long ttlMs;
    private final Map<ExecutionId, Future<?>> running = new ConcurrentHashMap<>();
    private final Map<ExecutionId, Instant> lastUpdated = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param ttlMs how long a task is kept after creation, from the durability retention, or
     *        {@code null} when unbounded
     */
    public McpTaskService(JournaledRunner runner, Clock clock, Long ttlMs) {
        this.runner = runner;
        this.clock = clock;
        this.ttlMs = ttlMs;
    }

    /**
     * Creates a task that runs {@code work} and returns its {@code CreateTaskResult}.
     *
     * @param entry the exposed entry, recorded with the execution (for example
     *        {@code mcp:shipyard-ops/clear-for-departure})
     * @param principal the caller; every later {@code tasks/*} request must come from it
     * @param arguments the tool's arguments, recorded so the task can be started again
     * @param work the tool call; an {@link IllegalArgumentException} ends the task {@code failed}
     *        with {@code -32602}, as the synchronous call would answer
     */
    public ObjectNode create(String entry, String principal, JsonNode arguments,
            Callable<JsonNode> work) {
        String taskId = newTaskId();
        ExecutionId executionId = ExecutionId.random();
        runner.start(ExecutionRequest.of(executionId, arguments), entry,
                Map.of(TASK_ID, taskId, PRINCIPAL, digest(principal)));
        submit(executionId, work);
        ExecutionRecord record = runner.record(executionId).orElseThrow();
        ObjectNode result = describe(taskId, record, "working");
        result.put(McpResultType.FIELD, McpResultType.TASK.wireValue());
        return result;
    }

    /**
     * Starts again every unfinished task of the store whose entry {@code workFor} serves: the
     * work it returns for the entry and recorded arguments, or {@code null} for an entry this
     * service does not serve. A task started by another version of the capability fails with
     * {@code spec-changed} instead.
     *
     * @return the number of tasks started again
     */
    public int resume(BiFunction<String, JsonNode, Callable<JsonNode>> workFor) {
        int resumed = 0;
        List<ExecutionRecord> unfinished = new ArrayList<>(
                runner.list(ExecutionStatus.RUNNING, RESUME_LIMIT));
        for (ExecutionRecord record : unfinished) {
            if (!record.labels().containsKey(TASK_ID) || running.containsKey(record.executionId())
                    || record.entry() == null) {
                continue;
            }
            JsonNode arguments = runner.input(record.executionId())
                    .map(JsonNode.class::cast).orElse(null);
            Callable<JsonNode> work = workFor.apply(record.entry(), arguments);
            if (work == null) {
                continue;
            }
            if (!record.specDigest().equals(runner.specDigest())) {
                runner.fail(record.executionId(), new Failure(SPEC_CHANGED,
                        "The capability changed since this task started"));
                continue;
            }
            submit(record.executionId(), work);
            resumed++;
        }
        return resumed;
    }

    private void submit(ExecutionId executionId, Callable<JsonNode> work) {
        ExecutionRequest request = ExecutionRequest.of(executionId, null);
        Future<?> future = workers.submit(() -> {
            try {
                runner.run(request, interpreter(), action -> {
                    try {
                        // The flow's steps are journaled one by one under this task's execution,
                        // so a step that already ran is restored rather than run again.
                        return DurableScope.with(runner, executionId, work);
                    } catch (IllegalArgumentException e) {
                        throw new ActionFailedException(new Failure(INVALID_PARAMS, e.getMessage()), e);
                    } catch (InputRequiredException e) {
                        // Input requests on a task (tasks/update) arrive with the next increment.
                        throw new ActionFailedException(new Failure(INPUT_REQUIRED,
                                "This task asks for input, which tasks do not support yet"), e);
                    }
                });
            } finally {
                lastUpdated.put(executionId, clock.instant());
                running.remove(executionId);
            }
        });
        if (!future.isDone()) {
            // Kept for tasks/cancel; a task that already ended has nothing left to interrupt.
            running.put(executionId, future);
        }
    }

    /**
     * The current state of a task for {@code tasks/get}. Empty for an unknown ID and for a task
     * created by another principal, so that existence never leaks.
     */
    public Optional<ObjectNode> get(String taskId, String principal) {
        ExecutionRecord record = find(taskId, principal);
        if (record == null) {
            return Optional.empty();
        }
        ObjectNode node = switch (record.status()) {
            case RUNNING -> describe(taskId, record, "working");
            case SUSPENDED -> describe(taskId, record, "input_required");
            case CANCELLED -> describe(taskId, record, "cancelled");
            case COMPLETED, FAILED -> terminal(taskId, record);
        };
        node.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
        return Optional.of(node);
    }

    /** Cancels a task; returns {@code false} for an unknown ID or another principal's task. */
    public boolean cancel(String taskId, String principal) {
        ExecutionRecord record = find(taskId, principal);
        if (record == null) {
            return false;
        }
        runner.cancel(record.executionId());
        Future<?> work = running.get(record.executionId());
        if (work != null) {
            work.cancel(true);
        }
        lastUpdated.put(record.executionId(), clock.instant());
        return true;
    }

    private ExecutionRecord find(String taskId, String principal) {
        if (taskId == null) {
            return null;
        }
        String caller = digest(principal);
        return runner.findByLabel(TASK_ID, taskId).stream()
                .filter(record -> caller.equals(record.labels().get(PRINCIPAL)))
                .findFirst().orElse(null);
    }

    private ObjectNode terminal(String taskId, ExecutionRecord record) {
        ExecutionResult result = runner.result(record.executionId()).orElse(null);
        if (result instanceof ExecutionResult.Completed completed) {
            ObjectNode node = describe(taskId, record, "completed");
            node.set("result", (JsonNode) completed.result());
            return node;
        }
        ObjectNode node = describe(taskId, record, "failed");
        Failure failure = result instanceof ExecutionResult.Failed failed ? failed.failure()
                : new Failure(Failure.UNEXPECTED, "The task ended without a result");
        ObjectNode error = node.putObject("error");
        error.put("code", INVALID_PARAMS.equals(failure.errorClass())
                ? JsonRpcError.INVALID_PARAMS.getCode() : JsonRpcError.INTERNAL_ERROR.getCode());
        error.put("message", INVALID_PARAMS.equals(failure.errorClass())
                ? "Invalid params: " + failure.message()
                : INPUT_REQUIRED.equals(failure.errorClass())
                        || SPEC_CHANGED.equals(failure.errorClass()) ? failure.message()
                                : "Task execution failed");
        return node;
    }

    private ObjectNode describe(String taskId, ExecutionRecord record, String status) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("taskId", taskId);
        node.put("status", status);
        node.put("createdAt", record.createdAt().toString());
        node.put("lastUpdatedAt",
                lastUpdated.getOrDefault(record.executionId(), record.createdAt()).toString());
        if (ttlMs != null) {
            node.put("ttlMs", ttlMs);
        } else {
            node.putNull("ttlMs");
        }
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

    /** The SHA-256 of a principal, so the store never holds the caller's identity in clear. */
    static String digest(String principal) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(principal).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String newTaskId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
