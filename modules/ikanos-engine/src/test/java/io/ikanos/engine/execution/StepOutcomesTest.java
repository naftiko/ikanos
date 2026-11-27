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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepEndSpec;
import io.ikanos.spec.util.OperationStepSpec;

public class StepOutcomesTest {

    @Test
    void ofShouldMapAStatusToAProtocolNeutralOutcome() {
        assertEquals("success", StepOutcomes.of(204));
        assertEquals("not-found", StepOutcomes.of(404));
        assertEquals("not-found", StepOutcomes.of(410));
        assertEquals("throttled", StepOutcomes.of(429));
        assertEquals("unreachable", StepOutcomes.of(0));
        assertEquals("failure", StepOutcomes.of(500));
        assertEquals("failure", StepOutcomes.of(409));
    }

    @Test
    void putShouldRecordAnOutcomeUnderItsNameAndItsAlias() {
        Map<String, Object> parameters = new HashMap<>();

        StepOutcomes.put(parameters, "find-page", "not-found");

        Map<?, ?> outcomes = (Map<?, ?>) parameters.get("outcome");
        assertEquals("not-found", outcomes.get("find-page"));
        assertEquals("not-found", outcomes.get("find_page"));
    }

    @Test
    void putShouldReplaceAValueUnderTheReservedName() {
        Map<String, Object> parameters = new HashMap<>(Map.of("outcome", "won"));

        StepOutcomes.put(parameters, "find", "success");

        assertEquals("success", StepOutcomes.get(parameters, "find"));
    }

    @Test
    void putShouldNotChangeTheMapAnotherRunCopied() {
        Map<String, Object> caller = new HashMap<>();
        StepOutcomes.put(caller, "find", "success");
        Map<String, Object> called = new HashMap<>(caller);

        StepOutcomes.put(called, "find", "failure");

        assertEquals("success", StepOutcomes.get(caller, "find"));
    }

    @Test
    void aNewRunShouldNotInheritTheOutcomesOfItsCaller() {
        Map<String, Object> caller = new HashMap<>();
        StepOutcomes.put(caller, "find", "not-found");

        StepRun called = new StepRun(caller);

        assertNull(StepOutcomes.get(called.runtimeParameters(), "find"));
    }

    @Test
    void acceptedShouldBeASuccessWithNoContent() {
        assertEquals(204, StepOutcomes.accepted().status());
        assertTrue(StepOutcomes.accepted().isSuccessful());
        assertFalse(StepOutcomes.accepted().hasBody());
    }

    @Test
    void restoreOutputShouldRestoreTheOutcomeJournaledWithACall() {
        StepRun run = new StepRun(null);
        tools.jackson.databind.node.ObjectNode journaled = (tools.jackson.databind.node.ObjectNode)
                RestoredResult.journal(null, StepOutcomes.accepted());
        journaled.put(RestoredResult.OUTCOME, "not-found");

        run.restoreOutput("find", journaled, true);

        assertTrue(StepGuard.holds("next", "outcome.find == 'not-found'",
                run.runtimeParameters()));
    }

    @Test
    void restoreOutputShouldMapTheStatusOfACallJournaledWithoutAnOutcome() {
        StepRun run = new StepRun(null);

        run.restoreOutput("find",
                RestoredResult.journal(null, new RestoredResult(429, null, null)), true);

        assertEquals("throttled", StepOutcomes.get(run.runtimeParameters(), "find"));
    }

    @Test
    void anEndStepShouldCompleteTheRunWithoutTheStepsAfterIt() {
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("first", new OperationStepCallSpec("first", "port.first"));
        steps.put("done", new OperationStepEndSpec("done"));
        steps.put("after", new OperationStepCallSpec("after", "port.after"));
        List<String> invoked = new java.util.ArrayList<>();

        StepRun run = Executions.runSteps(ExecutionTree.compile(steps, null), new StepRun(null),
                (node, current) -> {
                    invoked.add(node.step().getName());
                    return null;
                });

        assertEquals(List.of("first"), invoked);
        assertNull(run.failure());
    }
}
