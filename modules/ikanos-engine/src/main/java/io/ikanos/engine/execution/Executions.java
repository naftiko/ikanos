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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.Runner;
import io.ikanos.durable.core.DirectRunner;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.util.OperationStepElicitSpec;

/**
 * The one entry point through which steps and single calls run, whatever exposed them.
 *
 * <p>Today every execution runs in the {@code none} mode, through the {@link DirectRunner}: the
 * Interpreter's commands are carried out inline, in the request thread, and nothing is kept. The
 * exception that ended a failed run is rethrown unchanged, so the adapters keep their status and
 * error mapping.</p>
 */
public final class Executions {

    /** Path of the single action of an inline {@code call:}. */
    static final NodePath CALL = NodePath.of("call");

    private static final Runner DIRECT = new DirectRunner();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * The input request key prefix of the run in progress on this thread: the path of each step
     * that called a flow on the way down, each followed by {@code /}; empty at the top.
     */
    private static final ThreadLocal<String> KEY_PREFIX = ThreadLocal.withInitial(() -> "");

    /**
     * Carries a nested flow's question out of the calling step without it being recorded as that
     * step's failure: an action that throws an {@link Error} has no outcome, so a later run with
     * the answer invokes it again. The run that called the step turns it back into the
     * {@link InputRequiredException}.
     */
    private static final class Asked extends Error {
        private static final long serialVersionUID = 1L;
        private final transient InputRequiredException question;

        Asked(InputRequiredException question) {
            super(question.getMessage(), null, false, false);
            this.question = question;
        }
    }

    private Executions() {
    }

    /**
     * Runs a compiled sequence of steps and returns its run state.
     *
     * @throws RuntimeException the exception that ended the run, unchanged
     */
    public static StepRun runSteps(ExecutionTree tree, StepRun run, NodeRunner nodeRunner) {
        if (tree.isEmpty()) {
            return run;
        }
        DurableScope scope = DurableScope.current();
        StepInterpreter interpreter = new StepInterpreter(tree, run, nodeRunner);
        String prefix = KEY_PREFIX.get();
        // This run's answers only: those whose key starts with its prefix, the prefix removed.
        Map<NodePath, Object> answers = answersFor(prefix, ExecutionInputs.current());
        ExecutionResult result;
        try {
            if (scope == null) {
                result = DIRECT.run(new ExecutionRequest(nextId(), null, answers, null),
                        interpreter, request -> runNode(request.action(), run, nodeRunner));
            } else {
                // A task: each step is journaled, and one recorded by an earlier run is restored.
                ExecutionId id = scope.nextExecutionId();
                result = scope.runner().run(new ExecutionRequest(id, null, answers, null),
                        interpreter, request -> {
                            // Shutting down: stop before this action, leaving it to the next
                            // process.
                            scope.checkNotStopping();
                            return DurableScope.within(
                                    scope.child(request.executionId(), request.path()),
                                    () -> runJournaledNode(request.action(), run, nodeRunner));
                        });
                if (result instanceof ExecutionResult.Completed) {
                    // Recorded before this run started: walk the tree once against the journal,
                    // so the outputs mappings read are restored.
                    interpreter.next(scope.runner().view(id));
                }
            }
        } catch (Asked asked) {
            // A flow called by one of these steps asks: its keys already carry their prefix.
            throw asked.question;
        }
        if (result instanceof ExecutionResult.Completed) {
            return run;
        }
        if (result instanceof ExecutionResult.Suspended suspended) {
            // Only elicit steps await an update: the caller must answer them.
            throw new InputRequiredException(suspended.awaiting().stream()
                    .map(await -> new InputRequiredException.Question(
                            prefix + await.path().value(),
                            (OperationStepElicitSpec) ((StepNode) await.request()).step()))
                    .toList());
        }
        throw rethrowable(run, result);
    }

    /**
     * The key prefix of the step running on this thread: the path of each flow-call step on the
     * way, then the step's own path, each followed by {@code /}. A consumed call that passes an
     * upstream's questions through keys them under it.
     */
    public static String currentKeyPrefix() {
        return KEY_PREFIX.get();
    }

    /**
     * The answers addressed to a run whose keys start with {@code prefix}, keyed by the run's own
     * paths. At the top, the prefix is empty and every answer passes.
     */
    static Map<NodePath, Object> answersFor(String prefix, Map<NodePath, Object> answers) {
        if (prefix.isEmpty()) {
            return answers;
        }
        Map<NodePath, Object> own = new LinkedHashMap<>();
        answers.forEach((path, value) -> {
            if (path.value().startsWith(prefix)) {
                own.put(new NodePath(path.value().substring(prefix.length())), value);
            }
        });
        return own;
    }

    /**
     * Runs a prepared inline {@code call:} as a one-action execution and returns its result. The
     * result is not checked for success: a single call passes the upstream status through.
     *
     * @throws Exception the exception the invocation raised, unchanged
     */
    public static ConsumedResult invoke(ConsumedInvocation invocation) throws Exception {
        StepRun run = new StepRun(null);
        ExecutionResult result = DIRECT.run(ExecutionRequest.of(nextId(), null), view -> {
            var outcome = view.outcome(CALL);
            if (outcome.isEmpty()) {
                return List.of(new Command.ScheduleAction(CALL, invocation, null));
            }
            if (outcome.get() instanceof ActionOutcome.Failed failed) {
                return List.of(new Command.Fail(failed.failure()));
            }
            return List.of(new Command.Complete(
                    ((ActionOutcome.Succeeded) outcome.get()).value()));
        }, request -> {
            try {
                return ((ConsumedInvocation) request.action()).invoke();
            } catch (Exception e) {
                run.fail(e);
                throw new ActionFailedException(StepInterpreter.failureOf(e), e);
            }
        });
        if (result instanceof ExecutionResult.Completed completed) {
            return (ConsumedResult) completed.result();
        }
        Exception failure = run.failure();
        throw failure != null ? failure : rethrowable(run, result);
    }

    /** Runs one action node, marking it evaluated so the Interpreter never restores it again. */
    private static Object runNode(Object action, StepRun run, NodeRunner nodeRunner)
            throws ActionFailedException {
        StepNode node = (StepNode) action;
        String prefix = KEY_PREFIX.get();
        // A flow this step calls asks under the step's path.
        KEY_PREFIX.set(prefix + node.path().value() + "/");
        try {
            Object output = nodeRunner.run(node, run);
            run.markEvaluated(node.path());
            return output;
        } catch (InputRequiredException e) {
            throw new Asked(e);
        } catch (RuntimeException e) {
            run.fail(e);
            throw new ActionFailedException(StepInterpreter.failureOf(e), e);
        } finally {
            KEY_PREFIX.set(prefix);
        }
    }

    /**
     * Runs one action node of a journaled execution. A call step whose response has a body but no
     * JSON output, such as a binary body, fails: its outcome could not be journaled, and a restart
     * would restore it as a response without a body. The failure is journaled, so a replay fails
     * the same way.
     */
    private static Object runJournaledNode(Object action, StepRun run, NodeRunner nodeRunner)
            throws ActionFailedException {
        Object output = runNode(action, run, nodeRunner);
        StepNode node = (StepNode) action;
        if (node.kind() == StepNode.Kind.CALL && output == null && run.lastResult() != null
                && run.lastResult().hasBody()) {
            IllegalStateException e = new IllegalStateException("Step '"
                    + node.step().getName() + "' returned a response that is not JSON, which a "
                    + "durable task cannot journal; call it outside the task, or map it to JSON");
            run.fail(e);
            throw new ActionFailedException(StepInterpreter.failureOf(e), e);
        }
        return output;
    }

    static RuntimeException rethrowable(StepRun run, ExecutionResult result) {
        if (run.failure() instanceof RuntimeException runtime) {
            return runtime;
        }
        if (run.failure() != null) {
            return new IllegalStateException(run.failure().getMessage(), run.failure());
        }
        if (result instanceof ExecutionResult.Failed failed) {
            // A failure recorded by an earlier run of a journaled execution: no exception here.
            return new IllegalStateException(failed.failure().message());
        }
        return new IllegalStateException("The execution did not complete: " + result);
    }

    static ExecutionId nextId() {
        return new ExecutionId("direct-" + SEQUENCE.incrementAndGet());
    }
}
