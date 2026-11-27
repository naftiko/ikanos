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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
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
import io.ikanos.durable.core.ExecutionBusyException;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.ExecutionView;
import io.ikanos.engine.execution.DurableScope;
import io.ikanos.engine.execution.ExecutionInputs;
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
 * <p>On a store several replicas share ({@code distributed}), a task runs on one replica at a
 * time, under its execution's lease, and any replica answers {@code tasks/get},
 * {@code tasks/update} and {@code tasks/cancel} from the store. Every lease period,
 * {@link #takeOver} starts again the running tasks whose lease expired, as when the replica
 * running them stopped, and the waiting tasks whose answers arrived; a task another replica still
 * runs is left to it.</p>
 *
 * <p>A task whose flow asks for input ({@code elicit}) does not fail: its tool-call action ends
 * with the questions as its outcome, and the task's execution waits for an update holding the
 * answers, which {@link #update} delivers. The task then reports {@code input_required} with
 * those questions, from the journal, on any replica and after a restart. Once answered, the next
 * round calls the tool again with every answer so far: the steps journaled in earlier rounds are
 * restored, the answered {@code elicit} steps pass, and the flow goes on.</p>
 *
 * <p>The task ID is a random 128-bit value, distinct from the execution ID.</p>
 */
public final class McpTaskService {

    /** Path of a task's tool call; round {@code n} of a task that asks visits it {@code n} times. */
    static final NodePath TOOL_CALL = InputRounds.CALL;

    /** Path of the update that answers the questions of a round. */
    static final NodePath ANSWERS = InputRounds.ANSWERS;

    /** Failure class of a call rejected for invalid parameters: a JSON-RPC error, not a tool error. */
    static final String INVALID_PARAMS = InputRounds.INVALID_PARAMS;

    /** Failure class of a task started by another version of the capability. */
    static final String SPEC_CHANGED = "spec-changed";

    /** Failure class of a task whose arguments were never recorded. */
    static final String INCOMPLETE = "incomplete-creation";

    /** Label holding the task ID on the task's execution. */
    static final String TASK_ID = "taskId";

    /** Label holding the SHA-256 of the principal that created the task. */
    static final String PRINCIPAL = "principal";

    /** Label marking the hidden execution of an inline call answered in rounds. */
    static final String KIND = InlineRounds.KIND;

    /** Value of {@link #KIND} for an inline call answered by multi round-trip requests. */
    static final String MRTR = InlineRounds.MRTR;

    /** Suggested polling interval, in milliseconds. */
    static final long POLL_INTERVAL_MS = 1000;

    /** Longest wait, in milliseconds, for a round that asked to end before answers resume it. */
    static final long ROUND_END_WAIT_MS = 5000;

    /** Most unfinished tasks resumed at once. */
    static final int RESUME_LIMIT = 10_000;

    /**
     * Most tasks whose last update time is kept in memory; the oldest is forgotten first, and
     * reports its creation time again, as every task does after a restart.
     */
    static final int LAST_UPDATED_LIMIT = 10_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private final JournaledRunner runner;
    private final Clock clock;
    private final Long ttlMs;
    private final Map<ExecutionId, Future<?>> running = new ConcurrentHashMap<>();
    private final Map<ExecutionId, Instant> lastUpdated = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ExecutionId, Instant> eldest) {
                    return size() > LAST_UPDATED_LIMIT;
                }
            });
    private final ExecutorService workers;
    private final AtomicBoolean draining = new AtomicBoolean();
    /** Takes the tasks of stopped replicas over, on a shared store; started by the adapter. */
    private volatile ScheduledExecutorService takeovers;
    private final InlineRounds inline;

    /**
     * @param ttlMs how long a task is kept after creation, from the durability retention, or
     *        {@code null} when unbounded
     */
    private volatile Consumer<ExecutionId> onCancel = id -> {
    };

    public McpTaskService(JournaledRunner runner, Clock clock, Long ttlMs) {
        this(runner, clock, ttlMs, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** With the executor that runs the tasks, for the tests. */
    McpTaskService(JournaledRunner runner, Clock clock, Long ttlMs, ExecutorService workers) {
        this.runner = runner;
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.workers = workers;
        this.inline = new InlineRounds(runner, clock, draining::get);
    }

    /**
     * Runs {@code hook} after a task or an inline call is cancelled, with its execution: to cancel
     * what it waits on outside this process, such as an upstream task.
     *
     * @return this
     */
    public McpTaskService whenCancelled(Consumer<ExecutionId> hook) {
        this.onCancel = hook;
        inline.whenCancelled(hook);
        return this;
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
        if (draining.get()) {
            throw new IllegalStateException("The server is shutting down: no new task starts");
        }
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
        if (draining.get()) {
            return 0;
        }
        if (runner.isShared()) {
            // Other replicas run, or wait on people for, most of the store's tasks: a replica
            // starting takes over only those no replica owns.
            return takeOver(workFor);
        }
        // Tasks only, filtered by the store before the limit: inline calls and sub-runs, however
        // many, never take a task's place.
        List<ExecutionRecord> unfinished = new ArrayList<>(
                runner.listLabelled(ExecutionStatus.RUNNING, TASK_ID, null, null, RESUME_LIMIT));
        // A waiting task resumes too: answers delivered just before the process stopped are in
        // its inbox; without them, it waits again at once, running nothing.
        unfinished.addAll(runner.listLabelled(ExecutionStatus.SUSPENDED, TASK_ID, null, null,
                RESUME_LIMIT));
        return restart(unfinished, workFor);
    }

    /**
     * On a store several replicas share, starts again the tasks no replica runs: the running
     * ones whose lease expired, and the waiting ones whose answers are in the inbox. A task whose
     * lease another replica holds is left to it. Unlike {@link #resume}, a waiting task without
     * answers is not run, so a sweep costs nothing for the tasks that wait on a person.
     *
     * @return the number of tasks started again, including those another replica took in the
     *         meantime
     */
    int takeOver(BiFunction<String, JsonNode, Callable<JsonNode>> workFor) {
        if (draining.get()) {
            return 0;
        }
        // Filtered by the store before the limit: tasks other replicas hold, however many,
        // never take the place of one no replica runs.
        List<ExecutionRecord> unowned = new ArrayList<>(
                runner.listUnowned(ExecutionStatus.RUNNING, TASK_ID, RESUME_LIMIT));
        for (ExecutionRecord waiting : runner.listUnowned(ExecutionStatus.SUSPENDED, TASK_ID,
                RESUME_LIMIT)) {
            if (runner.hasPendingUpdates(waiting.executionId())) {
                unowned.add(waiting);
            }
        }
        return restart(unowned, workFor);
    }

    /**
     * Takes over, every lease period, the tasks of replicas that stopped ({@link #takeOver}),
     * until {@link #drain}. Does nothing on a store of one process, whose tasks only a restart
     * resumes.
     */
    public void takeOverPeriodically(BiFunction<String, JsonNode, Callable<JsonNode>> workFor) {
        if (!runner.isShared() || draining.get()) {
            return;
        }
        synchronized (this) {
            if (takeovers != null) {
                return;
            }
            takeovers = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "ikanos-task-takeover");
                thread.setDaemon(true);
                return thread;
            });
        }
        long period = runner.leaseFor().toMillis();
        takeovers.scheduleWithFixedDelay(() -> {
            try {
                takeOver(workFor);
            } catch (RuntimeException e) {
                // The store is unreachable for now: the next sweep tries again.
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private int restart(List<ExecutionRecord> unfinished,
            BiFunction<String, JsonNode, Callable<JsonNode>> workFor) {
        int resumed = 0;
        for (ExecutionRecord record : unfinished) {
            try {
                if (restart(record, workFor)) {
                    resumed++;
                }
            } catch (ExecutionBusyException e) {
                // Another replica runs it: it is that replica's to finish or to fail.
            }
        }
        return resumed;
    }

    private boolean restart(ExecutionRecord record,
            BiFunction<String, JsonNode, Callable<JsonNode>> workFor) {
        if (!record.labels().containsKey(TASK_ID) || isRunning(record.executionId())
                || record.entry() == null) {
            return false;
        }
        Optional<Object> input = runner.input(record.executionId());
        JsonNode arguments = input.map(JsonNode.class::cast).orElse(null);
        Callable<JsonNode> work = workFor.apply(record.entry(), arguments);
        if (work == null) {
            return false;
        }
        if (input.isEmpty()) {
            // The process stopped between recording the task and its arguments: the task was
            // never returned to a client, and running it without its arguments would be wrong.
            // On a shared store, the replica still recording it holds its lease: fail throws.
            runner.fail(record.executionId(), new Failure(INCOMPLETE,
                    "The task was not fully recorded before the process stopped"));
            return false;
        }
        if (!record.specDigest().equals(runner.specDigest())) {
            runner.fail(record.executionId(), new Failure(SPEC_CHANGED,
                    "The capability changed since this task started"));
            return false;
        }
        submit(record.executionId(), work);
        return true;
    }

    private void submit(ExecutionId executionId, Callable<JsonNode> work) {
        ExecutionRequest request = ExecutionRequest.of(executionId, null);
        // Registered before it can run, and removed by the task itself once it ends (or by done()
        // when it is cancelled before it starts): an entry never outlives its task.
        AtomicReference<Future<?>> self = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            boolean ran = true;
            try {
                runner.run(request, InputRounds.interpreter(),
                        InputRounds.executor(runner, executionId, draining::get, clock, work));
            } catch (ExecutionBusyException e) {
                // Another replica runs it: nothing changed here.
                ran = false;
            } finally {
                if (ran) {
                    lastUpdated.put(executionId, clock.instant());
                }
                // This worker's own entry only: a later submit may already have replaced it.
                running.remove(executionId, self.get());
                cleanUpIfCancelled(executionId);
            }
            return null;
        }) {
            @Override
            protected void done() {
                running.remove(executionId, this);
            }
        };
        self.set(task);
        running.put(executionId, task);
        try {
            workers.execute(task);
        } catch (RuntimeException e) {
            running.remove(executionId, task);
            throw e;
        }
    }

    /** The number of tasks tracked as running, for the tests. */
    int runningCount() {
        return running.size();
    }

    /** The number of tasks whose last update time is kept, for the tests. */
    int lastUpdatedCount() {
        return lastUpdated.size();
    }

    /**
     * Whether a worker runs, or is about to run, a task. A future cancelled before it ran never
     * removes its own entry, so a finished future counts as no worker.
     */
    private boolean isRunning(ExecutionId executionId) {
        Future<?> future = running.get(executionId);
        return future != null && !future.isDone();
    }

    /**
     * Once a cancelled task's worker has stopped, cancels what it still waits on
     * ({@link #whenCancelled}): it may stop between two actions of a consumed call, such as
     * after an upstream task's creation is journaled and before its first poll, so no polling
     * code is left to send {@code tasks/cancel}.
     */
    private void cleanUpIfCancelled(ExecutionId executionId) {
        if (runner.status(executionId).orElse(null) == ExecutionStatus.CANCELLED) {
            try {
                onCancel.accept(executionId);
            } catch (RuntimeException e) {
                // Best effort, as for a cancel with no worker.
            }
        }
    }

    /**
     * Stops the running tasks at their next action, for a graceful shutdown: no new task starts,
     * each running one finishes the action in flight, journals its outcome and stops, unfinished,
     * for the next process to resume. Waits at most {@code timeout}.
     *
     * @return the number of tasks still running when the timeout passed, cut short where they were
     */
    public int drain(Duration timeout) {
        draining.set(true);
        ScheduledExecutorService sweeping = takeovers;
        if (sweeping != null) {
            sweeping.shutdownNow();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Future<?> task : List.copyOf(running.values())) {
            long left = deadline - System.nanoTime();
            try {
                task.get(Math.max(left, 0), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | CancellationException e) {
                // Stopped, failed or cancelled: either way, no longer running.
            }
        }
        workers.shutdown();
        return (int) running.values().stream().filter(task -> !task.isDone()).count();
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
            // Answered and resubmitted, but its worker has not picked it up yet: working.
            case SUSPENDED -> isRunning(record.executionId())
                    ? describe(taskId, record, "working") : waiting(taskId, record);
            case CANCELLED -> describe(taskId, record, "cancelled");
            case COMPLETED, FAILED -> terminal(taskId, record);
        };
        node.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
        return Optional.of(node);
    }

    /**
     * Answers a task's questions, for {@code tasks/update}: the responses to the questions the
     * task waits on are delivered once, and the task resumes with them; responses to other keys,
     * or to a task that is not waiting, are ignored. A response delivered before, by a retried
     * update, is not delivered again.
     *
     * @param responses the {@code inputResponses}, keyed as {@code tasks/get} shows the
     *        questions: a key asked again in a later round is qualified with that round
     * @param workFor the tool call for a task's entry and arguments, as {@link #resume} takes
     * @return {@code false} for an unknown ID or another principal's task
     */
    public boolean update(String taskId, String principal, JsonNode responses,
            BiFunction<String, JsonNode, Callable<JsonNode>> workFor) {
        ExecutionRecord record = find(taskId, principal);
        if (record == null) {
            return false;
        }
        if (responses == null || !responses.isObject()) {
            return true;
        }
        if (record.status() == ExecutionStatus.RUNNING && stillAsks(record, responses)) {
            // A waiting task runs for a moment when it is resumed after a restart: it reads the
            // answers delivered before the stop, finds none, and waits again (resume). Answers
            // sent in that moment would be ignored as sent to a running task: let it wait first.
            awaitWorker(record.executionId());
            record = find(taskId, principal);
            if (record == null) {
                return false;
            }
        }
        boolean waiting = record.status() == ExecutionStatus.SUSPENDED;
        // On a shared store, another replica may be running the round that asks: the answers go
        // to the inbox, which that replica reads once it releases the task's lease.
        boolean askedElsewhere = !waiting && runner.isShared()
                && record.status() == ExecutionStatus.RUNNING && stillAsks(record, responses);
        if (!waiting && !askedElsewhere) {
            return true;
        }
        if (!record.specDigest().equals(runner.specDigest())) {
            // Recorded by another version of the capability, whose steps may not exist here: it
            // fails as resume and takeOver fail it, rather than run against this version.
            try {
                runner.fail(record.executionId(), new Failure(SPEC_CHANGED,
                        "The capability changed since this task started"));
            } catch (ExecutionBusyException e) {
                // A replica of its own version runs it.
            }
            return true;
        }
        ExecutionView view = runner.view(record.executionId());
        int round = InputRounds.lastRound(view);
        // Keyed for this round: a retried update of an earlier one never answers a key asked
        // again (InputRounds.taskQuestions).
        ObjectNode answers = InputRounds.taskAnswers(view, round, responses);
        if (answers.isEmpty()) {
            return true;
        }
        // Into the inbox first: a crash after it leaves the answers for the next run to read.
        runner.deliver(record.executionId(), ANSWERS.visit(round), answers);
        // The round that asked may still be finishing on its worker: let it end, so the answers
        // are not left in the inbox with nothing running to read them.
        awaitWorker(record.executionId());
        if (!isRunning(record.executionId()) && !draining.get()) {
            Callable<JsonNode> work = workFor.apply(record.entry(),
                    runner.input(record.executionId()).map(JsonNode.class::cast).orElse(null));
            if (work != null) {
                submit(record.executionId(), work);
            }
        }
        lastUpdated.put(record.executionId(), clock.instant());
        return true;
    }

    /**
     * Whether a running task's last round asked questions that are still unanswered and that
     * these responses answer: the task is about to wait for them, not past them.
     */
    private boolean stillAsks(ExecutionRecord record, JsonNode responses) {
        ExecutionView view = runner.view(record.executionId());
        int round = InputRounds.lastRound(view);
        return InputRounds.questions(view, round) != null
                && view.update(ANSWERS.visit(round)).isEmpty()
                && !InputRounds.taskAnswers(view, round, responses).isEmpty();
    }

    /** Waits, at most {@link #ROUND_END_WAIT_MS}, for the worker running a task to end. */
    private void awaitWorker(ExecutionId executionId) {
        Future<?> ending = running.get(executionId);
        if (ending != null) {
            try {
                ending.get(ROUND_END_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException | TimeoutException e) {
                // Ended one way or another, or still running: either way, go on.
            }
        }
    }

    /**
     * Runs one round of an inline call answered by multi round-trip requests, journal-backed
     * ({@link InlineRounds#run}).
     */
    public InlineRounds.Round inRounds(String entry, String principal, JsonNode arguments,
            ExecutionId executionId, int round, JsonNode responses, Callable<JsonNode> work) {
        return inline.run(entry, principal, arguments, executionId, round, responses, work);
    }

    /** Fails the inline calls left unanswered past their state's expiry. */
    void sweepUnanswered() {
        inline.sweepUnanswered();
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
            // Interrupted, a call polling an upstream task cancels that task itself; whatever
            // the worker leaves waiting is cancelled once it stops (cleanUpIfCancelled).
            work.cancel(true);
        } else {
            // No worker: the task waits for input, and an upstream task it waits on is named
            // only by its journal.
            onCancel.accept(record.executionId());
        }
        lastUpdated.put(record.executionId(), clock.instant());
        return true;
    }

    /**
     * The entry of a task of this principal ({@code mcp:<namespace>/<tool>}), so its tool's
     * authorization can be checked; empty for an unknown ID and for another principal's task.
     */
    public Optional<String> entryOf(String taskId, String principal) {
        ExecutionRecord record = find(taskId, principal);
        return record == null ? Optional.empty() : Optional.ofNullable(record.entry());
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

    /** A task waiting on input: {@code input_required}, with the questions of its last round. */
    private ObjectNode waiting(String taskId, ExecutionRecord record) {
        ObjectNode node = describe(taskId, record, "input_required");
        ExecutionView view = runner.view(record.executionId());
        ObjectNode questions = InputRounds.taskQuestions(view, InputRounds.lastRound(view));
        if (questions != null) {
            node.set("inputRequests", questions);
        }
        return node;
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
                : SPEC_CHANGED.equals(failure.errorClass()) ? failure.message()
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
