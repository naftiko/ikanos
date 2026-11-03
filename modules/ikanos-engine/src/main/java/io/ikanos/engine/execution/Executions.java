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

import java.util.List;
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
        ExecutionResult result = DIRECT.run(ExecutionRequest.of(nextId(), null),
                new StepInterpreter(tree, run, nodeRunner), request -> {
                    StepNode node = (StepNode) request.action();
                    try {
                        return nodeRunner.run(node, run);
                    } catch (RuntimeException e) {
                        run.fail(e);
                        throw new ActionFailedException(StepInterpreter.failureOf(e), e);
                    }
                });
        if (result instanceof ExecutionResult.Completed) {
            return run;
        }
        throw rethrowable(run, result);
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

    static RuntimeException rethrowable(StepRun run, ExecutionResult result) {
        if (run.failure() instanceof RuntimeException runtime) {
            return runtime;
        }
        if (run.failure() != null) {
            return new IllegalStateException(run.failure().getMessage(), run.failure());
        }
        return new IllegalStateException("The execution did not complete: " + result);
    }

    static ExecutionId nextId() {
        return new ExecutionId("direct-" + SEQUENCE.incrementAndGet());
    }
}
