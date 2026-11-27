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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepParallelSpec;
import io.ikanos.spec.util.OperationStepSequenceSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import tools.jackson.databind.JsonNode;

/** The {@code parallel} step in both durability modes (#1169). */
public class ParallelTracksTest {

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Runs a {@code parallel} step through {@link ParallelTracks}, as the engine does, and any
     * other step by recording {@code {"step": name}}.
     */
    private static class Nodes implements NodeRunner {
        final List<String> invoked = new CopyOnWriteArrayList<>();
        final Set<String> crashOn = ConcurrentHashMap.newKeySet();
        final Set<String> failOn = ConcurrentHashMap.newKeySet();
        final Map<String, Set<String>> seen = new ConcurrentHashMap<>();

        @Override
        public Object run(StepNode node, StepRun run) {
            if (node.step() instanceof OperationStepParallelSpec parallel) {
                return ParallelTracks.run(parallel, node.index(), run, null, this);
            }
            String name = node.step().getName();
            invoked.add(name);
            seen.put(name, Set.copyOf(run.runtimeParameters().keySet()));
            before(name);
            if (crashOn.remove(name)) {
                throw new Crash();
            }
            if (failOn.contains(name)) {
                throw new IllegalArgumentException("Port refused " + name);
            }
            JsonNode output = IkanosMappers.json().createObjectNode().put("step", name);
            run.putOutput(name, output);
            StepOutcomes.put(run.runtimeParameters(), name, StepOutcomes.SUCCESS);
            return output;
        }

        void before(String name) {
        }
    }

    private static OperationStepCallSpec call(String name) {
        return new OperationStepCallSpec(name, "port." + name);
    }

    private static OperationStepSequenceSpec sequence(String name, OperationStepSpec... steps) {
        OperationStepSequenceSpec sequence = new OperationStepSequenceSpec(name);
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (OperationStepSpec step : steps) {
            map.put(step.getName(), step);
        }
        sequence.setSteps(map);
        return sequence;
    }

    private static OperationStepParallelSpec parallel(String name, OperationStepSpec... tracks) {
        OperationStepParallelSpec parallel = new OperationStepParallelSpec(name);
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (OperationStepSpec track : tracks) {
            map.put(track.getName(), track);
        }
        parallel.setTracks(map);
        return parallel;
    }

    private static ExecutionTree tree(OperationStepSpec... steps) {
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (OperationStepSpec step : steps) {
            map.put(step.getName(), step);
        }
        return ExecutionTree.compile(map, null);
    }

    @Test
    void tracksShouldRunAtTheSameTime() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Nodes nodes = new Nodes() {
            @Override
            void before(String name) {
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS),
                            "each track waits for the other: they must run concurrently");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        StepRun run = Executions.runSteps(tree(parallel("gather", call("a"), call("b"))),
                new StepRun(null), nodes);

        assertEquals(Set.of("a", "b"), Set.copyOf(nodes.invoked));
        assertEquals("a", run.stepContext().getStepOutput("a").path("step").asString());
        assertEquals("b", run.stepContext().getStepOutput("gather").path("b").path("step")
                .asString());
        assertEquals("success", StepOutcomes.get(run.runtimeParameters(), "b"));
    }

    @Test
    void aTrackShouldSeeTheStepsBeforeItsGroupButNotTheOtherTracks() {
        Nodes nodes = new Nodes();

        Executions.runSteps(tree(call("first"), parallel("gather", call("a"),
                sequence("b", call("b1"), call("b2")))), new StepRun(null), nodes);

        assertTrue(nodes.seen.get("a").contains("first"));
        assertFalse(nodes.seen.get("a").contains("b1"));
        assertFalse(nodes.seen.get("b1").contains("a"));
        assertTrue(nodes.seen.get("b2").contains("b1"), "a sequence sees its own earlier steps");
    }

    @Test
    void aSequenceTrackShouldGiveTheOutputOfItsLastStep() {
        StepRun run = Executions.runSteps(tree(parallel("gather", call("a"),
                sequence("b", call("b1"), call("b2")))), new StepRun(null), new Nodes());

        assertEquals("b2", run.stepContext().getStepOutput("b").path("step").asString());
    }

    @Test
    void aFailingTrackShouldFailTheGroupAndTheStepsAfter() {
        Nodes nodes = new Nodes();
        nodes.failOn.add("b");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Executions.runSteps(tree(parallel("gather", call("a"), call("b")),
                        call("after")), new StepRun(null), nodes));

        assertTrue(error.getMessage().contains("Port refused b"), error.getMessage());
        assertFalse(nodes.invoked.contains("after"));
    }

    @Test
    void aResumedGroupShouldRunOnlyTheTracksThatDidNotFinish() throws Exception {
        ExecutionTree tree = tree(parallel("gather", call("a"), sequence("b", call("b1"),
                call("b2"))), call("after"));
        Nodes first = new Nodes();
        first.crashOn.add("b2");
        assertThrows(Crash.class, () -> DurableScope.with(runner, new ExecutionId("task-1"),
                () -> Executions.runSteps(tree, new StepRun(null), first)));

        Nodes second = new Nodes();
        StepRun resumed = DurableScope.with(runner, new ExecutionId("task-1"),
                () -> Executions.runSteps(tree, new StepRun(null), second));

        assertEquals(List.of("b2", "after"), second.invoked,
                "a, and b1 before the crash, were journaled and are not run again");
        assertEquals("a", resumed.stepContext().getStepOutput("a").path("step").asString());
        assertEquals("success", StepOutcomes.get(resumed.runtimeParameters(), "a"));
    }

    @Test
    void aGroupShouldRecordASpanWithASpanPerTrack() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(spans)).build());
        try {
            Executions.runSteps(tree(parallel("gather", call("a"), call("b"))),
                    new StepRun(null), new Nodes());

            SpanData group = spans.getFinishedSpanItems().stream()
                    .filter(span -> "step.parallel".equals(span.getName())).findFirst()
                    .orElseThrow();
            List<SpanData> tracks = spans.getFinishedSpanItems().stream()
                    .filter(span -> "step.track".equals(span.getName())).toList();
            assertEquals(2, tracks.size());
            tracks.forEach(track -> assertEquals(group.getSpanId(), track.getParentSpanId()));
        } finally {
            TelemetryBootstrap.reset();
        }
    }
}
