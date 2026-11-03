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

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.ActionRequest;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.Runner;

/**
 * Runner of the {@code none} durability mode: carries the Interpreter's commands out inline, in
 * the calling thread, and keeps nothing once the run ends.
 *
 * <ul>
 *   <li>A single action runs in the calling thread; several actions returned together run
 *       concurrently on virtual threads, and their outcomes are recorded in command order.</li>
 *   <li>Each action runs once. Durable retry policies are ignored here: retries across attempts
 *       are a durable-mode feature, and in-process retries belong to the action executor.</li>
 *   <li>Timers are rejected: a capability that needs one runs in a durable mode.</li>
 *   <li>An {@link Command.AwaitUpdate} without a known update suspends the run. The caller
 *       collects the answer and runs the execution again from the start, with the answer in
 *       {@link ExecutionRequest#updates()}; this is a stateless multi round-trip request.</li>
 * </ul>
 *
 * <p>Because nothing is journaled, an action that runs before a pause runs again on the next
 * round trip. Validation only allows a pause under {@code none} when every action before it is
 * safe.</p>
 */
public final class DirectRunner implements Runner {

    private final Clock clock;

    public DirectRunner() {
        this(Clock.systemUTC());
    }

    public DirectRunner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExecutionResult run(ExecutionRequest request, Interpreter interpreter,
            ActionExecutor executor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(interpreter, "interpreter");
        Objects.requireNonNull(executor, "executor");
        DirectExecutionView view = new DirectExecutionView(request, clock);

        while (true) {
            if (request.deadline() != null && clock.instant().isAfter(request.deadline())) {
                return new ExecutionResult.Failed(new Failure(Failure.DEADLINE_EXCEEDED,
                        "The execution ran past its deadline " + request.deadline()));
            }

            List<Command> commands = interpreter.next(view);
            checkBatch(commands);

            List<Command.ScheduleAction> actions = new ArrayList<>();
            List<Command.AwaitUpdate> awaiting = new ArrayList<>();
            for (Command command : commands) {
                switch (command) {
                    case Command.Complete complete -> {
                        return new ExecutionResult.Completed(complete.result());
                    }
                    case Command.Fail fail -> {
                        return new ExecutionResult.Failed(fail.failure());
                    }
                    case Command.RecordDecision decision -> recordDecision(view, decision);
                    case Command.ScheduleAction action -> actions.add(checkAction(view, action));
                    case Command.AwaitUpdate await -> awaiting.add(checkAwait(view, await));
                    case Command.StartTimer timer -> throw new IllegalStateException(
                            "The timer at " + timer.path()
                                    + " needs a durability mode other than none");
                }
            }

            runActions(request, actions, view, executor);

            if (!awaiting.isEmpty() && actions.isEmpty()) {
                return new ExecutionResult.Suspended(awaiting);
            }
        }
    }

    static void checkBatch(List<Command> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalStateException("The Interpreter returned no command");
        }
        boolean terminal = commands.stream()
                .anyMatch(c -> c instanceof Command.Complete || c instanceof Command.Fail);
        if (terminal && commands.size() > 1) {
            throw new IllegalStateException(
                    "A terminal command must be the only command of its batch: " + commands);
        }
        Set<Object> paths = new HashSet<>();
        for (Command command : commands) {
            if (command.path() != null && !paths.add(command.path())) {
                throw new IllegalStateException(
                        "Two commands of one batch address " + command.path());
            }
        }
    }

    static void recordDecision(DirectExecutionView view, Command.RecordDecision decision) {
        if (view.hasDecision(decision.path())) {
            throw new IllegalStateException(
                    "A decision is already recorded at " + decision.path());
        }
        view.recordDecision(decision.path(), decision.value());
    }

    static Command.ScheduleAction checkAction(DirectExecutionView view,
            Command.ScheduleAction action) {
        if (view.hasOutcome(action.path())) {
            throw new IllegalStateException(
                    "The action at " + action.path() + " already has an outcome");
        }
        return action;
    }

    static Command.AwaitUpdate checkAwait(DirectExecutionView view, Command.AwaitUpdate await) {
        if (view.update(await.path()).isPresent()) {
            throw new IllegalStateException(
                    "The update for " + await.path() + " is already known to the Interpreter");
        }
        return await;
    }

    static void runActions(ExecutionRequest request, List<Command.ScheduleAction> actions,
            DirectExecutionView view, ActionExecutor executor) {
        if (actions.size() == 1) {
            Command.ScheduleAction action = actions.get(0);
            view.recordOutcome(action.path(), attempt(request, action, executor));
            return;
        }
        if (actions.isEmpty()) {
            return;
        }
        List<ActionOutcome> outcomes = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ActionOutcome>> futures = new ArrayList<>();
            for (Command.ScheduleAction action : actions) {
                futures.add(threads.submit(() -> attempt(request, action, executor)));
            }
            for (Future<ActionOutcome> future : futures) {
                outcomes.add(await(future));
            }
        }
        for (int i = 0; i < actions.size(); i++) {
            view.recordOutcome(actions.get(i).path(), outcomes.get(i));
        }
    }

    static ActionOutcome attempt(ExecutionRequest request, Command.ScheduleAction action,
            ActionExecutor executor) {
        ActionRequest attempt =
                new ActionRequest(request.executionId(), action.path(), action.action(), 1);
        try {
            return new ActionOutcome.Succeeded(executor.execute(attempt));
        } catch (ActionFailedException e) {
            return new ActionOutcome.Failed(e.failure());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionOutcome.Failed(new Failure(Failure.INTERRUPTED,
                    "The action at " + action.path() + " was interrupted"));
        } catch (Exception e) {
            return new ActionOutcome.Failed(new Failure(Failure.UNEXPECTED, describe(e)));
        }
    }

    static ActionOutcome await(Future<ActionOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionOutcome.Failed(
                    new Failure(Failure.INTERRUPTED, "The run was interrupted"));
        } catch (ExecutionException e) {
            return new ActionOutcome.Failed(new Failure(Failure.UNEXPECTED, describe(e.getCause())));
        }
    }

    static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
