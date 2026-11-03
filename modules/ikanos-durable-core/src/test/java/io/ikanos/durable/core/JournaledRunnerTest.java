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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.PayloadCodec;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.memory.MemoryStoreProvider;

public class JournaledRunnerTest {

    private static final ExecutionId ID = new ExecutionId("task-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"),
            ZoneOffset.UTC);
    private static final NodePath SCREEN = new NodePath("steps/screen");
    private static final NodePath CONFIRM = new NodePath("steps/confirm-hazardous");
    private static final NodePath BOOK = new NodePath("steps/book-slot");

    /** Strings only: enough for these tests, and no JSON library in the engine. */
    private static final PayloadCodec STRINGS = new PayloadCodec() {
        @Override
        public byte[] encode(Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object decode(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    };

    private final Stores stores = MemoryStoreProvider.openStores(CLOCK);
    private final JournaledRunner runner = new JournaledRunner(stores, STRINGS, CLOCK, "sha256:tree");
    private final List<String> calls = new ArrayList<>();

    private final ActionExecutor recording = request -> {
        calls.add(request.path().value());
        return "result of " + request.action();
    };

    /** Screen, ask the duty officer, then book: the departure-clearance shape. */
    private static final Interpreter CLEARANCE = view -> {
        if (view.outcome(SCREEN).isEmpty()) {
            return List.of(new Command.ScheduleAction(SCREEN, "screen", null));
        }
        if (view.update(CONFIRM).isEmpty()) {
            return List.of(new Command.AwaitUpdate(CONFIRM, "Confirm 3 containers"));
        }
        if (!"true".equals(view.update(CONFIRM).get())) {
            return List.of(new Command.Fail(new Failure("declined", "Declaration not confirmed")));
        }
        if (view.outcome(BOOK).isEmpty()) {
            return List.of(new Command.ScheduleAction(BOOK, "book-slot", null));
        }
        if (view.outcome(BOOK).get() instanceof ActionOutcome.Failed failed) {
            return List.of(new Command.Fail(failed.failure()));
        }
        return List.of(new Command.Complete(
                ((ActionOutcome.Succeeded) view.outcome(BOOK).get()).value()));
    };

    @Test
    void runShouldSuspendAndMarkTheExecutionSuspended() {
        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertInstanceOf(ExecutionResult.Suspended.class, result);
        assertEquals(ExecutionStatus.SUSPENDED, runner.status(ID).orElseThrow());
        assertEquals(List.of("steps/screen"), calls);
    }

    @Test
    void runShouldResumeFromTheJournalWithoutRunningCompletedActionsAgain() {
        runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);
        assertTrue(runner.deliver(ID, CONFIRM, true));

        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertEquals(new ExecutionResult.Completed("result of book-slot"), result);
        assertEquals(List.of("steps/screen", "steps/book-slot"), calls);
        assertEquals(ExecutionStatus.COMPLETED, runner.status(ID).orElseThrow());
    }

    @Test
    void deliverShouldAcceptOneUpdatePerPath() {
        assertTrue(runner.deliver(ID, CONFIRM, true));
        assertFalse(runner.deliver(ID, CONFIRM, false));
    }

    @Test
    void runShouldAcceptUpdatesPassedInTheRequest() {
        ExecutionRequest withAnswer = new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null);

        assertEquals(new ExecutionResult.Completed("result of book-slot"),
                runner.run(withAnswer, CLEARANCE, recording));
    }

    @Test
    void runShouldReturnTheRecordedResultOfAnEndedExecution() {
        runner.run(new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), CLEARANCE,
                recording);
        calls.clear();

        ExecutionResult again = runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertEquals(new ExecutionResult.Completed("result of book-slot"), again);
        assertTrue(calls.isEmpty());
        assertEquals(again, runner.result(ID).orElseThrow());
    }

    @Test
    void runShouldRecordAFailedActionAndFailTheExecution() {
        ActionExecutor slotTaken = request -> {
            if (request.path().equals(BOOK)) {
                throw new ActionFailedException(new Failure("http.status.409", "Slot taken"));
            }
            return "ok";
        };

        ExecutionResult result = runner.run(
                new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), CLEARANCE, slotTaken);

        Failure expected = new Failure("http.status.409", "Slot taken");
        assertEquals(new ExecutionResult.Failed(expected), result);
        assertEquals(ExecutionStatus.FAILED, runner.status(ID).orElseThrow());
        assertEquals(new ExecutionResult.Failed(expected), runner.result(ID).orElseThrow());
    }

    @Test
    void runShouldJournalOutcomesUpdatesAndTheResultInOrder() {
        runner.run(new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), CLEARANCE,
                recording);

        List<JournalEntry.Kind> kinds = stores.journal().read(ID).stream()
                .map(JournalEntry::kind).toList();
        assertEquals(List.of(JournalEntry.Kind.UPDATE_RECEIVED, JournalEntry.Kind.ACTION_SUCCEEDED,
                JournalEntry.Kind.ACTION_SUCCEEDED, JournalEntry.Kind.EXECUTION_COMPLETED), kinds);
    }

    @Test
    void runShouldKeepRecordedDecisionsAcrossRuns() {
        NodePath route = new NodePath("steps/inspection");
        Interpreter decideThenAwait = view -> {
            if (view.decision(route).isEmpty()) {
                return List.of(new Command.RecordDecision(route, "then"));
            }
            if (view.update(CONFIRM).isEmpty()) {
                return List.of(new Command.AwaitUpdate(CONFIRM, null));
            }
            return List.of(new Command.Complete(view.decision(route).get()));
        };
        runner.run(ExecutionRequest.of(ID, null), decideThenAwait, recording);
        runner.deliver(ID, CONFIRM, true);

        assertEquals(new ExecutionResult.Completed("then"),
                runner.run(ExecutionRequest.of(ID, null), decideThenAwait, recording));
    }

    @Test
    void cancelShouldStopASuspendedExecution() {
        runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        runner.cancel(ID);

        assertEquals(ExecutionStatus.CANCELLED, runner.status(ID).orElseThrow());
        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);
        assertEquals("cancelled",
                assertInstanceOf(ExecutionResult.Failed.class, result).failure().errorClass());
    }

    @Test
    void cancelShouldNotChangeAnEndedExecution() {
        runner.run(new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), CLEARANCE,
                recording);
        runner.cancel(ID);
        assertEquals(ExecutionStatus.COMPLETED, runner.status(ID).orElseThrow());
    }

    @Test
    void createShouldBeIdempotent() {
        assertTrue(runner.create(ExecutionRequest.of(ID, null), "mcp:shipyard-ops"));
        assertFalse(runner.create(ExecutionRequest.of(ID, null), "mcp:shipyard-ops"));
        assertEquals(ExecutionStatus.RUNNING, runner.status(ID).orElseThrow());
        assertTrue(runner.result(ID).isEmpty());
    }

    @Test
    void runShouldFailAndRecordAPassedDeadline() {
        ExecutionRequest late =
                new ExecutionRequest(ID, null, Map.of(), CLOCK.instant().minusSeconds(1));

        ExecutionResult result = runner.run(late, CLEARANCE, recording);

        assertEquals(Failure.DEADLINE_EXCEEDED,
                assertInstanceOf(ExecutionResult.Failed.class, result).failure().errorClass());
        assertEquals(ExecutionStatus.FAILED, runner.status(ID).orElseThrow());
    }

    @Test
    void runShouldRejectATimerUntilDurableTimersExist() {
        Interpreter timer = view -> List.of(new Command.StartTimer(SCREEN, Duration.ofMinutes(2)));
        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), timer, recording));
    }

    @Test
    void runShouldRejectAnActionScheduledAgainAfterItsOutcome() {
        Interpreter again = view -> List.of(new Command.ScheduleAction(SCREEN, "screen", null));
        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), again, recording));
    }

    @Test
    void runShouldRejectADecisionRecordedTwice() {
        Interpreter twice = view -> List.of(new Command.RecordDecision(SCREEN, "x"));
        assertThrows(IllegalStateException.class,
                () -> runner.run(ExecutionRequest.of(ID, null), twice, recording));
    }

    @Test
    void runShouldRejectAnAwaitForAKnownUpdate() {
        Interpreter awaitKnown = view -> List.of(new Command.AwaitUpdate(CONFIRM, null));
        assertThrows(IllegalStateException.class, () -> runner.run(
                new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), awaitKnown,
                recording));
    }

    @Test
    void failuresShouldRoundTripThroughTheirEncoding() {
        Failure failure = new Failure("http.status.503", "Port Authority\nunavailable");
        assertEquals(failure,
                JournaledRunner.decodeFailure(JournaledRunner.encodeFailure(failure)));
        assertEquals(new Failure("timeout", ""),
                JournaledRunner.decodeFailure("timeout".getBytes(StandardCharsets.UTF_8)));
    }
}
