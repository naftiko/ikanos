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
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepChoiceSpec;
import io.ikanos.spec.util.OperationStepSequenceSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import tools.jackson.databind.JsonNode;

/** The {@code choice} step in both durability modes (#1171). */
public class ChoiceRunnerTest {

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** Runs a choice through {@link ChoiceRunner}, and any other step by recording its name. */
    private static class Nodes implements NodeRunner {
        final List<String> invoked = new CopyOnWriteArrayList<>();
        final Set<String> crashOn = ConcurrentHashMap.newKeySet();

        @Override
        public Object run(StepNode node, StepRun run) {
            if (node.step() instanceof OperationStepChoiceSpec choice) {
                return ChoiceRunner.run(choice, node.index(), run, null, this);
            }
            String name = node.step().getName();
            invoked.add(name);
            if (crashOn.remove(name)) {
                throw new Crash();
            }
            JsonNode output = IkanosMappers.json().createObjectNode().put("step", name);
            run.putOutput(name, output);
            StepOutcomes.put(run.runtimeParameters(), name, StepOutcomes.SUCCESS);
            return output;
        }
    }

    private static OperationStepCallSpec call(String name) {
        return new OperationStepCallSpec(name, "port." + name);
    }

    private static OperationStepChoiceSpec choice(String condition, OperationStepSpec then,
            OperationStepSpec otherwise) {
        OperationStepChoiceSpec choice = new OperationStepChoiceSpec("route");
        choice.setIf(condition);
        choice.setThen(then);
        choice.setElse(otherwise);
        return choice;
    }

    private static ExecutionTree tree(OperationStepSpec... steps) {
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (OperationStepSpec step : steps) {
            map.put(step.getName(), step);
        }
        return ExecutionTree.compile(map, null);
    }

    @Test
    void aChoiceShouldRunThenWhenItsConditionHolds() {
        Nodes nodes = new Nodes();

        StepRun run = Executions.runSteps(tree(choice("bug", call("incident"), call("ask"))),
                new StepRun(Map.of("bug", true)), nodes);

        assertEquals(List.of("incident"), nodes.invoked);
        assertEquals("incident", run.stepContext().getStepOutput("route").path("step")
                .asString());
        assertEquals("success", StepOutcomes.get(run.runtimeParameters(), "route"));
    }

    @Test
    void aChoiceShouldRunElseWhenItsConditionDoesNotHold() {
        Nodes nodes = new Nodes();

        Executions.runSteps(tree(choice("bug", call("incident"), call("ask"))),
                new StepRun(Map.of("bug", false)), nodes);

        assertEquals(List.of("ask"), nodes.invoked);
    }

    @Test
    void aChoiceWithoutElseShouldRunNothingAndHaveNoOutput() {
        Nodes nodes = new Nodes();

        StepRun run = Executions.runSteps(tree(choice("bug", call("incident"), null),
                call("after")), new StepRun(Map.of()), nodes);

        assertEquals(List.of("after"), nodes.invoked);
        assertNull(run.stepContext().getStepOutput("route"));
    }

    @Test
    void aSequencePathShouldGiveTheOutputOfItsLastStep() {
        OperationStepSequenceSpec path = new OperationStepSequenceSpec("both");
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("page", call("page"));
        steps.put("notify", call("notify"));
        path.setSteps(steps);

        StepRun run = Executions.runSteps(tree(choice("bug", path, null)),
                new StepRun(Map.of("bug", true)), new Nodes());

        assertEquals("notify", run.stepContext().getStepOutput("route").path("step")
                .asString());
    }

    @Test
    void aConditionThatIsNotABooleanShouldFailTheStep() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Executions.runSteps(tree(choice("'yes'", call("a"), null)),
                        new StepRun(null), new Nodes()));

        assertTrue(error.getMessage().startsWith("The if of step 'route'"), error.getMessage());
    }

    @Test
    void aResumedRunShouldTakeTheSamePathWithoutRunningItsFinishedSteps() throws Exception {
        OperationStepSequenceSpec path = new OperationStepSequenceSpec("both");
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("page", call("page"));
        steps.put("notify", call("notify"));
        path.setSteps(steps);
        ExecutionTree tree = tree(choice("bug", path, call("ask")), call("after"));
        Nodes first = new Nodes();
        first.crashOn.add("notify");
        assertThrows(Crash.class, () -> DurableScope.with(runner, new ExecutionId("task-1"),
                () -> Executions.runSteps(tree, new StepRun(Map.of("bug", true)), first)));

        Nodes second = new Nodes();
        StepRun resumed = DurableScope.with(runner, new ExecutionId("task-1"),
                () -> Executions.runSteps(tree, new StepRun(Map.of("bug", true)), second));

        assertEquals(List.of("notify", "after"), second.invoked,
                "page was journaled and is not run again");
        assertEquals("notify", resumed.stepContext().getStepOutput("route").path("step")
                .asString());
    }

    @Test
    void aChoiceShouldRecordASpanNamingThePathItTook() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(spans)).build());
        try {
            Executions.runSteps(tree(choice("bug", call("incident"), call("ask"))),
                    new StepRun(Map.of("bug", false)), new Nodes());

            SpanData span = spans.getFinishedSpanItems().stream()
                    .filter(s -> "step.choice".equals(s.getName())).findFirst().orElseThrow();
            assertEquals("else", span.getAttributes().get(TelemetryBootstrap.ATTR_STEP_PATH));
        } finally {
            TelemetryBootstrap.reset();
        }
    }
}
