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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepParallelSpec;
import io.ikanos.spec.util.OperationStepSequenceSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the tracks of a {@code parallel} step at the same time (#1169).
 *
 * <p>Each track runs as a step run of its own, on a virtual thread, from a copy of the
 * parameters and step outputs the run held when the {@code parallel} step started: a track
 * sees the steps before the group, never another track. A track is one step, or the steps of a
 * {@code sequence}; its output is the output of that step, or of the last step of the sequence
 * that has one.</p>
 *
 * <p>In a durable run, each track's steps are journaled under a scope of its own, named after
 * the track, so a restart restores the tracks that finished and resumes the others, whatever
 * order they ran in. The group's journaled output carries each track's output and outcome, so a
 * replay restores both ({@link #restore}).</p>
 */
public final class ParallelTracks {

    /** Fields of a {@code parallel} step's journaled output. */
    static final String OUTPUTS = "outputs";
    static final String OUTCOMES = "outcomes";

    private ParallelTracks() {
    }

    /**
     * Runs the tracks of {@code step} and records each track's output and outcome in
     * {@code run}, under the track's name.
     *
     * @return the output to journal: each track's output and outcome
     * @throws RuntimeException the failure of the first track, in declaration order, that failed
     */
    public static JsonNode run(OperationStepParallelSpec step, int stepIndex, StepRun run,
            StepHandlerRegistry registry, NodeRunner nodeRunner) {
        Map<String, OperationStepSpec> tracks = new LinkedHashMap<>();
        synchronized (step.getTracks()) {
            tracks.putAll(step.getTracks());
        }
        // The previous call's result is not the group's: nothing reads it after this point.
        if (run.lastResult() != null) {
            run.lastResult().release();
            run.setLastResult(null);
        }
        Map<String, Object> parameters = new LinkedHashMap<>(run.runtimeParameters());
        Map<String, JsonNode> outputs = new LinkedHashMap<>(
                run.stepContext().getAllStepOutputs());
        ExecutionInputs.Snapshot inputs = ExecutionInputs.capture();
        DurableScope scope = DurableScope.current();
        TelemetryBootstrap telemetry = TelemetryBootstrap.get();
        Span groupSpan = telemetry.startStepParallelSpan(stepIndex, step.getName());

        Map<String, StepRun> finished = new LinkedHashMap<>();
        try (Scope groupScope = groupSpan.makeCurrent();
                ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<StepRun>> futures = new LinkedHashMap<>();
            for (Map.Entry<String, OperationStepSpec> track : tracks.entrySet()) {
                String name = track.getKey();
                DurableScope trackScope = scope != null ? scope.track(name) : null;
                Callable<StepRun> work = () -> {
                    StepRun trackRun = new StepRun(parameters);
                    // A track reads the outcomes of the steps before the group, as a copy.
                    StepOutcomes.inherit(trackRun.runtimeParameters(), parameters);
                    outputs.forEach(trackRun.stepContext()::storeStepOutput);
                    Span trackSpan = TelemetryBootstrap.get().startStepTrackSpan(name);
                    try (Scope trackOtel = trackSpan.makeCurrent()) {
                        Callable<StepRun> steps = () -> Executions.runSteps(
                                ExecutionTree.compile(stepsOf(name, track.getValue()), registry),
                                trackRun, nodeRunner);
                        return ExecutionInputs.within(inputs, NodePath.of(name),
                                trackScope != null ? () -> DurableScope.within(trackScope, steps)
                                        : steps);
                    } catch (Exception e) {
                        TelemetryBootstrap.recordError(trackSpan, e);
                        throw e;
                    } finally {
                        TelemetryBootstrap.endSpan(trackSpan);
                    }
                };
                futures.put(name, threads.submit(Context.current().wrap(work)));
            }
            RuntimeException failure = null;
            for (Map.Entry<String, Future<StepRun>> future : futures.entrySet()) {
                try {
                    finished.put(future.getKey(), future.getValue().get());
                } catch (ExecutionException e) {
                    if (failure == null) {
                        failure = unwrap(e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (failure == null) {
                        failure = new IllegalStateException("Step '" + step.getName()
                                + "' was interrupted while its tracks ran", e);
                    }
                }
            }
            if (failure != null) {
                TelemetryBootstrap.recordError(groupSpan, failure);
                finished.values().forEach(ParallelTracks::release);
                throw failure;
            }
        } finally {
            TelemetryBootstrap.endSpan(groupSpan);
        }

        ObjectNode journaled = IkanosMappers.json().createObjectNode();
        ObjectNode trackOutputs = journaled.putObject(OUTPUTS);
        ObjectNode trackOutcomes = journaled.putObject(OUTCOMES);
        for (Map.Entry<String, StepRun> track : finished.entrySet()) {
            String name = track.getKey();
            StepRun trackRun = track.getValue();
            JsonNode output = outputOf(stepsOf(name, tracks.get(name)), trackRun);
            if (output != null) {
                trackOutputs.set(name, output);
            }
            String outcome = outcomeOf(stepsOf(name, tracks.get(name)), trackRun);
            if (outcome != null) {
                trackOutcomes.put(name, outcome);
            }
            release(trackRun);
        }
        restore(step.getName(), journaled, run);
        return journaled;
    }

    /**
     * Records the outputs and outcomes of a {@code parallel} step's journaled output in
     * {@code run}: each track's under its name, and the outputs together under the step's.
     */
    static void restore(String stepName, JsonNode journaled, StepRun run) {
        if (journaled == null || !journaled.isObject()) {
            return;
        }
        JsonNode outputs = journaled.path(OUTPUTS);
        outputs.properties().forEach(track -> run.putOutput(track.getKey(), track.getValue()));
        journaled.path(OUTCOMES).properties().forEach(track -> StepOutcomes.put(
                run.runtimeParameters(), track.getKey(), track.getValue().asString()));
        if (stepName != null && outputs.isObject()) {
            run.putOutput(stepName, outputs);
        }
    }

    /** The steps of a track: its own step, or the steps of its {@code sequence}. */
    static Map<String, OperationStepSpec> stepsOf(String name, OperationStepSpec track) {
        if (track instanceof OperationStepSequenceSpec sequence) {
            return sequence.getSteps();
        }
        Map<String, OperationStepSpec> one = new LinkedHashMap<>();
        one.put(name, track);
        return one;
    }

    /** The output of a track: that of its last step that has one, or {@code null}. */
    private static JsonNode outputOf(Map<String, OperationStepSpec> steps, StepRun run) {
        List<String> names = new ArrayList<>(steps.keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
            JsonNode output = run.stepContext().getStepOutput(names.get(i));
            if (output != null && !output.isNull() && !output.isMissingNode()) {
                return output;
            }
        }
        return null;
    }

    /** The outcome of a track: that of its last call step that ran, or {@code null}. */
    private static String outcomeOf(Map<String, OperationStepSpec> steps, StepRun run) {
        List<String> names = new ArrayList<>(steps.keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
            String outcome = StepOutcomes.get(run.runtimeParameters(), names.get(i));
            if (outcome != null) {
                return outcome;
            }
        }
        return null;
    }

    private static void release(StepRun run) {
        if (run.lastResult() != null) {
            run.lastResult().release();
            run.setLastResult(null);
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause.getMessage(), cause);
    }
}
