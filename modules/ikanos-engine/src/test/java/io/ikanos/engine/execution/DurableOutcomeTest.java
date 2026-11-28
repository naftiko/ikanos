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
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepEndSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Outcomes and an early end in a durable run: a call accepted as {@code not-found} is journaled
 * with its outcome, so a run resumed after a restart reads the same outcome without calling
 * again, and a replay reaches the same {@code end}.
 */
public class DurableOutcomeTest {

    private static final ExecutionId ROOT = new ExecutionId("task-1");

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** {@code find} is accepted as not found, as a success criterion would accept it. */
    private static final class PageRunner implements NodeRunner {
        final List<String> invoked = new ArrayList<>();
        final Set<String> crashOn = new HashSet<>();

        @Override
        public Object run(StepNode node, StepRun run) {
            String name = node.step().getName();
            invoked.add(name);
            if (crashOn.remove(name)) {
                throw new Crash();
            }
            if ("find".equals(name)) {
                StepOutcomes.put(run.runtimeParameters(), name, StepOutcomes.NOT_FOUND);
                run.setLastResult(StepOutcomes.accepted());
                return null;
            }
            StepOutcomes.put(run.runtimeParameters(), name, StepOutcomes.SUCCESS);
            run.setLastResult(StepOutcomes.accepted());
            return null;
        }
    }

    private static ExecutionTree tree(OperationStepSpec... steps) {
        Map<String, OperationStepSpec> map = new LinkedHashMap<>();
        for (OperationStepSpec step : steps) {
            map.put(step.getName(), step);
        }
        return ExecutionTree.compile(map, null);
    }

    private static OperationStepCallSpec call(String name, String onlyIf) {
        OperationStepCallSpec step = new OperationStepCallSpec(name, "notion." + name);
        step.setOnlyIf(onlyIf);
        return step;
    }

    private StepRun inScope(ExecutionTree tree, PageRunner nodes) throws Exception {
        return DurableScope.with(runner, ROOT,
                () -> Executions.runSteps(tree, new StepRun(null), nodes));
    }

    @Test
    void aResumedRunShouldReadTheJournaledOutcomeOfAnAcceptedCall() throws Exception {
        ExecutionTree tree = tree(call("find", null),
                call("create", "outcome.find == 'not-found'"));
        PageRunner first = new PageRunner();
        first.crashOn.add("create");
        assertThrows(Crash.class, () -> inScope(tree, first));

        PageRunner second = new PageRunner();
        StepRun resumed = inScope(tree, second);

        assertEquals(List.of("find", "create"), first.invoked);
        assertEquals(List.of("create"), second.invoked,
                "find must not run again, and create still sees it as not found");
        assertEquals("not-found", StepOutcomes.get(resumed.runtimeParameters(), "find"));
    }

    @Test
    void aReplayShouldReachTheSameEnd() throws Exception {
        OperationStepEndSpec end = new OperationStepEndSpec("missing");
        end.setOnlyIf("outcome.find == 'not-found'");
        ExecutionTree tree = tree(call("find", null), end, call("update", null));
        PageRunner first = new PageRunner();
        inScope(tree, first);

        PageRunner second = new PageRunner();
        inScope(tree, second);

        assertEquals(List.of("find"), first.invoked, "the run ends before update");
        assertTrue(second.invoked.isEmpty(), "a completed run is restored, not run again");
    }
}
