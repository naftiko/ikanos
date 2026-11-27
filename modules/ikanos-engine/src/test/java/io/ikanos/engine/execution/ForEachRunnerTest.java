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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.engine.LogCapture;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepForEachSpec;
import io.ikanos.spec.util.OperationStepSequenceSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import tools.jackson.databind.JsonNode;

/** The {@code forEach} step in both durability modes (#1170). */
public class ForEachRunnerTest {

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** Runs a forEach through {@link ForEachRunner}, and any other step as {@code name:item}. */
    private static class Nodes implements NodeRunner {
        final List<String> invoked = new CopyOnWriteArrayList<>();
        final Set<String> crashOn = ConcurrentHashMap.newKeySet();
        final Set<String> failOn = ConcurrentHashMap.newKeySet();

        @Override
        public Object run(StepNode node, StepRun run) {
            if (node.step() instanceof OperationStepForEachSpec forEach) {
                return ForEachRunner.run(forEach, node.index(), run, null, this);
            }
            String name = node.step().getName();
            Object item = run.runtimeParameters().get("pr");
            String call = name + ":" + item;
            invoked.add(call);
            if (crashOn.remove(call)) {
                throw new Crash();
            }
            if (failOn.contains(call)) {
                throw new IllegalArgumentException("Port refused " + call);
            }
            JsonNode output = IkanosMappers.json().createObjectNode().put("call", call);
            run.putOutput(name, output);
            return output;
        }
    }

    private static OperationStepForEachSpec forEach(String items, OperationStepSpec body) {
        OperationStepForEachSpec step = new OperationStepForEachSpec("issues");
        step.setItems(items);
        step.setAs("pr");
        step.setDo(body);
        return step;
    }

    private static ExecutionTree tree(OperationStepSpec... steps) {
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (OperationStepSpec step : steps) {
            map.put(step.getName(), step);
        }
        return ExecutionTree.compile(map, null);
    }

    private static StepRun run(Map<String, Object> parameters, OperationStepSpec... steps) {
        return Executions.runSteps(tree(steps), new StepRun(parameters), new Nodes());
    }

    @Test
    void aForEachShouldRunItsStepForEachItemInOrderAndListTheOutputs() {
        Nodes nodes = new Nodes();

        StepRun run = Executions.runSteps(tree(forEach("prs",
                new OperationStepCallSpec(null, "github.get-issue"))),
                new StepRun(Map.of("prs", List.of(1, 2, 3))), nodes);

        assertEquals(List.of("do:1", "do:2", "do:3"), nodes.invoked);
        JsonNode issues = run.stepContext().getStepOutput("issues");
        assertEquals(3, issues.size());
        assertEquals("do:2", issues.get(1).path("call").asString());
    }

    @Test
    void aForEachShouldReadItsItemsFromACelExpression() {
        Nodes nodes = new Nodes();

        Executions.runSteps(tree(forEach("prs.filter(n, n > 1)",
                new OperationStepCallSpec(null, "github.get-issue"))),
                new StepRun(Map.of("prs", List.of(1, 2, 3))), nodes);

        assertEquals(List.of("do:2", "do:3"), nodes.invoked);
    }

    @Test
    void aSequenceBodyShouldGiveTheOutputOfItsLastStep() {
        OperationStepSequenceSpec body = new OperationStepSequenceSpec("each");
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("get", new OperationStepCallSpec("get", "github.get-issue"));
        steps.put("flag", new OperationStepCallSpec("flag", "notion.flag"));
        body.setSteps(steps);

        StepRun run = run(Map.of("prs", List.of(7)), forEach("prs", body));

        assertEquals("flag:7", run.stepContext().getStepOutput("issues").get(0).path("call")
                .asString());
    }

    @Test
    void aSequenceBodyShouldBeSkippedForTheItemsItsGuardDoesNotHoldFor() {
        OperationStepSequenceSpec body = new OperationStepSequenceSpec("each");
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("get", new OperationStepCallSpec("get", "github.get-issue"));
        body.setSteps(steps);
        body.setOnlyIf("pr > 1");
        Nodes nodes = new Nodes();

        StepRun run = Executions.runSteps(tree(forEach("prs", body)),
                new StepRun(Map.of("prs", List.of(1, 2))), nodes);

        assertEquals(List.of("get:2"), nodes.invoked);
        assertTrue(run.stepContext().getStepOutput("issues").get(0).isNull());
    }

    @Test
    void anAbsentListShouldRunNoItem() {
        StepRun run = run(Map.of(), forEach("prs",
                new OperationStepCallSpec(null, "github.get-issue")));

        assertEquals(0, run.stepContext().getStepOutput("issues").size());
    }

    @Test
    void aValueThatIsNotAListShouldFailTheStep() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> run(Map.of("prs", "nope"), forEach("prs",
                        new OperationStepCallSpec(null, "github.get-issue"))));

        assertTrue(error.getMessage().contains("must be a list"), error.getMessage());
    }

    @Test
    void aFailingItemShouldFailTheStepAndSkipTheItemsAfterIt() {
        Nodes nodes = new Nodes();
        nodes.failOn.add("do:2");

        assertThrows(IllegalArgumentException.class, () -> Executions.runSteps(
                tree(forEach("prs", new OperationStepCallSpec(null, "github.get-issue"))),
                new StepRun(Map.of("prs", List.of(1, 2, 3))), nodes));

        assertEquals(List.of("do:1", "do:2"), nodes.invoked);
    }

    @Test
    void aResumedForEachShouldRunOnlyTheItemsThatDidNotFinish() throws Exception {
        ExecutionTree tree = tree(forEach("prs",
                new OperationStepCallSpec(null, "github.get-issue")));
        Nodes first = new Nodes();
        first.crashOn.add("do:2");
        assertThrows(Crash.class, () -> DurableScope.with(runner, new ExecutionId("task-1"),
                () -> Executions.runSteps(tree, new StepRun(Map.of("prs", List.of(1, 2, 3))),
                        first)));

        Nodes second = new Nodes();
        StepRun resumed = DurableScope.with(runner, new ExecutionId("task-1"),
                () -> Executions.runSteps(tree, new StepRun(Map.of("prs", List.of(1, 2, 3))),
                        second));

        assertEquals(List.of("do:2", "do:3"), second.invoked,
                "the first item was journaled and is not run again");
        assertEquals(3, resumed.stepContext().getStepOutput("issues").size());
    }

    @Test
    void aForEachShouldRecordASpanWithASpanPerItem() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(spans)).build());
        try {
            run(Map.of("prs", List.of(1, 2)), forEach("prs",
                    new OperationStepCallSpec(null, "github.get-issue")));

            SpanData step = spans.getFinishedSpanItems().stream()
                    .filter(span -> "step.forEach".equals(span.getName())).findFirst()
                    .orElseThrow();
            assertEquals(2L, step.getAttributes().get(TelemetryBootstrap.ATTR_STEP_ITEMS));
            List<SpanData> items = spans.getFinishedSpanItems().stream()
                    .filter(span -> "step.item".equals(span.getName())).toList();
            assertEquals(2, items.size());
            items.forEach(item -> assertEquals(step.getSpanId(), item.getParentSpanId()));
            assertFalse(items.isEmpty());
        } finally {
            TelemetryBootstrap.reset();
        }
    }

    private static OperationStepForEachSpec limited(Object limit) {
        OperationStepForEachSpec step = forEach("prs",
                new OperationStepCallSpec(null, "github.get-issue"));
        step.setLimit(limit);
        return step;
    }

    @Test
    void limitOfShouldReadANumber() {
        assertEquals(2, ForEachRunner.limitOf(limited(2), Map.of()));
    }

    @Test
    void limitOfShouldReadTheInputItNames() {
        assertEquals(5, ForEachRunner.limitOf(limited("max"), Map.of("max", 5L)));
    }

    @Test
    void limitOfShouldGiveNoLimitWhenTheInputItNamesIsAbsent() {
        assertNull(ForEachRunner.limitOf(limited("max"), Map.of()));
    }

    @Test
    void limitOfShouldRefuseALimitBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> ForEachRunner.limitOf(limited("max"), Map.of("max", 0)));
    }

    @Test
    void aLimitShouldLogAWarningNamingTheStepAndTheSkippedItems() {
        OperationStepForEachSpec step = limited(2);
        Nodes nodes = new Nodes();

        try (LogCapture logs = new LogCapture()) {
            Executions.runSteps(tree(step), new StepRun(Map.of("prs", List.of(1, 2, 3, 4, 5))),
                    nodes);

            assertEquals(List.of("do:1", "do:2"), nodes.invoked);
            assertTrue(logs.messages().stream().anyMatch(message -> message.contains("issues")
                    && message.contains("skipped 3")), logs.messages().toString());
        }
    }
}
