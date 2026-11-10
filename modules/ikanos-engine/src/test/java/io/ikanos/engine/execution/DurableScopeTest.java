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

import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Step runs inside a {@link DurableScope}: journaled step by step, restored rather than run again,
 * and keyed by execution IDs that follow the call structure.
 */
public class DurableScopeTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final ExecutionId ROOT = new ExecutionId("task-1");

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    /** Simulates a process dying mid-step: an Error is not an action failure, nothing journals it. */
    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static ExecutionTree tree(String... names) {
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        for (String name : names) {
            steps.put(name, new OperationStepCallSpec(name, "port." + name));
        }
        return ExecutionTree.compile(steps, null);
    }

    /** A node runner recording each step's output as the real one does, and counting calls. */
    private static final class CountingRunner implements NodeRunner {
        final List<String> invoked = new ArrayList<>();
        final Set<String> crashOn = new HashSet<>();
        final Set<String> failOn = new HashSet<>();
        final Map<String, ExecutionTree> calls = new LinkedHashMap<>();

        @Override
        public Object run(StepNode node, StepRun run) {
            String name = node.step().getName();
            invoked.add(name);
            if (crashOn.remove(name)) {
                throw new Crash();
            }
            if (failOn.contains(name)) {
                throw new IllegalArgumentException("Port refused " + name);
            }
            if (calls.containsKey(name)) {
                // A step calling a flow: its steps run as a nested step run.
                Executions.runSteps(calls.get(name), new StepRun(null), this);
            }
            JsonNode output = JSON.createObjectNode().put("step", name);
            run.stepContext().storeStepOutput(name, output);
            run.runtimeParameters().put(name, JSON.convertValue(output, Object.class));
            return output;
        }
    }

    private StepRun runInScope(ExecutionTree tree, CountingRunner nodes) throws Exception {
        return DurableScope.with(runner, ROOT,
                () -> Executions.runSteps(tree, new StepRun(null), nodes));
    }

    @Test
    void stepsShouldRunDirectlyOutsideAScope() {
        CountingRunner nodes = new CountingRunner();

        StepRun run = Executions.runSteps(tree("book-slot"), new StepRun(null), nodes);

        assertEquals(List.of("book-slot"), nodes.invoked);
        assertNull(DurableScope.current());
        assertTrue(runner.status(new ExecutionId("task-1/steps")).isEmpty());
        assertEquals("book-slot", run.stepContext().getStepOutput("book-slot").path("step")
                .asString());
    }

    @Test
    void aCompletedRunShouldRestoreEveryOutputWithoutRunningAStep() throws Exception {
        CountingRunner first = new CountingRunner();
        runInScope(tree("book-slot", "declare"), first);

        CountingRunner second = new CountingRunner();
        StepRun replayed = runInScope(tree("book-slot", "declare"), second);

        assertEquals(List.of("book-slot", "declare"), first.invoked);
        assertTrue(second.invoked.isEmpty(), "Completed steps must not run again");
        assertEquals(ExecutionStatus.COMPLETED,
                runner.status(new ExecutionId("task-1/steps")).orElseThrow());
        assertEquals("declare", replayed.stepContext().getStepOutput("declare").path("step")
                .asString());
        assertEquals(Map.of("step", "book-slot"), replayed.runtimeParameters().get("book-slot"));
    }

    @Test
    void aRunCutShortShouldResumeAfterItsLastRecordedStep() throws Exception {
        CountingRunner first = new CountingRunner();
        first.crashOn.add("declare");
        assertThrows(Crash.class, () -> runInScope(tree("book-slot", "declare", "clear"), first));

        CountingRunner second = new CountingRunner();
        StepRun resumed = runInScope(tree("book-slot", "declare", "clear"), second);

        assertEquals(List.of("book-slot", "declare"), first.invoked);
        assertEquals(List.of("declare", "clear"), second.invoked,
                "The booking already recorded must not run again");
        assertEquals("book-slot", resumed.stepContext().getStepOutput("book-slot").path("step")
                .asString());
    }

    @Test
    void nestedRunsShouldKeepTheirIdsWhenAnEarlierCallIsRestored() throws Exception {
        CountingRunner first = new CountingRunner();
        first.calls.put("screen", tree("check-goods"));
        first.calls.put("customs", tree("file-declaration", "poll-declaration"));
        first.crashOn.add("poll-declaration");
        assertThrows(Crash.class, () -> runInScope(tree("screen", "customs"), first));

        CountingRunner second = new CountingRunner();
        second.calls.putAll(first.calls);
        runInScope(tree("screen", "customs"), second);

        assertEquals(List.of("customs", "poll-declaration"), second.invoked,
                "Only the step cut short and its caller run again; the filed declaration does not");
        assertEquals(ExecutionStatus.COMPLETED,
                runner.status(new ExecutionId("task-1/steps/steps/screen")).orElseThrow());
        assertEquals(ExecutionStatus.COMPLETED,
                runner.status(new ExecutionId("task-1/steps/steps/customs")).orElseThrow());
    }

    @Test
    void aRecordedFailureShouldReplayWithoutRunningTheStepAgain() throws Exception {
        CountingRunner first = new CountingRunner();
        first.failOn.add("declare");
        IllegalArgumentException original = assertThrows(IllegalArgumentException.class,
                () -> runInScope(tree("book-slot", "declare"), first));

        CountingRunner second = new CountingRunner();
        second.failOn.add("declare");
        IllegalStateException replayed = assertThrows(IllegalStateException.class,
                () -> runInScope(tree("book-slot", "declare"), second));

        assertTrue(second.invoked.isEmpty());
        assertEquals(original.getMessage(), replayed.getMessage());
    }

    @Test
    void aRestoredCallShouldKeepTheResponseApartFromItsProjectedOutput() throws Exception {
        JsonNode raw = JSON.createObjectNode().put("rawId", 7).put("extra", true);
        JsonNode projected = JSON.createObjectNode().put("id", 7);
        NodeRunner projecting = (node, run) -> {
            // As output parameters do: the step output is a projection of the response body.
            run.setLastResult(new RestoredResult(201, "application/vnd.port+json", raw));
            run.stepContext().storeStepOutput(node.step().getName(), projected);
            return projected;
        };
        DurableScope.with(runner, ROOT,
                () -> Executions.runSteps(tree("book-slot"), new StepRun(null), projecting));

        StepRun restored = DurableScope.with(runner, ROOT, () -> Executions.runSteps(
                tree("book-slot"), new StepRun(null), (node, run) -> {
                    throw new AssertionError("restored, not run");
                }));

        assertEquals(projected, restored.stepContext().getStepOutput("book-slot"));
        assertEquals(201, restored.lastResult().status());
        assertEquals("application/vnd.port+json", restored.lastResult().mediaType());
        assertEquals(raw, restored.lastResult().document());
    }

    @Test
    void aRestoredCallWhoseOutputIsItsBodyShouldJournalTheBodyOnce() throws Exception {
        JsonNode body = JSON.createObjectNode().put("id", 7);
        NodeRunner plain = (node, run) -> {
            run.setLastResult(new RestoredResult(body));
            return body;
        };
        DurableScope.with(runner, ROOT,
                () -> Executions.runSteps(tree("book-slot"), new StepRun(null), plain));

        JsonNode journaled = (JsonNode) ((ActionOutcome.Succeeded) runner
                .view(new ExecutionId("task-1/steps"))
                .outcome(ExecutionTree.STEPS.child("book-slot")).orElseThrow()).value();
        assertFalse(journaled.has(RestoredResult.BODY), journaled.toString());
        assertEquals(body, RestoredResult.responseOf(journaled).document());
    }

    @Test
    void aCallWithABodyButNoJsonOutputShouldFailInsideAScopeOnly() throws Exception {
        NodeRunner binary = (node, run) -> {
            // A call whose body is not JSON: the step records its last result, but no output.
            run.setLastResult(new RestoredResult(JSON.createObjectNode().put("raw", "bytes")));
            return null;
        };

        Executions.runSteps(tree("download-manifest"), new StepRun(null), binary);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DurableScope.with(runner, ROOT, () -> Executions.runSteps(
                        tree("download-manifest"), new StepRun(null), binary)));

        assertTrue(e.getMessage().contains("not JSON"), e.getMessage());
        assertEquals(ExecutionStatus.FAILED,
                runner.status(new ExecutionId("task-1/steps")).orElseThrow());
    }

    @Test
    void aSecondRunStartedByOneActionShouldGetANumberedId() throws Exception {
        CountingRunner nodes = new CountingRunner();
        DurableScope.with(runner, ROOT, () -> {
            Executions.runSteps(tree("a"), new StepRun(null), nodes);
            return Executions.runSteps(tree("b"), new StepRun(null), nodes);
        });

        assertEquals(ExecutionStatus.COMPLETED,
                runner.status(new ExecutionId("task-1/steps")).orElseThrow());
        assertEquals(ExecutionStatus.COMPLETED,
                runner.status(new ExecutionId("task-1/steps#2")).orElseThrow());
    }
}
