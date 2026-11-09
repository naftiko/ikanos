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
package io.ikanos.engine.execution;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.core.JournaledRunner;

/**
 * Where the step runs of the current work are journaled, step by step.
 *
 * <p>Outside a scope, steps run on the {@code DirectRunner} and keep nothing, which is what every
 * synchronous call does. Inside one, opened for an MCP task, each sequence of steps runs on the
 * {@link JournaledRunner} as an execution of its own, so a step that already has an outcome is
 * restored from the journal instead of running again.</p>
 *
 * <p>Execution IDs follow the call structure, never a counter: the task's steps run as
 * {@code <root>/steps}, and the steps of a flow called by step {@code s} run as
 * {@code <parent execution>/<path of s>}. A replay that restores step {@code s} without running
 * it therefore cannot shift the IDs of the flows called after it. The scope is carried by the
 * thread running each action, so it holds whichever thread the runner runs an action on.</p>
 */
public final class DurableScope {

    private static final ThreadLocal<DurableScope> CURRENT = new ThreadLocal<>();

    /**
     * Thrown before an action starts once the scope is stopping: an {@link Error}, so no action
     * failure is journaled and the execution stays unfinished, to be resumed by the next process.
     */
    public static final class Stopped extends Error {
        private static final long serialVersionUID = 1L;

        Stopped() {
            super("Stopped before the next action: the process is shutting down");
        }
    }

    private final JournaledRunner runner;
    private final ExecutionId next;
    private final BooleanSupplier stopping;
    private int uses;

    private DurableScope(JournaledRunner runner, ExecutionId next, BooleanSupplier stopping) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.next = Objects.requireNonNull(next, "next");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
    }

    /**
     * Runs {@code work} with its step runs journaled by {@code runner}, under execution IDs
     * derived from {@code root}.
     */
    public static <T> T with(JournaledRunner runner, ExecutionId root, Callable<T> work)
            throws Exception {
        return with(runner, root, () -> false, work);
    }

    /**
     * Runs {@code work} as {@link #with(JournaledRunner, ExecutionId, Callable)} does, stopping
     * before the next action once {@code stopping} holds: the action in flight finishes and its
     * outcome is journaled, then {@link Stopped} ends the work.
     */
    public static <T> T with(JournaledRunner runner, ExecutionId root, BooleanSupplier stopping,
            Callable<T> work) throws Exception {
        return within(new DurableScope(runner, new ExecutionId(root.value() + "/steps"),
                stopping), work);
    }

    /** Throws {@link Stopped} when the scope is stopping, before an action starts. */
    void checkNotStopping() {
        if (stopping.getAsBoolean()) {
            throw new Stopped();
        }
    }

    /** The scope of the current thread, or {@code null} when its steps run directly. */
    static DurableScope current() {
        return CURRENT.get();
    }

    /** Runs {@code work} inside {@code scope}, then restores the scope it replaced. */
    static <T> T within(DurableScope scope, Callable<T> work) throws Exception {
        DurableScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Runs a small journaled run of its own for the action in progress, such as a consumed call
     * waiting on an upstream task, under the action's path, so a restart restores what it
     * recorded. Empty when no journal is kept: the caller then works directly.
     */
    public static java.util.Optional<io.ikanos.durable.ExecutionResult> runJournaled(
            io.ikanos.durable.Interpreter interpreter, io.ikanos.durable.ActionExecutor executor) {
        DurableScope scope = current();
        if (scope == null) {
            return java.util.Optional.empty();
        }
        ExecutionId id = scope.nextExecutionId();
        return java.util.Optional.of(scope.runner().run(
                io.ikanos.durable.ExecutionRequest.of(id, null), interpreter, request -> {
                    scope.checkNotStopping();
                    return executor.execute(request);
                }));
    }

    /** Whether the action in progress runs inside a journaled run. */
    public static boolean isJournaled() {
        return current() != null;
    }

    /**
     * The identity of the action in progress, its execution ID and path, or empty when no journal
     * is kept. Every attempt of the action, also after a restart, has the same identity.
     */
    public static java.util.Optional<String> actionIdentity() {
        DurableScope scope = current();
        return scope == null ? java.util.Optional.empty()
                : java.util.Optional.of(scope.next.value());
    }

    JournaledRunner runner() {
        return runner;
    }

    /**
     * The execution ID of the next step run in this scope. A second run started by the same
     * action, which a replay re-runs as a whole, gets a numbered ID, in the same order.
     */
    synchronized ExecutionId nextExecutionId() {
        uses++;
        return uses == 1 ? next : new ExecutionId(next.value() + "#" + uses);
    }

    /** The scope of the step runs started by the action at {@code path} of {@code execution}. */
    DurableScope child(ExecutionId execution, NodePath path) {
        return new DurableScope(runner, new ExecutionId(execution.value() + "/" + path.value()),
                stopping);
    }
}
