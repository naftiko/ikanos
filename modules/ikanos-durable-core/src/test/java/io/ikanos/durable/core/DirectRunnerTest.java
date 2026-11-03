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
package io.ikanos.durable.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;

public class DirectRunnerTest {

    private static final ExecutionId ID = new ExecutionId("exec-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"),
            ZoneOffset.UTC);

    private final DirectRunner runner = new DirectRunner(CLOCK);
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** Runs named steps in order, and completes with the last step's value. */
    private static Interpreter sequence(String... steps) {
        return view -> {
            Object last = null;
            for (String step : steps) {
                NodePath path = NodePath.of("steps").child(step);
                var outcome = view.outcome(path);
                if (outcome.isEmpty()) {
                    return List.of(new Command.ScheduleAction(path, step, null));
                }
                if (outcome.get() instanceof ActionOutcome.Failed failed) {
                    return List.of(new Command.Fail(failed.failure()));
                }
                last = ((ActionOutcome.Succeeded) outcome.get()).value();
            }
            return List.of(new Command.Complete(last));
        };
    }

    private ActionExecutor recording() {
        return request -> {
            calls.add(request.path().value());
            return "result of " + request.action();
        };
    }

    @Test
    void runShouldCompleteWithTheLastResultWhenEveryActionSucceeds() {
        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null),
                sequence("voyage", "eligibility"), recording());

        assertEquals(new ExecutionResult.Completed("result of eligibility"), result);
        assertEquals(List.of("steps/voyage", "steps/eligibility"), calls);
    }

    @Test
    void runShouldStopAtTheFirstFailedAction() {
        ActionExecutor executor = request -> {
            calls.add(request.path().value());
            throw new ActionFailedException(new Failure("http.status.422", "Ship not active"));
        };

        ExecutionResult result =
                runner.run(ExecutionRequest.of(ID, null), sequence("eligibility", "book-slot"),
                        executor);

        assertEquals(new ExecutionResult.Failed(new Failure("http.status.422", "Ship not active")),
                result);
        assertEquals(List.of("steps/eligibility"), calls);
    }

    @Test
    void runShouldClassifyAnUnexpectedExceptionAsAFailure() {
        ActionExecutor executor = request -> {
            throw new IllegalStateException("boom");
        };

        ExecutionResult result =
                runner.run(ExecutionRequest.of(ID, null), sequence("voyage"), executor);

        assertEquals(new ExecutionResult.Failed(new Failure(Failure.UNEXPECTED, "boom")), result);
    }

    @Test
    void runShouldPassTheIdempotencyKeyOfEachAction() {
        List<String> keys = new ArrayList<>();
        runner.run(ExecutionRequest.of(ID, null), sequence("book-slot"), request -> {
            keys.add(request.idempotencyKey());
            return null;
        });

        assertEquals(List.of("exec-1:steps/book-slot"), keys);
    }

    @Test
    @Timeout(5)
    void runShouldRunTheActionsOfOneBatchConcurrently() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        NodePath ship = new NodePath("steps/gather/ship");
        NodePath crew = new NodePath("steps/gather/crew");
        Interpreter gather = view -> view.outcome(ship).isPresent()
                ? List.of(new Command.Complete(List.of(
                        ((ActionOutcome.Succeeded) view.outcome(ship).get()).value(),
                        ((ActionOutcome.Succeeded) view.outcome(crew).get()).value())))
                : List.of(new Command.ScheduleAction(ship, "ship", null),
                        new Command.ScheduleAction(crew, "crew", null));

        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null), gather, request -> {
            bothStarted.countDown();
            // Each action waits for the other: this only returns if they run at the same time.
            if (!bothStarted.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not concurrent");
            }
            return request.action();
        });

        assertEquals(new ExecutionResult.Completed(List.of("ship", "crew")), result);
    }

    @Test
    void runShouldSuspendWhenAnUpdateIsAwaited() {
        NodePath confirm = new NodePath("steps/confirm-hazardous");
        Interpreter interpreter = confirmBeforeBooking(confirm);

        ExecutionResult result =
                runner.run(ExecutionRequest.of(ID, null), interpreter, recording());

        ExecutionResult.Suspended suspended = assertInstanceOf(ExecutionResult.Suspended.class,
                result);
        assertEquals(List.of(new Command.AwaitUpdate(confirm, "Confirm 3 containers")),
                suspended.awaiting());
        assertEquals(List.of("steps/screen"), calls);
    }

    @Test
    void runShouldReplayFromTheStartWhenTheAnswerIsSupplied() {
        NodePath confirm = new NodePath("steps/confirm-hazardous");
        ExecutionRequest withAnswer =
                new ExecutionRequest(ID, null, Map.of(confirm, Boolean.TRUE), null);

        ExecutionResult result = runner.run(withAnswer, confirmBeforeBooking(confirm), recording());

        assertEquals(new ExecutionResult.Completed("result of book-slot"), result);
        // Nothing is journaled under none: the safe action before the pause runs again.
        assertEquals(List.of("steps/screen", "steps/book-slot"), calls);
    }

    private static Interpreter confirmBeforeBooking(NodePath confirm) {
        NodePath screen = new NodePath("steps/screen");
        NodePath book = new NodePath("steps/book-slot");
        return view -> {
            if (view.outcome(screen).isEmpty()) {
                return List.of(new Command.ScheduleAction(screen, "screen", null));
            }
            if (view.update(confirm).isEmpty()) {
                return List.of(new Command.AwaitUpdate(confirm, "Confirm 3 containers"));
            }
            if (view.outcome(book).isEmpty()) {
                return List.of(new Command.ScheduleAction(book, "book-slot", null));
            }
            return List.of(new Command.Complete(
                    ((ActionOutcome.Succeeded) view.outcome(book).get()).value()));
        };
    }

    @Test
    void runShouldKeepRecordedDecisionsForTheInterpreter() {
        NodePath route = new NodePath("steps/inspection");
        Interpreter interpreter = view -> view.decision(route).isPresent()
                ? List.of(new Command.Complete(view.decision(route).get()))
                : List.of(new Command.RecordDecision(route, "then"));

        assertEquals(new ExecutionResult.Completed("then"),
                runner.run(ExecutionRequest.of(ID, null), interpreter, recording()));
    }

    @Test
    void runShouldExposeTheInputAndTheClockToTheInterpreter() {
        Interpreter interpreter = view -> List.of(new Command.Complete(
                view.input() + "@" + view.now() + "/" + view.executionId() + "/"
                        + view.timerFired(NodePath.of("steps"))));

        assertEquals(
                new ExecutionResult.Completed("VOY-2026-042@2026-11-03T10:00:00Z/exec-1/false"),
                runner.run(ExecutionRequest.of(ID, "VOY-2026-042"), interpreter, recording()));
    }

    @Test
    void runShouldFailWhenTheDeadlineHasPassed() {
        ExecutionRequest late = new ExecutionRequest(ID, null, Map.of(),
                CLOCK.instant().minusSeconds(1));

        ExecutionResult result = runner.run(late, sequence("voyage"), recording());

        ExecutionResult.Failed failed = assertInstanceOf(ExecutionResult.Failed.class, result);
        assertEquals(Failure.DEADLINE_EXCEEDED, failed.failure().errorClass());
        assertTrue(calls.isEmpty());
    }

    @Test
    void runShouldRejectATimer() {
        Interpreter interpreter = view -> List.of(
                new Command.StartTimer(new NodePath("steps/wait"), Duration.ofMinutes(2)));

        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), interpreter, recording()));
    }

    @Test
    void runShouldRejectAnEmptyBatch() {
        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), view -> List.of(), recording()));
    }

    @Test
    void runShouldRejectATerminalCommandMixedWithOthers() {
        Interpreter interpreter = view -> List.of(new Command.Complete(null),
                new Command.ScheduleAction(new NodePath("steps/a"), "a", null));

        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), interpreter, recording()));
    }

    @Test
    void runShouldRejectTwoCommandsForOnePath() {
        NodePath path = new NodePath("steps/a");
        Interpreter interpreter = view -> List.of(new Command.ScheduleAction(path, "a", null),
                new Command.RecordDecision(path, "x"));

        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), interpreter, recording()));
    }

    @Test
    void runShouldRejectAnActionScheduledAgainAfterItsOutcome() {
        NodePath path = new NodePath("steps/a");
        Interpreter interpreter = view -> List.of(new Command.ScheduleAction(path, "a", null));

        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), interpreter, recording()));
        assertEquals(List.of("steps/a"), calls);
    }

    @Test
    void runShouldRejectADecisionRecordedTwice() {
        NodePath path = new NodePath("steps/route");
        Interpreter interpreter = view -> List.of(new Command.RecordDecision(path, "then"));

        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), interpreter, recording()));
    }

    @Test
    void runShouldRejectAnAwaitForAnUpdateAlreadyKnown() {
        NodePath path = new NodePath("steps/confirm");
        ExecutionRequest request = new ExecutionRequest(ID, null, Map.of(path, true), null);
        Interpreter interpreter = view -> List.of(new Command.AwaitUpdate(path, null));

        assertThrows(IllegalStateException.class,
                () -> runner.run(request, interpreter, recording()));
    }

    @Test
    void runShouldRunActionsBeforeSuspendingWhenBothAreRequested() {
        NodePath confirm = new NodePath("steps/confirm");
        NodePath prefetch = new NodePath("steps/prefetch");
        Interpreter interpreter = view -> view.outcome(prefetch).isEmpty()
                ? List.of(new Command.ScheduleAction(prefetch, "prefetch", null),
                        new Command.AwaitUpdate(confirm, null))
                : List.of(new Command.AwaitUpdate(confirm, null));

        ExecutionResult result =
                runner.run(ExecutionRequest.of(ID, null), interpreter, recording());

        assertInstanceOf(ExecutionResult.Suspended.class, result);
        assertEquals(List.of("steps/prefetch"), calls);
    }

    @Test
    void describeShouldFallBackToTheExceptionClassName() {
        assertEquals("IllegalStateException", DirectRunner.describe(new IllegalStateException()));
    }

    @Test
    void attemptShouldReportAnInterruptedAction() {
        ActionOutcome outcome = DirectRunner.attempt(ExecutionRequest.of(ID, null),
                new Command.ScheduleAction(new NodePath("steps/a"), "a", null), request -> {
                    throw new InterruptedException();
                });

        assertEquals(Failure.INTERRUPTED,
                assertInstanceOf(ActionOutcome.Failed.class, outcome).failure().errorClass());
        assertTrue(Thread.interrupted());
    }

    @Test
    void viewShouldReportNoOutcomeBeforeAnActionRuns() {
        ExecutionView view = new DirectExecutionView(ExecutionRequest.of(ID, null), CLOCK);
        assertTrue(view.outcome(new NodePath("steps/a")).isEmpty());
        assertTrue(view.decision(new NodePath("steps/a")).isEmpty());
    }
}
