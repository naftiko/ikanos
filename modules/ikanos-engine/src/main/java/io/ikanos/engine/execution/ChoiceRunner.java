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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepChoiceSpec;
import io.ikanos.spec.util.OperationStepSequenceSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the path a {@code choice} step picks (#1171): {@code then} when its {@code if} holds,
 * {@code else} otherwise, or nothing without {@code else}.
 *
 * <p>The path runs as a step run of its own, from a copy of what the run held when the step
 * started, like an item of a {@code forEach}. The step's output is the path's output (that of
 * its step, or of the last step of its {@code sequence} that has one), and its outcome is the
 * outcome of the path's last call step. The condition reads only what the run holds, so a replay
 * picks the same path; the step's journaled output names the path, and a durable path journals
 * its steps under a scope named after it.</p>
 */
public final class ChoiceRunner {

    /** Fields of a {@code choice} step's journaled output. */
    static final String PATH = "path";
    static final String OUTPUT = "output";
    static final String OUTCOME = "outcome";

    private ChoiceRunner() {
    }

    /**
     * Runs the path {@code step} picks and records its output and outcome under the step's name.
     *
     * @return what to journal: the path taken, its output and its outcome
     */
    public static JsonNode run(OperationStepChoiceSpec step, int stepIndex, StepRun run,
            StepHandlerRegistry registry, NodeRunner nodeRunner) {
        if (run.lastResult() != null) {
            run.lastResult().release();
            run.setLastResult(null);
        }
        boolean holds = StepGuard.holdsAs("The if of step '" + step.getName() + "'",
                step.getIf(), StepGuard.parametersOf(run));
        OperationStepSpec path = holds ? step.getThen() : step.getElse();
        String taken = path == null ? "none" : holds ? "then" : "else";
        ObjectNode journaled = IkanosMappers.json().createObjectNode().put(PATH, taken);
        Span span = TelemetryBootstrap.get().startStepChoiceSpan(stepIndex, step.getName(),
                taken);
        try (Scope otel = span.makeCurrent()) {
            if (path == null) {
                return journaled;
            }
            StepRun pathRun = new StepRun(run.runtimeParameters());
            StepOutcomes.inherit(pathRun.runtimeParameters(), run.runtimeParameters());
            run.stepContext().getAllStepOutputs().forEach(pathRun.stepContext()::storeStepOutput);
            if (path instanceof OperationStepSequenceSpec sequence
                    && !StepGuard.holds(path.getName(), sequence.getOnlyIf(),
                            StepGuard.parametersOf(pathRun))) {
                TelemetryBootstrap.get().recordStepSkipped(stepIndex, path.getName());
                return journaled;
            }
            Map<String, OperationStepSpec> steps = ParallelTracks.stepsOf(path.getName(), path);
            DurableScope scope = DurableScope.current();
            DurableScope pathScope = scope != null ? scope.track(taken) : null;
            Callable<StepRun> work = () -> Executions.runSteps(
                    ExecutionTree.compile(steps, registry), pathRun, nodeRunner);
            try {
                ExecutionInputs.within(ExecutionInputs.capture(), NodePath.of(taken),
                        pathScope != null ? () -> DurableScope.within(pathScope, work) : work);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                if (pathRun.lastResult() != null) {
                    pathRun.lastResult().release();
                    pathRun.setLastResult(null);
                }
            }
            List<String> names = new ArrayList<>(steps.keySet());
            for (int i = names.size() - 1; i >= 0; i--) {
                JsonNode output = pathRun.stepContext().getStepOutput(names.get(i));
                if (output != null && !output.isNull() && !output.isMissingNode()) {
                    journaled.set(OUTPUT, output);
                    break;
                }
            }
            for (int i = names.size() - 1; i >= 0; i--) {
                String outcome = StepOutcomes.get(pathRun.runtimeParameters(), names.get(i));
                if (outcome != null) {
                    journaled.put(OUTCOME, outcome);
                    break;
                }
            }
        } catch (RuntimeException e) {
            TelemetryBootstrap.recordError(span, e);
            throw e;
        } finally {
            TelemetryBootstrap.endSpan(span);
        }
        restore(step.getName(), journaled, run);
        return journaled;
    }

    /** Records the output and outcome of a {@code choice} step's journaled output in {@code run}. */
    static void restore(String stepName, JsonNode journaled, StepRun run) {
        if (journaled == null || !journaled.isObject() || stepName == null) {
            return;
        }
        if (journaled.hasNonNull(OUTPUT)) {
            run.putOutput(stepName, journaled.get(OUTPUT));
        }
        if (journaled.hasNonNull(OUTCOME)) {
            StepOutcomes.put(run.runtimeParameters(), stepName,
                    journaled.get(OUTCOME).asString());
        }
    }

}
