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
import io.ikanos.engine.consumes.http.StandardRequestHeaders.InvalidPreconditionException;
import io.ikanos.engine.util.OperationStepExecutor.StepFailedException;

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
    void aPreconditionRefusalRecordedBeforeARestartShouldBeRaisedAsOneAgain() {
        // The step journaled a write refused for its precondition (#1001), and the process
        // stopped before the round recorded its response: the retry is still bad input (400),
        // not an internal failure.
        Failure recorded = StepInterpreter.failureOf(new InvalidPreconditionException(
                "No value for the If-Match header of a request that is not safe"));

        RuntimeException raised = Executions.rethrowable(new StepRun(null),
                new ExecutionResult.Failed(recorded));

        assertInstanceOf(InvalidPreconditionException.class, raised);
        assertEquals("No value for the If-Match header of a request that is not safe",
                raised.getMessage());
    }

    @Test
    void anotherRecordedFailureShouldStayAnIllegalState() {
        RuntimeException raised = Executions.rethrowable(new StepRun(null),
                new ExecutionResult.Failed(new Failure("McpClientException", "Upstream down")));

        assertFalse(raised instanceof InputDeclinedException);
        assertEquals("Upstream down", raised.getMessage());
    }

    @Test
    void anUpstreamFailureRecordedBeforeARestartShouldBeRaisedAsOneAgain() {
        // The step journaled its upstream failure, and the process stopped before the round
        // recorded its response: REST still answers 502, and MCP the same status.
        RuntimeException raised = Executions.rethrowable(new StepRun(null),
                new ExecutionResult.Failed(new Failure("StepFailedException",
                        "Step 'book' failed with HTTP 503")));

        StepFailedException failed = assertInstanceOf(StepFailedException.class, raised);
        assertEquals("Step 'book' failed with HTTP 503", failed.getMessage());
        assertEquals("book", failed.getStepName());
        assertEquals(503, failed.getStatusCode());
    }

    @Test
    void anUpstreamFailureWithNoResponseShouldBeRaisedWithNoStatus() {
        RuntimeException raised = Executions.rethrowable(new StepRun(null),
                new ExecutionResult.Failed(new Failure("StepFailedException",
                        "Step 'book' failed: no response received")));

        assertEquals(0, assertInstanceOf(StepFailedException.class, raised).getStatusCode());
    }
}
