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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.ikanos.durable.NodePath;

/**
 * Answers to {@code elicit} steps, addressed by the full path of calling steps so that two called
 * flows whose elicit steps share a name are asked, and answered, apart.
 */
class ExecutionInputsTest {

    private static final NodePath CREW = new NodePath("steps/crew");
    private static final NodePath CARGO = new NodePath("steps/cargo");
    private static final NodePath CONFIRM = new NodePath("steps/confirm");

    @Test
    void aCalledFlowShouldSeeOnlyTheAnswersAddressedUnderItsCallingStep() throws Exception {
        Map<NodePath, Object> answers = Map.of(
                new NodePath("steps/crew/steps/confirm"), "crew-answer",
                new NodePath("steps/cargo/steps/confirm"), "cargo-answer");

        Map<NodePath, Object> seen = ExecutionInputs.with(answers,
                () -> ExecutionInputs.within(ExecutionInputs.capture(), CARGO,
                        ExecutionInputs::current));

        assertEquals(Map.of(CONFIRM, "cargo-answer"), seen);
    }

    @Test
    void aQuestionShouldBeKeyedByTheFullPathOfItsCallingSteps() throws Exception {
        NodePath key = ExecutionInputs.with(Map.of(),
                () -> ExecutionInputs.within(ExecutionInputs.capture(), CREW,
                        () -> ExecutionInputs.within(ExecutionInputs.capture(),
                                new NodePath("steps/inspect"),
                                () -> ExecutionInputs.qualify(CONFIRM))));

        assertEquals(new NodePath("steps/crew/steps/inspect/steps/confirm"), key);
    }

    @Test
    void aTopLevelQuestionShouldKeepItsOwnPath() {
        assertEquals(CONFIRM, ExecutionInputs.qualify(CONFIRM));
    }

    @Test
    void capturedAnswersShouldReachAnActionRunOnAnotherThread() throws Exception {
        Map<NodePath, Object> answers = Map.of(new NodePath("steps/crew/steps/confirm"), "yes");
        AtomicReference<Map<NodePath, Object>> seen = new AtomicReference<>();

        ExecutionInputs.with(answers, () -> {
            ExecutionInputs.Snapshot snapshot = ExecutionInputs.capture();
            // Parallel actions run on threads of their own, which inherit no thread-local.
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    seen.set(ExecutionInputs.within(snapshot, CREW, ExecutionInputs::current));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            thread.join();
            return null;
        });

        assertEquals(Map.of(CONFIRM, "yes"), seen.get());
    }
}
