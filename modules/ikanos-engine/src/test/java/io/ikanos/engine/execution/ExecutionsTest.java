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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.Failure;

/** What a failure recorded by an earlier run of a journaled execution is raised as. */
class ExecutionsTest {

    @Test
    void aDeclineRecordedBeforeARestartShouldBeRaisedAsADeclineAgain() {
        // The step journaled the decline, and the process stopped before the round that ran it
        // recorded its response: the retry restores the failure from the journal only.
        Failure recorded = StepInterpreter.failureOf(
                new InputDeclinedException("Step 'confirm' was declined"));

        RuntimeException raised = Executions.rethrowable(new StepRun(null),
                new ExecutionResult.Failed(recorded));

        assertInstanceOf(InputDeclinedException.class, raised);
        assertEquals("Step 'confirm' was declined", raised.getMessage());
    }

    @Test
    void anotherRecordedFailureShouldStayAnIllegalState() {
        RuntimeException raised = Executions.rethrowable(new StepRun(null),
                new ExecutionResult.Failed(new Failure("McpClientException", "Upstream down")));

        assertFalse(raised instanceof InputDeclinedException);
        assertEquals("Upstream down", raised.getMessage());
    }
}
