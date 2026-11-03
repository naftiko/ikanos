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
import java.util.Optional;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;

/**
 * The Ikanos {@link Interpreter} for a sequence of steps.
 *
 * <p>It walks the tree in order: the first action without an outcome is scheduled; a failed
 * action fails the execution; a lookup is evaluated in place, since it only reads earlier
 * outputs; when every node is done, the execution completes. The step outputs themselves stay in
 * the {@link StepRun}, which the {@link NodeRunner} updates as each step runs.</p>
 */
public final class StepInterpreter implements Interpreter {

    private final ExecutionTree tree;
    private final StepRun run;
    private final NodeRunner nodeRunner;

    public StepInterpreter(ExecutionTree tree, StepRun run, NodeRunner nodeRunner) {
        this.tree = tree;
        this.run = run;
        this.nodeRunner = nodeRunner;
    }

    @Override
    public List<Command> next(ExecutionView view) {
        for (StepNode node : tree.nodes()) {
            if (node.kind() == StepNode.Kind.LOOKUP) {
                if (!run.isEvaluated(node.path())) {
                    try {
                        nodeRunner.run(node, run);
                    } catch (RuntimeException e) {
                        run.fail(e);
                        return List.of(new Command.Fail(failureOf(e)));
                    }
                    run.markEvaluated(node.path());
                }
            } else if (node.kind().isAction()) {
                Optional<ActionOutcome> outcome = view.outcome(node.path());
                if (outcome.isEmpty()) {
                    return List.of(new Command.ScheduleAction(node.path(), node, null));
                }
                if (outcome.get() instanceof ActionOutcome.Failed failed) {
                    return List.of(new Command.Fail(failed.failure()));
                }
            }
        }
        return List.of(new Command.Complete(null));
    }

    static Failure failureOf(Exception e) {
        String simpleName = e.getClass().getSimpleName();
        return new Failure(simpleName.isEmpty() ? e.getClass().getName() : simpleName,
                e.getMessage());
    }
}
