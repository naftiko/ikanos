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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.spec.scripting.OperationStepScriptSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepLookupSpec;
import io.ikanos.spec.util.OperationStepSpec;

public class ExecutionTreeTest {

    private static Map<String, OperationStepSpec> shipyardSteps() {
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("get-voyage", new OperationStepCallSpec("get-voyage", "registry.get-voyage"));
        steps.put("list-crew", new OperationStepCallSpec("list-crew", "registry.list-crew"));
        steps.put("resolve-captain", new OperationStepLookupSpec("resolve-captain", "list-crew",
                "crewId", "$.get-voyage.captainId"));
        steps.put("score", new OperationStepScriptSpec("score", "javascript", null, "score.js"));
        return steps;
    }

    @Test
    void compileShouldKeepTheDocumentOrderOfTheStepsMap() {
        List<String> paths = new ArrayList<>();
        ExecutionTree.compile(shipyardSteps(), null).nodes()
                .forEach(node -> paths.add(node.path().value()));

        assertEquals(List.of("steps/get-voyage", "steps/list-crew", "steps/resolve-captain",
                "steps/score"), paths);
    }

    @Test
    void compileShouldClassifyCallsAndScriptsAsActionsAndLookupsAsPure() {
        List<StepNode.Kind> kinds = new ArrayList<>();
        ExecutionTree.compile(shipyardSteps(), null).nodes()
                .forEach(node -> kinds.add(node.kind()));

        assertEquals(List.of(StepNode.Kind.CALL, StepNode.Kind.CALL, StepNode.Kind.LOOKUP,
                StepNode.Kind.SCRIPT), kinds);
        assertFalse(StepNode.Kind.LOOKUP.isAction());
        assertTrue(StepNode.Kind.HANDLER.isAction());
    }

    @Test
    void compileShouldMarkHandlerStepsWithoutAdvancingTheTelemetryIndex() {
        StepHandlerRegistry registry = new StepHandlerRegistry();
        registry.register("get-voyage", context -> null);

        List<StepNode> nodes = ExecutionTree.compile(shipyardSteps(), registry).nodes();

        assertEquals(StepNode.Kind.HANDLER, nodes.get(0).kind());
        assertEquals(0, nodes.get(0).index());
        assertEquals(0, nodes.get(1).index());
        assertEquals(1, nodes.get(2).index());
    }

    @Test
    void compileShouldSkipUnknownStepTypes() {
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("custom", new OperationStepSpec("custom", "custom") {
        });

        StepNode node = ExecutionTree.compile(steps, null).nodes().get(0);

        assertEquals(StepNode.Kind.SKIP, node.kind());
        assertFalse(node.kind().isAction());
    }

    @Test
    void compileShouldReturnAnEmptyTreeForNoSteps() {
        assertTrue(ExecutionTree.compile(null, null).isEmpty());
        assertTrue(ExecutionTree.compile(new LinkedHashMap<>(), null).isEmpty());
    }

    @Test
    void runStepsShouldRunNodesInOrderAndEvaluateLookupsOnce() {
        List<String> ran = new ArrayList<>();
        StepRun run = new StepRun(Map.of("voyageId", "VOY-2026-042"));

        StepRun result = Executions.runSteps(ExecutionTree.compile(shipyardSteps(), null), run,
                (node, current) -> {
                    ran.add(node.path().value());
                    return null;
                });

        assertSame(run, result);
        assertEquals(List.of("steps/get-voyage", "steps/list-crew", "steps/resolve-captain",
                "steps/score"), ran);
        assertEquals("VOY-2026-042", run.runtimeParameters().get("voyageId"));
    }

    @Test
    void runStepsShouldRethrowTheExceptionThatEndedTheRunUnchanged() {
        IllegalArgumentException boom = new IllegalArgumentException("Invalid call format");
        List<String> ran = new ArrayList<>();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Executions.runSteps(ExecutionTree.compile(shipyardSteps(), null),
                        new StepRun(null), (node, current) -> {
                            ran.add(node.path().value());
                            throw boom;
                        }));

        assertSame(boom, thrown);
        assertEquals(List.of("steps/get-voyage"), ran);
    }

    @Test
    void runStepsShouldRethrowAFailedLookupUnchanged() {
        IllegalArgumentException missing =
                new IllegalArgumentException("Lookup step references non-existent step");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Executions.runSteps(ExecutionTree.compile(shipyardSteps(), null),
                        new StepRun(null), (node, current) -> {
                            if (node.kind() == StepNode.Kind.LOOKUP) {
                                throw missing;
                            }
                            return null;
                        }));

        assertSame(missing, thrown);
    }

    @Test
    void runStepsShouldNotRunAnEmptyTree() {
        StepRun run = new StepRun(null);
        assertSame(run, Executions.runSteps(ExecutionTree.compile(null, null), run,
                (node, current) -> {
                    throw new AssertionError("no node to run");
                }));
    }

    @Test
    void failureOfShouldNameAnAnonymousExceptionByItsClassName() {
        RuntimeException anonymous = new RuntimeException("x") {
            private static final long serialVersionUID = 1L;
        };
        assertTrue(StepInterpreter.failureOf(anonymous).errorClass().contains("ExecutionTreeTest"));
    }
}
