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
import java.util.logging.Level;
import org.restlet.Context;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepForEachSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;

/**
 * Runs the {@code do} of a {@code forEach} step once for each item of its list, in order
 * (#1170).
 *
 * <p>Each item runs as a step run of its own, from a copy of the parameters, outcomes and step
 * outputs the run held when the step started, with the item under {@code as} ({@code item} by
 * default): an item sees the steps before the {@code forEach} step, never another item's. Its
 * output is the output of {@code do}, or of the last step of its {@code sequence} that has one;
 * the step's output is the list of those outputs, in item order ({@code null} for an item that
 * gave none). The first item that fails fails the step, and the items after it do not run.</p>
 *
 * <p>In a durable run, each item journals its steps under a scope named after its position, so a
 * restart restores the items that finished and resumes from the first that did not; the step's
 * journaled output is the list.</p>
 */
public final class ForEachRunner {

    private ForEachRunner() {
    }

    /**
     * Runs {@code step} and records its output, the list of each item's output, under its name.
     *
     * @return that list, to journal
     * @throws RuntimeException the failure of the first item that failed
     */
    public static JsonNode run(OperationStepForEachSpec step, int stepIndex, StepRun run,
            StepHandlerRegistry registry, NodeRunner nodeRunner) {
        if (run.lastResult() != null) {
            run.lastResult().release();
            run.setLastResult(null);
        }
        Map<String, Object> parameters = new LinkedHashMap<>(run.runtimeParameters());
        Map<String, JsonNode> outputs = new LinkedHashMap<>(
                run.stepContext().getAllStepOutputs());
        List<Object> items = itemsOf(step, StepGuard.parametersOf(run));
        Integer limit = limitOf(step, StepGuard.parametersOf(run));
        if (limit != null && items.size() > limit) {
            // As SQL's LIMIT: the first items run, the rest are skipped and the call goes on.
            Context.getCurrentLogger().log(Level.WARNING, "Step '" + step.getName()
                    + "' ran its first " + limit + " items and skipped " + (items.size() - limit)
                    + ": its limit is " + limit);
            items = items.subList(0, limit);
        }
        // One step, or the steps of a sequence
        Map<String, OperationStepSpec> body = ParallelTracks.stepsOf(step.getDo().getName(),
                step.getDo());
        String itemName = step.itemName();
        ExecutionInputs.Snapshot inputs = ExecutionInputs.capture();
        DurableScope scope = DurableScope.current();
        TelemetryBootstrap telemetry = TelemetryBootstrap.get();
        Span stepSpan = telemetry.startStepForEachSpan(stepIndex, step.getName(), items.size());
        ArrayNode results = IkanosMappers.json().createArrayNode();
        try (Scope stepScope = stepSpan.makeCurrent()) {
            for (int i = 0; i < items.size(); i++) {
                String position = "item-" + i;
                StepRun itemRun = new StepRun(parameters);
                StepOutcomes.inherit(itemRun.runtimeParameters(), parameters);
                outputs.forEach(itemRun.stepContext()::storeStepOutput);
                itemRun.runtimeParameters().put(itemName, items.get(i));
                String alias = itemName.replace('-', '_');
                if (!alias.equals(itemName)) {
                    itemRun.runtimeParameters().putIfAbsent(alias, items.get(i));
                }
                // The guard of a sequence do, for this item: a single step's runs with it.
                DurableScope itemScope = scope != null ? scope.track(position) : null;
                Span itemSpan = TelemetryBootstrap.get().startStepItemSpan(i);
                try (Scope itemOtel = itemSpan.makeCurrent()) {
                    if (step.getDo() instanceof io.ikanos.spec.util.OperationStepSequenceSpec seq
                            && !StepGuard.holds(step.getDo().getName(), seq.getOnlyIf(),
                                    StepGuard.parametersOf(itemRun))) {
                        // Skipped for this item: a step.skip span under the item's, and no
                        // output.
                        TelemetryBootstrap.get().recordStepSkipped(stepIndex,
                                step.getDo().getName());
                        results.add(NullNode.getInstance());
                        continue;
                    }
                    Callable<StepRun> steps = () -> Executions.runSteps(
                            ExecutionTree.compile(body, registry), itemRun, nodeRunner);
                    ExecutionInputs.within(inputs, NodePath.of(position),
                            itemScope != null ? () -> DurableScope.within(itemScope, steps)
                                    : steps);
                } catch (RuntimeException e) {
                    TelemetryBootstrap.recordError(itemSpan, e);
                    throw e;
                } catch (Exception e) {
                    TelemetryBootstrap.recordError(itemSpan, e);
                    throw new IllegalStateException(e.getMessage(), e);
                } finally {
                    TelemetryBootstrap.endSpan(itemSpan);
                    if (itemRun.lastResult() != null) {
                        itemRun.lastResult().release();
                        itemRun.setLastResult(null);
                    }
                }
                JsonNode output = ParallelTracks.outputOf(body, itemRun);
                results.add(output != null ? output : NullNode.getInstance());
            }
        } catch (RuntimeException e) {
            TelemetryBootstrap.recordError(stepSpan, e);
            throw e;
        } finally {
            TelemetryBootstrap.endSpan(stepSpan);
        }
        run.putOutput(step.getName(), results);
        return results;
    }

    /** The items: a list, a JSON array, or nothing for an absent value. */
    static List<Object> itemsOf(OperationStepForEachSpec step, Map<String, Object> parameters) {
        String label = "The items of step '" + step.getName() + "'";
        Object value = StepGuard.value(label, step.getItems(), parameters);
        if (value == null || value instanceof NullNode) {
            return List.of();
        }
        if (value instanceof JsonNode node && !node.isArray()) {
            throw new IllegalArgumentException(label + " must be a list, not " + node.getNodeType()
                    + ".");
        }
        if (value instanceof JsonNode node) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(
                    IkanosMappers.json().convertValue(item, Object.class)));
            return items;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            iterable.forEach(items::add);
            return items;
        }
        throw new IllegalArgumentException(label + " must be a list, not "
                + value.getClass().getSimpleName() + ".");
    }

    /** The limit: a number, the value of the input it names, or {@code null} for none. */
    static Integer limitOf(OperationStepForEachSpec step, Map<String, Object> parameters) {
        Object limit = step.getLimit();
        if (limit instanceof String name) {
            limit = StepGuard.value("The limit of step '" + step.getName() + "'", name,
                    parameters);
            if (limit instanceof JsonNode node) {
                limit = node.isNumber() ? node.numberValue() : node.asString(null);
            }
            if (limit instanceof String text) {
                try {
                    limit = Long.parseLong(text.strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("The limit of step '" + step.getName()
                            + "' must be a whole number, not '" + text + "'.", e);
                }
            }
        }
        if (limit == null) {
            return null;
        }
        if (!(limit instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() < 1) {
            throw new IllegalArgumentException("The limit of step '" + step.getName()
                    + "' must be a whole number of at least 1, not " + limit + ".");
        }
        return (int) Math.min(number.longValue(), Integer.MAX_VALUE);
    }

}
