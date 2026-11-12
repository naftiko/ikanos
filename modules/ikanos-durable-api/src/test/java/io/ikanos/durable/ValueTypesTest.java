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
package io.ikanos.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ValueTypesTest {

    private static final ExecutionId ID = new ExecutionId("exec-1");
    private static final NodePath PATH = new NodePath("steps/book-slot");

    @Test
    void executionIdShouldRejectABlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionId(" "));
    }

    @Test
    void randomExecutionIdsShouldDiffer() {
        assertNotEquals(ExecutionId.random(), ExecutionId.random());
    }

    @Test
    void executionIdToStringShouldReturnTheValue() {
        assertEquals("exec-1", ID.toString());
    }

    @Test
    void idempotencyKeyShouldJoinExecutionIdAndPath() {
        assertEquals("exec-1:steps/book-slot",
                new ActionRequest(ID, PATH, null, 2).idempotencyKey());
    }

    @Test
    void actionRequestShouldRejectAttemptZero() {
        assertThrows(IllegalArgumentException.class, () -> new ActionRequest(ID, PATH, null, 0));
    }

    @Test
    void failureShouldRejectABlankClass() {
        assertThrows(IllegalArgumentException.class, () -> new Failure("", "message"));
    }

    @Test
    void failureShouldDefaultAMissingMessageToEmpty() {
        assertEquals("", new Failure("timeout", null).message());
    }

    @Test
    void actionFailedExceptionShouldCarryItsFailure() {
        Failure failure = new Failure("http.status.409", "Slot already booked");
        ActionFailedException e = new ActionFailedException(failure);
        assertSame(failure, e.failure());
        assertEquals("Slot already booked", e.getMessage());
    }

    @Test
    void retryPolicyShouldGrowBackoffUpToItsCap() {
        RetryPolicy policy =
                new RetryPolicy(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(3));
        assertEquals(Duration.ZERO, policy.backoffBefore(1));
        assertEquals(Duration.ofSeconds(1), policy.backoffBefore(2));
        assertEquals(Duration.ofSeconds(2), policy.backoffBefore(3));
        assertEquals(Duration.ofSeconds(3), policy.backoffBefore(4));
    }

    @Test
    void retryPolicyShouldRejectInvalidSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, Duration.ZERO, 1.0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(1, Duration.ofSeconds(-1), 1.0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(1, Duration.ZERO, 0.5, Duration.ZERO));
    }

    @Test
    void scheduleActionShouldDefaultToNoRetry() {
        assertSame(RetryPolicy.NONE, new Command.ScheduleAction(PATH, "call", null).retry());
    }

    @Test
    void startTimerShouldRejectANegativeDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> new Command.StartTimer(PATH, Duration.ofSeconds(-1)));
    }

    @Test
    void terminalCommandsShouldHaveNoPath() {
        assertNull(new Command.Complete("done").path());
        assertNull(new Command.Fail(new Failure("criteria", "")).path());
    }

    @Test
    void executionRequestShouldCopyItsUpdates() {
        Map<NodePath, Object> updates = new HashMap<>();
        updates.put(PATH, true);
        ExecutionRequest request = new ExecutionRequest(ID, null, updates, null);
        updates.clear();
        assertEquals(Map.of(PATH, true), request.updates());
    }

    @Test
    void executionRequestOfShouldHaveNoUpdateAndNoDeadline() {
        ExecutionRequest request = ExecutionRequest.of(ID, "input");
        assertTrue(request.updates().isEmpty());
        assertNull(request.deadline());
    }

    @Test
    void suspendedShouldRequireAnAwaitedUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionResult.Suspended(List.of()));
    }

    @Test
    void failedResultsShouldRequireAFailure() {
        assertThrows(NullPointerException.class, () -> new ExecutionResult.Failed(null));
        assertThrows(NullPointerException.class, () -> new ActionOutcome.Failed(null));
    }
}
