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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.OperationStepSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** How a step's {@code onlyIf} guard decides whether it runs, in both durability modes. */
public class StepGuardExecutionTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final ExecutionId ROOT = new ExecutionId("task-1");

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** Records each step it runs; a step's output is {@code {"step": name, "ok": <ok>}}. */
    private static final class RecordingRunner implements NodeRunner {
        final List<String> invoked = new ArrayList<>();
        final Set<String> crashOn = new HashSet<>();
        final Map<String, Boolean> ok = new LinkedHashMap<>();

        @Override
        public Object run(StepNode node, StepRun run) {
            String name = node.step().getName();
            invoked.add(name);
            if (crashOn.remove(name)) {
                throw new Crash();
            }
            JsonNode output = JSON.createObjectNode().put("step", name)
                    .put("ok", ok.getOrDefault(name, true));
            run.stepContext().storeStepOutput(name, output);
            run.runtimeParameters().put(name, JSON.convertValue(output, Object.class));
            return output;
        }
    }

    /** Steps by name; a name with {@code ?} carries the guard after it, as in {@code b?a.ok}. */
    private static ExecutionTree tree(String... steps) {
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (String entry : steps) {
            String[] parts = entry.split("\\?", 2);
            OperationStepCallSpec step = new OperationStepCallSpec(parts[0], "port." + parts[0]);
            if (parts.length == 2) {
                step.setOnlyIf(parts[1]);
            }
            map.put(parts[0], step);
        }
        return ExecutionTree.compile(map, null);
    }

    @Test
    void runStepsShouldSkipAStepWhoseGuardDoesNotHoldAndLeaveItWithoutOutput() {
        RecordingRunner nodes = new RecordingRunner();
        nodes.ok.put("check", false);

        StepRun run = Executions.runSteps(
                tree("check", "alert?check.ok", "log"), new StepRun(null), nodes);

        assertEquals(List.of("check", "log"), nodes.invoked);
        assertNull(run.stepContext().getStepOutput("alert"));
        assertFalse(run.runtimeParameters().containsKey("alert"));
    }

    @Test
    void runStepsShouldRecordASkippedStepAsAStepSkipSpan() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(spans))
                .build());
        try {
            Executions.runSteps(tree("alert?absent"), new StepRun(null), new RecordingRunner());

            SpanData skip = spans.getFinishedSpanItems().stream()
                    .filter(span -> "step.skip".equals(span.getName()))
                    .findFirst().orElseThrow();
            assertEquals("alert", skip.getAttributes().get(TelemetryBootstrap.ATTR_STEP_NAME));
            assertEquals(0L, skip.getAttributes().get(TelemetryBootstrap.ATTR_STEP_INDEX));
        } finally {
            TelemetryBootstrap.reset();
        }
    }

    @Test
    void runStepsShouldRunAStepWhoseGuardHolds() {
        RecordingRunner nodes = new RecordingRunner();

        Executions.runSteps(tree("check", "alert?check.ok == true"), new StepRun(null), nodes);

        assertEquals(List.of("check", "alert"), nodes.invoked);
    }

    @Test
    void runStepsShouldReadTheInputsOfTheRunInAGuard() {
        RecordingRunner nodes = new RecordingRunner();

        Executions.runSteps(tree("alert?order.status == 'overdue'"),
                new StepRun(Map.of("order", Map.of("status", "paid"))), nodes);

        assertTrue(nodes.invoked.isEmpty());
    }

    @Test
    void runStepsShouldNotAskTheQuestionOfAnElicitStepItSkips() {
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        OperationStepElicitSpec confirm = new OperationStepElicitSpec("confirm", "Delete it?",
                Map.of("ok", Map.of("type", "boolean")));
        confirm.setOnlyIf("risky");
        steps.put("confirm", confirm);
        steps.put("delete", new OperationStepCallSpec("delete", "port.delete"));
        RecordingRunner nodes = new RecordingRunner();

        Executions.runSteps(ExecutionTree.compile(steps, null),
                new StepRun(Map.of("risky", false)), nodes);

        assertEquals(List.of("delete"), nodes.invoked);
    }

    @Test
    void runStepsShouldFailRatherThanSkipWhenAGuardReadsClaimsTheRunDoesNotHave() {
        // A task resumed after a restart has no caller claims: skipping the step would let it
        // end as a success without the step.
        RecordingRunner nodes = new RecordingRunner();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Executions.runSteps(tree("audit?auth.claims.sub"), new StepRun(null),
                        nodes));

        assertTrue(error.getMessage().contains("caller's claims"), error.getMessage());
        assertTrue(nodes.invoked.isEmpty());
    }

    @Test
    void runStepsShouldFailTheRunWhenAGuardIsNotABoolean() {
        RecordingRunner nodes = new RecordingRunner();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Executions.runSteps(tree("alert?'yes'"), new StepRun(null), nodes));

        assertTrue(error.getMessage().contains("step 'alert'"), error.getMessage());
        assertTrue(nodes.invoked.isEmpty());
    }

    @Test
    void aResumedRunShouldSkipTheSameStepsWithoutRunningRecordedOnesAgain() throws Exception {
        RecordingRunner first = new RecordingRunner();
        first.ok.put("check", false);
        first.crashOn.add("log");
        ExecutionTree tree = tree("check", "alert?check.ok", "log");
        assertThrows(Crash.class, () -> DurableScope.with(runner, ROOT,
                () -> Executions.runSteps(tree, new StepRun(null), first)));

        RecordingRunner second = new RecordingRunner();
        StepRun resumed = DurableScope.with(runner, ROOT,
                () -> Executions.runSteps(tree, new StepRun(null), second));

        assertEquals(List.of("check", "log"), first.invoked);
        assertEquals(List.of("log"), second.invoked,
                "The recorded check must not run again and the skipped alert stays skipped");
        assertNull(resumed.stepContext().getStepOutput("alert"));
    }
}
