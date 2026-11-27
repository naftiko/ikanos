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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.ActionRequest;
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
import io.ikanos.durable.store.Lease;
import io.ikanos.durable.store.PayloadCodec;
import io.ikanos.durable.store.StoreFeature;
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
 *   <li>Runs of one execution are serialised in this process.</li>
 *   <li>On a store several replicas share ({@link StoreFeature#SHARED}), a replica writes an
 *       execution only under its lease, renewed while the run goes on and released when it ends.
 *       A replica that finds the lease held by another gets an {@link ExecutionBusyException};
 *       once the holder's lease expires, as when its process died, another replica takes the
 *       execution over with a greater fencing token, and the journal refuses the stale holder's
 *       appends. An update delivered while a run held the lease is read when it ends: its sender
 *       could not run the execution, so the holder runs it again.</li>
 * </ul>
 *
 * <p>Durable timers and retries across attempts come with the next increment; a timer is
 * rejected for now.</p>
 */
public final class JournaledRunner implements Runner {

    /** Fencing token used while a single process owns every execution. */
    static final long SINGLE_OWNER = 0L;

    /** How long a lease on a shared store lasts unless renewed; renewed every third of it. */
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    private final Stores stores;
    private final PayloadCodec codec;
    private final Clock clock;
    private final String specDigest;
    private final String owner;
    private final Duration leaseFor;
    private final boolean shared;
    /** Ownership of no execution: the token of a single process, with no lease to renew. */
    private final Owned notOwned = new Owned((Lease) null);
    /** Renews the leases of the runs in progress; started with the first lease. */
    private volatile ScheduledExecutorService renewals;
    /** The lock of each execution a thread runs or waits on; removed once none does. */
    private final Map<ExecutionId, ExecutionLock> locks = new ConcurrentHashMap<>();
    /** The leases this runner holds, by execution: its nested executions run under them. */
    private final Map<ExecutionId, Owned> held = new ConcurrentHashMap<>();

    public JournaledRunner(Stores stores, PayloadCodec codec, Clock clock, String specDigest) {
        this(stores, codec, clock, specDigest, "runner-" + UUID.randomUUID(), DEFAULT_LEASE);
    }

    /**
     * @param owner the name this runner's leases carry, distinct for each replica
     * @param leaseFor how long a lease lasts unless renewed, on a shared store
     */
    public JournaledRunner(Stores stores, PayloadCodec codec, Clock clock, String specDigest,
            String owner, Duration leaseFor) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.specDigest = Objects.requireNonNull(specDigest, "specDigest");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.leaseFor = Objects.requireNonNull(leaseFor, "leaseFor");
        if (leaseFor.isNegative() || leaseFor.isZero()) {
            throw new IllegalArgumentException("A lease must last some time: " + leaseFor);
        }
        this.shared = stores.journal().features().contains(StoreFeature.SHARED);
    }

    /** Whether several replicas share this runner's store, so its writes need a lease. */
    public boolean isShared() {
        return shared;
    }

    /** The name this runner's leases carry. */
    public String owner() {
        return owner;
    }

    /** How long a lease lasts unless renewed. */
    public Duration leaseFor() {
        return leaseFor;
    }

    /** Stops renewing leases; the runs still in progress lose theirs once they expire. */
    public void close() {
        ScheduledExecutorService running = renewals;
        if (running != null) {
            running.shutdownNow();
        }
    }

    /**
     * Creates the execution in the index, without running it. Returns {@code false} when it
     * already exists. A durable MCP task is answered only once this returns.
     */
    public boolean create(ExecutionRequest request, String entry) {
        return stores.index().create(new ExecutionRecord(request.executionId(), specDigest,
                entry, ExecutionStatus.RUNNING, clock.instant(), request.deadline(), Map.of()));
    }

    /**
     * Creates an execution that a restarted process can start again: its index entry, with
     * {@code entry} and {@code labels}, then a first journal entry holding the request's input.
     * Returns {@code false}, and records nothing, when the execution already exists.
     */
    public boolean start(ExecutionRequest request, String entry, Map<String, String> labels) {
        ExecutionId id = request.executionId();
        ExecutionLock lock = lock(id);
        // Leased before it is indexed: a replica that finds it running before its input is
        // journaled cannot take it for one whose creation never finished.
        try (Owned owned = own(id)) {
            if (!stores.index().create(new ExecutionRecord(id, specDigest, entry,
                    ExecutionStatus.RUNNING, clock.instant(), request.deadline(), labels))) {
                return false;
            }
            stores.journal().append(id, 0, owned.token(), List.of(new JournalEntry(1,
                    JournalEntry.Kind.EXECUTION_STARTED, null, encodeValue(request.input()))));
            return true;
        } catch (ConcurrentAppendException e) {
            throw conflict(id, e);
        } finally {
            unlock(id, lock);
        }
    }

    /**
     * The idempotency key of an action: stable across every run of the execution, so an action
     * invoked again after a crash carries the same key as the attempt the crash cut short. It is
     * {@link ActionRequest#idempotencyKey(ExecutionId, NodePath)}, the key executors receive.
     */
    public static String idempotencyKey(ExecutionId executionId, NodePath path) {
        return ActionRequest.idempotencyKey(executionId, path);
    }

    /** The input recorded by {@link #start}, or empty when the execution has none. */
    public Optional<Object> input(ExecutionId executionId) {
        List<JournalEntry> entries = stores.journal().read(executionId);
        if (entries.isEmpty() || entries.get(0).kind() != JournalEntry.Kind.EXECUTION_STARTED) {
            return Optional.empty();
        }
        return Optional.ofNullable(decodeValue(entries.get(0).payload()));
    }

    /** The index entry of an execution. */
    public Optional<ExecutionRecord> record(ExecutionId executionId) {
        return stores.index().find(executionId);
    }

    /** The executions labelled {@code key} = {@code value}, oldest first. */
    public List<ExecutionRecord> findByLabel(String key, String value) {
        return stores.index().findByLabel(key, value);
    }

    /**
     * Every sub-run of {@code root} with {@code status}, at any depth (IDs starting with
     * {@code <root>/}), oldest first, however many other executions the store holds.
     */
    public List<ExecutionRecord> listUnder(ExecutionId root, ExecutionStatus status) {
        return stores.index().listUnder(root.value() + "/", status);
    }

    /**
     * Up to {@code limit} executions with {@code status} and a label {@code key}, oldest first,
     * filtered by the store before the limit.
     *
     * @param value the label's value, or {@code null} for any value
     * @param createdBefore only executions created strictly before it, or {@code null} for all
     */
    public List<ExecutionRecord> listLabelled(ExecutionStatus status, String key, String value,
            Instant createdBefore, int limit) {
        return stores.index().listLabelled(status, key, value, createdBefore, limit);
    }

    /**
     * Up to {@code limit} executions with {@code status} and a label {@code key} that no replica
     * runs: on a shared store, those whose lease has expired or was released, oldest first.
     */
    public List<ExecutionRecord> listUnowned(ExecutionStatus status, String key, int limit) {
        return stores.index().listUnleased(status, key, clock.instant(), limit);
    }

    /**
     * Like {@link #listUnowned}, keeping the executions with updates waiting in the inbox,
     * filtered by the store before the limit where it can see its inbox.
     */
    public List<ExecutionRecord> listUnownedWithUpdates(ExecutionStatus status, String key,
            int limit) {
        return stores.index().listUnleasedWithUpdates(status, key, clock.instant(), limit);
    }

    /** Up to {@code limit} executions with {@code status}, oldest first. */
    public List<ExecutionRecord> list(ExecutionStatus status, int limit) {
        return stores.index().list(status, limit);
    }

    /** The digest of the spec this runner's executions are pinned to. */
    public String specDigest() {
        return specDigest;
    }

    /**
     * Ends an execution that has not ended with {@code failure}, without running it: for an
     * execution this process cannot run, such as one started by another version of the spec.
     */
    public void fail(ExecutionId executionId, Failure failure) {
        ExecutionLock lock = lock(executionId);
        try (Owned owned = own(executionId)) {
            Optional<ExecutionStatus> status = status(executionId);
            if (status.isEmpty() || status.get().isTerminal()) {
                return;
            }
            new Run(ExecutionRequest.of(executionId, null), owned).fail(failure);
        } catch (ConcurrentAppendException e) {
            throw conflict(executionId, e);
        } finally {
            unlock(executionId, lock);
        }
    }

    /**
     * Ends an execution with {@code failure} only if, under its lock, it still has
     * {@code expected} status and {@code condition} holds for its journal as read then: a
     * decision made on an earlier read never ends a run that moved on since, such as one that
     * resumed and asked again.
     *
     * @param expected a status that has not ended, such as {@code SUSPENDED}
     * @return whether the execution was failed; {@code false} too when another replica runs it
     */
    public boolean failIf(ExecutionId executionId, ExecutionStatus expected,
            Predicate<ExecutionView> condition, Failure failure) {
        ExecutionLock lock = lock(executionId);
        try (Owned owned = own(executionId)) {
            if (status(executionId).orElse(null) != expected) {
                return false;
            }
            Run run = new Run(ExecutionRequest.of(executionId, null), owned);
            if (!condition.test(run)) {
                return false;
            }
            run.fail(failure);
            return true;
        } catch (ExecutionBusyException e) {
            // Running elsewhere, so no longer in the state the decision was made on.
            return false;
        } catch (ConcurrentAppendException e) {
            if (shared) {
                return false;
            }
            throw conflict(executionId, e);
        } finally {
            unlock(executionId, lock);
        }
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

    /**
     * A read-only view of an execution, rebuilt from its journal: the outcomes, decisions and
     * updates recorded so far. An Interpreter walked against it restores its own state without
     * running anything, for an execution whose result is already recorded.
     */
    public ExecutionView view(ExecutionId executionId) {
        return new Run(ExecutionRequest.of(executionId, null), notOwned);
    }

    /** Whether updates were delivered to an execution that has not journaled them yet. */
    public boolean hasPendingUpdates(ExecutionId executionId) {
        return !stores.inbox().pending(executionId).isEmpty();
    }

    /** Delivers an update for {@code path}; returns {@code false} if one was already delivered. */
    public boolean deliver(ExecutionId executionId, NodePath path, Object update) {
        return stores.inbox().deliver(executionId, path, encodeValue(update));
    }

    /** Marks an execution cancelled, unless it already ended. */
    public void cancel(ExecutionId executionId) {
        // Not under the execution's lock, which a running execution holds for its whole run: a
        // cancel must reach it while it runs. The run reads the status at its next loop and at
        // its next step, and ends as cancelled (see Run#cancelled).
        // The update is conditional on the status read: a run that ended since the read keeps its
        // end, so the index never says CANCELLED over a journal that recorded the completion.
        while (true) {
            Optional<ExecutionStatus> status = status(executionId);
            if (status.isEmpty() || status.get().isTerminal()) {
                return;
            }
            if (stores.index().compareAndSetStatus(executionId, status.get(),
                    ExecutionStatus.CANCELLED)) {
                return;
            }
        }
    }

    @Override
    public ExecutionResult run(ExecutionRequest request, Interpreter interpreter,
            ActionExecutor executor) {
        Objects.requireNonNull(interpreter, "interpreter");
        Objects.requireNonNull(executor, "executor");
        ExecutionId id = request.executionId();
        ExecutionLock lock = lock(id);
        try {
            while (true) {
                ExecutionResult result;
                try (Owned owned = own(id)) {
                    result = runOwned(request, interpreter, executor, owned);
                }
                // Released first, then checked: an update delivered by a replica that found the
                // lease held is either seen here or run by that replica, never left unread.
                if (!shared || !(result instanceof ExecutionResult.Suspended)
                        || stores.inbox().pending(id).isEmpty()) {
                    return result;
                }
            }
        } catch (ConcurrentAppendException e) {
            throw conflict(id, e);
        } finally {
            unlock(id, lock);
        }
    }

    private ExecutionResult runOwned(ExecutionRequest request, Interpreter interpreter,
            ActionExecutor executor, Owned owned) throws ConcurrentAppendException {
        ExecutionId id = request.executionId();
        create(request, null);
        ExecutionStatus status = status(id).orElse(ExecutionStatus.RUNNING);
        if (status == ExecutionStatus.CANCELLED) {
            return new ExecutionResult.Failed(
                    new Failure("cancelled", "The execution was cancelled"));
        }
        Optional<ExecutionResult> recorded = result(id);
        if (recorded.isPresent()) {
            // A crash between the journal's last entry and the index update leaves the index
            // behind the journal: the journal is the truth, so the index catches up here.
            stores.index().updateStatus(id, recorded.get() instanceof ExecutionResult.Completed
                    ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED);
            return recorded.get();
        }
        Run run = new Run(request, owned);
        run.receiveUpdates();
        // Conditional, as cancel is: a cancel that landed since the read is not undone; the
        // loop then sees it and ends the run as cancelled.
        stores.index().compareAndSetStatus(id, status, ExecutionStatus.RUNNING);
        return run.loop(interpreter, executor);
    }

    /**
     * A write refused by the journal. On a shared store, another replica wrote first: it owns
     * the execution now. Otherwise, a second writer in one process, which is a bug.
     */
    private IllegalStateException conflict(ExecutionId id, ConcurrentAppendException e) {
        return shared ? new ExecutionBusyException(id, e)
                : new IllegalStateException("Another owner appended to the journal of " + id, e);
    }

    /**
     * Takes the right to write {@code id}: its lease on a shared store, nothing otherwise. A
     * nested execution ({@code <execution>/...}, such as a task's steps or a flow one of them
     * calls) runs under the lease this runner holds on an enclosing one, and takes none of its
     * own: so a replica that takes a task over owns its nested executions with it, whatever
     * leases the replica that stopped left on them.
     */
    private Owned own(ExecutionId id) {
        if (!shared) {
            return notOwned;
        }
        for (Map.Entry<ExecutionId, Owned> enclosing : held.entrySet()) {
            if (id.value().startsWith(enclosing.getKey().value() + "/")) {
                return new Owned(enclosing.getValue());
            }
        }
        Lease lease = stores.queue().acquire(id, owner, leaseFor)
                .orElseThrow(() -> new ExecutionBusyException(id, null));
        Owned owned = new Owned(lease);
        held.put(id, owned);
        return owned;
    }

    private ScheduledExecutorService renewals() {
        ScheduledExecutorService running = renewals;
        if (running == null) {
            synchronized (this) {
                running = renewals;
                if (running == null) {
                    running = Executors.newSingleThreadScheduledExecutor(task -> {
                        Thread thread = new Thread(task, "ikanos-lease-renewals");
                        thread.setDaemon(true);
                        return thread;
                    });
                    renewals = running;
                }
            }
        }
        return running;
    }

    /**
     * The right to write an execution's journal: its lease on a shared store, renewed every
     * third of its length until closed; nothing on a store of one process.
     */
    private final class Owned implements AutoCloseable {

        private final Lease lease;
        /** The enclosing execution's ownership this one borrows, or {@code null}. */
        private final Owned enclosing;
        private volatile boolean lost;
        private volatile ScheduledFuture<?> renewal;

        /** Ownership borrowed from an enclosing execution: its token, renewed and released by it. */
        Owned(Owned enclosing) {
            this.lease = null;
            this.enclosing = enclosing;
        }

        Owned(Lease lease) {
            this.lease = lease;
            this.enclosing = null;
            if (lease != null) {
                long period = Math.max(1, leaseFor.toMillis() / 3);
                renewal = renewals().scheduleAtFixedRate(this::renew, period, period,
                        TimeUnit.MILLISECONDS);
            }
        }

        long token() {
            if (enclosing != null) {
                return enclosing.token();
            }
            return lease == null ? SINGLE_OWNER : lease.fencingToken();
        }

        /** Whether the lease expired before it was renewed: another replica may own it now. */
        boolean lost() {
            return enclosing != null ? enclosing.lost() : lost;
        }

        void renew() {
            try {
                if (!stores.queue().renew(lease, leaseFor)) {
                    lost = true;
                    renewal.cancel(false);
                }
            } catch (RuntimeException e) {
                // The store is unreachable for now: the next renewal tries again, and the run
                // stops at its next append if the lease expires meanwhile.
            }
        }

        @Override
        public void close() {
            if (lease == null) {
                return;
            }
            held.remove(lease.executionId(), this);
            ScheduledFuture<?> scheduled = renewal;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            try {
                stores.queue().release(lease);
            } catch (RuntimeException e) {
                // Unreleased, the lease expires on its own.
            }
        }
    }

    /** The number of executions whose lock is held or awaited, for the tests. */
    int lockCount() {
        return locks.size();
    }

    /**
     * Takes the lock of an execution, registering this thread as one of its users first, so the
     * lock is not removed while the thread waits on it.
     */
    private ExecutionLock lock(ExecutionId executionId) {
        ExecutionLock lock = locks.compute(executionId, (id, held) -> {
            ExecutionLock registered = held == null ? new ExecutionLock() : held;
            registered.users++;
            return registered;
        });
        lock.lock.lock();
        return lock;
    }

    /** Releases the lock of an execution, and removes it once no thread holds or awaits it. */
    private void unlock(ExecutionId executionId, ExecutionLock lock) {
        lock.lock.unlock();
        locks.computeIfPresent(executionId, (id, held) -> --held.users == 0 ? null : held);
    }

    /** A lock and the number of threads holding or awaiting it, changed only inside compute. */
    private static final class ExecutionLock {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    /** One run of one execution: its view rebuilt from the journal, and its appends. */
    private final class Run implements ExecutionView {

        private final ExecutionRequest request;
        private final Owned owned;
        private final Map<NodePath, ActionOutcome> outcomes = new HashMap<>();
        private final Map<NodePath, Object> decisions = new HashMap<>();
        private final Map<NodePath, Object> updates = new HashMap<>();
        private long sequence;

        Run(ExecutionRequest request, Owned owned) {
            this.request = request;
            this.owned = owned;
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
                if (cancelled()) {
                    // Cancelled while it ran: it ends as cancelled, whatever the next command.
                    return new ExecutionResult.Failed(
                            new Failure("cancelled", "The execution was cancelled"));
                }
                List<Command> commands = interpreter.next(this);
                DirectRunner.checkBatch(commands);

                List<Command.ScheduleAction> actions = new ArrayList<>();
                List<Command.AwaitUpdate> awaiting = new ArrayList<>();
                for (Command command : commands) {
                    switch (command) {
                        case Command.Complete complete -> {
                            if (cancelled()) {
                                return new ExecutionResult.Failed(
                                        new Failure("cancelled", "The execution was cancelled"));
                            }
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
            // Scheduled before invoked: a crash during the call leaves a scheduled action without
            // an outcome, which the next run invokes again with the same idempotency key.
            for (Command.ScheduleAction action : actions) {
                append(JournalEntry.Kind.ACTION_SCHEDULED, action.path(),
                        idempotencyKey(request.executionId(), action.path())
                                .getBytes(StandardCharsets.UTF_8));
            }
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

        /** Whether the execution was cancelled since this run started: a cancel wins. */
        boolean cancelled() {
            return status(request.executionId()).orElse(ExecutionStatus.RUNNING)
                    == ExecutionStatus.CANCELLED;
        }

        ExecutionResult fail(Failure failure) throws ConcurrentAppendException {
            if (cancelled()) {
                return new ExecutionResult.Failed(
                        new Failure("cancelled", "The execution was cancelled"));
            }
            append(JournalEntry.Kind.EXECUTION_FAILED, null, encodeFailure(failure));
            stores.index().updateStatus(request.executionId(), ExecutionStatus.FAILED);
            return new ExecutionResult.Failed(failure);
        }

        void append(JournalEntry.Kind kind, NodePath path, byte[] payload)
                throws ConcurrentAppendException {
            if (owned.lost()) {
                throw new ConcurrentAppendException("The lease on " + request.executionId()
                        + " expired before it was renewed");
            }
            JournalEntry entry = new JournalEntry(sequence + 1, kind, path, payload);
            stores.journal().append(request.executionId(), sequence, owned.token(),
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
