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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.Failure;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.execution.InputRequiredException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inline calls answered in rounds, journal-backed: an MCP {@code tools/call} or
 * {@code resources/read}, or a REST operation, whose steps may ask its caller for input, in a
 * durable mode. The first round starts a hidden execution, labelled {@code kind: mrtr}; the
 * caller's sealed state names it, and each later round receives the responses to the questions
 * it waits on and resumes, so steps journaled in earlier rounds are restored, not run again.
 */
public final class InlineRounds {

    /** Label naming what an execution is. */
    public static final String KIND = "kind";

    /** Value of {@link #KIND} for an inline call answered in rounds. */
    public static final String MRTR = "mrtr";

    /** Label holding the SHA-256 of the principal the call is bound to. */
    public static final String PRINCIPAL = "principal";

    /** Failure class of a call whose questions went unanswered past its state's expiry. */
    public static final String EXPIRED = "expired";

    /** Least time between two sweeps of unanswered calls. */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    /** Most unanswered calls examined by one sweep. */
    static final int SWEEP_LIMIT = 10_000;

    private final JournaledRunner runner;
    private final Clock clock;
    private final BooleanSupplier stopping;
    private volatile Instant lastSweep = Instant.EPOCH;
    private volatile Consumer<ExecutionId> onCancel = id -> {
    };

    /**
     * @param stopping whether the process is shutting down: a round then stops before its next
     *        action
     */
    public InlineRounds(JournaledRunner runner, Clock clock, BooleanSupplier stopping) {
        this.runner = runner;
        this.clock = clock;
        this.stopping = stopping;
    }

    /**
     * Runs {@code hook} after a call is cancelled, with its execution: to cancel what it waits on
     * outside this process, such as an upstream task.
     *
     * @return this
     */
    public InlineRounds whenCancelled(Consumer<ExecutionId> hook) {
        this.onCancel = hook;
        return this;
    }

    /**
     * The outcome of one round of an inline call: its result, or the questions it stopped at, and
     * the execution that keeps its progress.
     */
    public record Round(ObjectNode result, ObjectNode questions, ExecutionId executionId) {
    }

    /**
     * Runs one round. The first round starts a hidden execution; a later one, named by the
     * caller's sealed state, receives the responses to the questions it waits on and resumes. A
     * round after the end returns the recorded result.
     *
     * @param executionId the execution the state names, or {@code null} for the first round
     * @param arguments the call's arguments, recorded by the first round
     * @throws IllegalArgumentException for invalid parameters, or a state naming an execution
     *         this store does not hold for this principal
     */
    public Round run(String entry, String principal, JsonNode arguments,
            ExecutionId executionId, JsonNode responses, Callable<JsonNode> work) {
        sweepUnanswered();
        ExecutionId id = executionId;
        if (id == null) {
            id = ExecutionId.random();
            runner.start(ExecutionRequest.of(id, arguments), entry,
                    Map.of(KIND, MRTR, PRINCIPAL, McpTaskService.digest(principal)));
        } else {
            ExecutionRecord record = find(id, principal).orElse(null);
            if (record == null) {
                // Lost with a memory store's process, or removed by retention: start again.
                throw new IllegalArgumentException("The state names an unknown call");
            }
            if (record.status() == ExecutionStatus.SUSPENDED) {
                ExecutionView view = runner.view(id);
                int round = InputRounds.lastRound(view);
                ObjectNode answers =
                        InputRounds.answersTo(InputRounds.questions(view, round), responses);
                if (!answers.isEmpty()) {
                    runner.deliver(id, InputRounds.ANSWERS.visit(round), answers);
                }
            }
        }
        // A failure raised in this process reaches the caller as thrown, so the adapter maps it
        // as it maps a synchronous call's; the journal keeps it as the call's failure.
        AtomicReference<RuntimeException> raised = new AtomicReference<>();
        Callable<JsonNode> watched = () -> {
            try {
                return work.call();
            } catch (IllegalArgumentException | InputRequiredException e) {
                throw e;
            } catch (RuntimeException e) {
                raised.set(e);
                throw e;
            }
        };
        ExecutionResult result = runner.run(ExecutionRequest.of(id, null),
                InputRounds.interpreter(),
                InputRounds.executor(runner, id, stopping, clock, watched));
        if (result instanceof ExecutionResult.Completed completed) {
            return new Round((ObjectNode) completed.result(), null, id);
        }
        if (result instanceof ExecutionResult.Suspended suspended) {
            return new Round(null, (ObjectNode) suspended.awaiting().get(0).request(), id);
        }
        if (raised.get() != null) {
            throw raised.get();
        }
        Failure failure = ((ExecutionResult.Failed) result).failure();
        if (InputRounds.INVALID_PARAMS.equals(failure.errorClass())) {
            throw new IllegalArgumentException(failure.message());
        }
        throw new IllegalStateException(failure.message());
    }

    /** The hidden execution of an inline call, if this store holds it for this principal. */
    public Optional<ExecutionRecord> find(ExecutionId executionId, String principal) {
        return runner.record(executionId)
                .filter(record -> MRTR.equals(record.labels().get(KIND)))
                .filter(record -> McpTaskService.digest(principal)
                        .equals(record.labels().get(PRINCIPAL)));
    }

    /** The questions the call waits on, or {@code null} when it does not wait. */
    public ObjectNode waitingOn(ExecutionId executionId) {
        ExecutionView view = runner.view(executionId);
        return InputRounds.questions(view, InputRounds.lastRound(view));
    }

    /** The recorded result of a completed call, if any. */
    public Optional<ObjectNode> result(ExecutionId executionId) {
        return runner.result(executionId)
                .filter(ExecutionResult.Completed.class::isInstance)
                .map(result -> (ObjectNode) ((ExecutionResult.Completed) result).result());
    }

    /** Cancels a call, unless it already ended, and what it waits on ({@link #whenCancelled}). */
    public void cancel(ExecutionId executionId) {
        runner.cancel(executionId);
        onCancel.accept(executionId);
    }

    /** The arguments the first round recorded. */
    public Optional<JsonNode> arguments(ExecutionId executionId) {
        return runner.input(executionId).map(JsonNode.class::cast);
    }

    /**
     * Fails the calls still waiting for answers past the expiry of their sealed state, at most
     * once per {@link #SWEEP_INTERVAL}: no retry can resume them any more. Each round issues a
     * new state, so a call expires from when its last round asked, not from when it started.
     */
    void sweepUnanswered() {
        Instant now = clock.instant();
        if (now.isBefore(lastSweep.plus(SWEEP_INTERVAL))) {
            return;
        }
        lastSweep = now;
        Instant cutoff = now.minus(McpRequestState.TTL);
        for (ExecutionRecord record : runner.list(ExecutionStatus.SUSPENDED, SWEEP_LIMIT)) {
            // A round asks after the call starts: a call started after the cutoff cannot be
            // expired, and the journal is read only for the others.
            if (MRTR.equals(record.labels().get(KIND)) && record.createdAt().isBefore(cutoff)
                    && InputRounds.askedAt(runner.view(record.executionId()))
                            .orElse(record.createdAt()).isBefore(cutoff)) {
                runner.fail(record.executionId(), new Failure(EXPIRED,
                        "The call's questions went unanswered past its state's expiry"));
            }
        }
    }
}
