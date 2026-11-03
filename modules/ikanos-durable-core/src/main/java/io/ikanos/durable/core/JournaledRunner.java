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
package io.ikanos.durable.core;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.Runner;
import io.ikanos.durable.store.ConcurrentAppendException;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.PayloadCodec;
import io.ikanos.durable.store.Stores;

/**
 * Runner of the durable modes: every outcome, decision and update is appended to the journal
 * before the run goes on, so a later run of the same execution resumes where this one stopped.
 *
 * <ul>
 *   <li>A run first replays the journal: actions with a recorded outcome are not run again, and
 *       recorded decisions are read back.</li>
 *   <li>Updates delivered to the {@code SignalInbox}, or passed in the request, are journaled
 *       once, then acknowledged.</li>
 *   <li>An {@link Command.AwaitUpdate} without an update suspends the run and marks the execution
 *       {@code SUSPENDED}; delivering the update and running again resumes it.</li>
 *   <li>A completed or failed execution returns its recorded result without running anything.</li>
 *   <li>Runs of one execution are serialised in this process. Leases and fencing across
 *       processes come with the shared stores.</li>
 * </ul>
 *
 * <p>Durable timers and retries across attempts come with the next increment; a timer is
 * rejected for now.</p>
 */
public final class JournaledRunner implements Runner {

    /** Fencing token used while a single process owns every execution. */
    static final long SINGLE_OWNER = 0L;

    private final Stores stores;
    private final PayloadCodec codec;
    private final Clock clock;
    private final String specDigest;
    private final Map<ExecutionId, ReentrantLock> locks = new ConcurrentHashMap<>();

    public JournaledRunner(Stores stores, PayloadCodec codec, Clock clock, String specDigest) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.specDigest = Objects.requireNonNull(specDigest, "specDigest");
    }

    /**
     * Creates the execution in the index, without running it. Returns {@code false} when it
     * already exists. A durable MCP task is answered only once this returns.
     */
    public boolean create(ExecutionRequest request, String entry) {
        return stores.index().create(new ExecutionRecord(request.executionId(), specDigest,
                entry, ExecutionStatus.RUNNING, clock.instant(), request.deadline(), Map.of()));
    }

    /** The recorded status of an execution. */
    public Optional<ExecutionStatus> status(ExecutionId executionId) {
        return stores.index().find(executionId).map(ExecutionRecord::status);
    }

    /** The recorded result of a completed or failed execution. */
    public Optional<ExecutionResult> result(ExecutionId executionId) {
        List<JournalEntry> entries = stores.journal().read(executionId);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        JournalEntry last = entries.get(entries.size() - 1);
        return switch (last.kind()) {
            case EXECUTION_COMPLETED -> Optional
                    .of(new ExecutionResult.Completed(decodeValue(last.payload())));
            case EXECUTION_FAILED -> Optional
                    .of(new ExecutionResult.Failed(decodeFailure(last.payload())));
            default -> Optional.empty();
        };
    }

    /** Delivers an update for {@code path}; returns {@code false} if one was already delivered. */
    public boolean deliver(ExecutionId executionId, NodePath path, Object update) {
        return stores.inbox().deliver(executionId, path, encodeValue(update));
    }

    /** Marks an execution cancelled, unless it already ended. */
    public void cancel(ExecutionId executionId) {
        ReentrantLock lock = lockOf(executionId);
        lock.lock();
        try {
            Optional<ExecutionStatus> status = status(executionId);
            if (status.isPresent() && !status.get().isTerminal()) {
                stores.index().updateStatus(executionId, ExecutionStatus.CANCELLED);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ExecutionResult run(ExecutionRequest request, Interpreter interpreter,
            ActionExecutor executor) {
        Objects.requireNonNull(interpreter, "interpreter");
        Objects.requireNonNull(executor, "executor");
        ExecutionId id = request.executionId();
        ReentrantLock lock = lockOf(id);
        lock.lock();
        try {
            create(request, null);
            ExecutionStatus status = status(id).orElse(ExecutionStatus.RUNNING);
            if (status == ExecutionStatus.CANCELLED) {
                return new ExecutionResult.Failed(
                        new Failure("cancelled", "The execution was cancelled"));
            }
            Optional<ExecutionResult> recorded = result(id);
            if (recorded.isPresent()) {
                return recorded.get();
            }
            Run run = new Run(request);
            run.receiveUpdates();
            stores.index().updateStatus(id, ExecutionStatus.RUNNING);
            return run.loop(interpreter, executor);
        } catch (ConcurrentAppendException e) {
            throw new IllegalStateException(
                    "Another owner appended to the journal of " + id, e);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockOf(ExecutionId executionId) {
        return locks.computeIfAbsent(executionId, id -> new ReentrantLock());
    }

    /** One run of one execution: its view rebuilt from the journal, and its appends. */
    private final class Run implements ExecutionView {

        private final ExecutionRequest request;
        private final Map<NodePath, ActionOutcome> outcomes = new HashMap<>();
        private final Map<NodePath, Object> decisions = new HashMap<>();
        private final Map<NodePath, Object> updates = new HashMap<>();
        private long sequence;

        Run(ExecutionRequest request) {
            this.request = request;
            for (JournalEntry entry : stores.journal().read(request.executionId())) {
                sequence = entry.sequence();
                switch (entry.kind()) {
                    case ACTION_SUCCEEDED -> outcomes.put(entry.path(),
                            new ActionOutcome.Succeeded(decodeValue(entry.payload())));
                    case ACTION_FAILED -> outcomes.put(entry.path(),
                            new ActionOutcome.Failed(decodeFailure(entry.payload())));
                    case DECISION_RECORDED -> decisions.put(entry.path(),
                            decodeValue(entry.payload()));
                    case UPDATE_RECEIVED -> updates.put(entry.path(),
                            decodeValue(entry.payload()));
                    default -> {
                        // Scheduling and timer entries carry nothing the view needs yet.
                    }
                }
            }
        }

        void receiveUpdates() throws ConcurrentAppendException {
            ExecutionId id = request.executionId();
            Map<NodePath, byte[]> incoming = new HashMap<>(stores.inbox().pending(id));
            request.updates().forEach((path, value) -> incoming.putIfAbsent(path,
                    encodeValue(value)));
            for (Map.Entry<NodePath, byte[]> update : incoming.entrySet()) {
                if (!updates.containsKey(update.getKey())) {
                    append(JournalEntry.Kind.UPDATE_RECEIVED, update.getKey(), update.getValue());
                    updates.put(update.getKey(), decodeValue(update.getValue()));
                }
                stores.inbox().acknowledge(id, update.getKey());
            }
        }

        ExecutionResult loop(Interpreter interpreter, ActionExecutor executor)
                throws ConcurrentAppendException {
            ExecutionId id = request.executionId();
            while (true) {
                if (request.deadline() != null && clock.instant().isAfter(request.deadline())) {
                    return fail(new Failure(Failure.DEADLINE_EXCEEDED,
                            "The execution ran past its deadline " + request.deadline()));
                }
                List<Command> commands = interpreter.next(this);
                DirectRunner.checkBatch(commands);

                List<Command.ScheduleAction> actions = new ArrayList<>();
                List<Command.AwaitUpdate> awaiting = new ArrayList<>();
                for (Command command : commands) {
                    switch (command) {
                        case Command.Complete complete -> {
                            append(JournalEntry.Kind.EXECUTION_COMPLETED, null,
                                    encodeValue(complete.result()));
                            stores.index().updateStatus(id, ExecutionStatus.COMPLETED);
                            return new ExecutionResult.Completed(complete.result());
                        }
                        case Command.Fail fail -> {
                            return fail(fail.failure());
                        }
                        case Command.RecordDecision decision -> {
                            if (decisions.containsKey(decision.path())) {
                                throw new IllegalStateException(
                                        "A decision is already recorded at " + decision.path());
                            }
                            append(JournalEntry.Kind.DECISION_RECORDED, decision.path(),
                                    encodeValue(decision.value()));
                            decisions.put(decision.path(), decision.value());
                        }
                        case Command.ScheduleAction action -> {
                            if (outcomes.containsKey(action.path())) {
                                throw new IllegalStateException("The action at " + action.path()
                                        + " already has an outcome");
                            }
                            actions.add(action);
                        }
                        case Command.AwaitUpdate await -> {
                            if (updates.containsKey(await.path())) {
                                throw new IllegalStateException("The update for " + await.path()
                                        + " is already known to the Interpreter");
                            }
                            awaiting.add(await);
                        }
                        case Command.StartTimer timer -> throw new IllegalStateException(
                                "Durable timers are not available yet: " + timer.path());
                    }
                }

                runActions(actions, executor);

                if (!awaiting.isEmpty() && actions.isEmpty()) {
                    stores.index().updateStatus(id, ExecutionStatus.SUSPENDED);
                    return new ExecutionResult.Suspended(awaiting);
                }
            }
        }

        void runActions(List<Command.ScheduleAction> actions, ActionExecutor executor)
                throws ConcurrentAppendException {
            DirectExecutionView scratch = new DirectExecutionView(request, clock);
            DirectRunner.runActions(request, actions, scratch, executor);
            // Outcomes are journaled in command order, whatever order the actions finished in.
            for (Command.ScheduleAction action : actions) {
                ActionOutcome outcome = scratch.outcome(action.path()).orElseThrow();
                if (outcome instanceof ActionOutcome.Failed failed) {
                    append(JournalEntry.Kind.ACTION_FAILED, action.path(),
                            encodeFailure(failed.failure()));
                } else {
                    append(JournalEntry.Kind.ACTION_SUCCEEDED, action.path(),
                            encodeValue(((ActionOutcome.Succeeded) outcome).value()));
                }
                outcomes.put(action.path(), outcome);
            }
        }

        ExecutionResult fail(Failure failure) throws ConcurrentAppendException {
            append(JournalEntry.Kind.EXECUTION_FAILED, null, encodeFailure(failure));
            stores.index().updateStatus(request.executionId(), ExecutionStatus.FAILED);
            return new ExecutionResult.Failed(failure);
        }

        void append(JournalEntry.Kind kind, NodePath path, byte[] payload)
                throws ConcurrentAppendException {
            JournalEntry entry = new JournalEntry(sequence + 1, kind, path, payload);
            stores.journal().append(request.executionId(), sequence, SINGLE_OWNER,
                    List.of(entry));
            sequence++;
        }

        @Override
        public ExecutionId executionId() {
            return request.executionId();
        }

        @Override
        public Object input() {
            return request.input();
        }

        @Override
        public Optional<ActionOutcome> outcome(NodePath path) {
            return Optional.ofNullable(outcomes.get(path));
        }

        @Override
        public Optional<Object> decision(NodePath path) {
            return Optional.ofNullable(decisions.get(path));
        }

        @Override
        public Optional<Object> update(NodePath path) {
            return Optional.ofNullable(updates.get(path));
        }

        @Override
        public boolean timerFired(NodePath path) {
            return false;
        }

        @Override
        public Instant now() {
            return clock.instant();
        }
    }

    private byte[] encodeValue(Object value) {
        return value == null ? new byte[0] : codec.encode(value);
    }

    private Object decodeValue(byte[] payload) {
        return payload.length == 0 ? null : codec.decode(payload);
    }

    static byte[] encodeFailure(Failure failure) {
        return (failure.errorClass() + "\n" + failure.message()).getBytes(StandardCharsets.UTF_8);
    }

    static Failure decodeFailure(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        int split = text.indexOf('\n');
        return split < 0 ? new Failure(text, "")
                : new Failure(text.substring(0, split), text.substring(split + 1));
    }
}
