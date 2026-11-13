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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import io.ikanos.durable.store.ExecutionIndex;
import io.ikanos.durable.store.ExecutionRecord;
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
        assertEquals(List.of(JournalEntry.Kind.UPDATE_RECEIVED,
                JournalEntry.Kind.ACTION_SCHEDULED, JournalEntry.Kind.ACTION_SUCCEEDED,
                JournalEntry.Kind.ACTION_SCHEDULED, JournalEntry.Kind.ACTION_SUCCEEDED,
                JournalEntry.Kind.EXECUTION_COMPLETED), kinds);
        assertEquals("task-1:steps/screen", new String(stores.journal().read(ID).get(1).payload(),
                StandardCharsets.UTF_8), "A scheduled action carries its idempotency key");
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
    void cancelShouldReachARunningExecutionAndStopItBeforeTheNextAction() throws Exception {
        Interpreter screenThenBook = view -> {
            if (view.outcome(SCREEN).isEmpty()) {
                return List.of(new Command.ScheduleAction(SCREEN, "screen", null));
            }
            if (view.outcome(BOOK).isEmpty()) {
                return List.of(new Command.ScheduleAction(BOOK, "book-slot", null));
            }
            return List.of(new Command.Complete("booked"));
        };
        ActionExecutor cancelledFromAnotherThread = request -> {
            calls.add(request.path().value());
            // A client cancels while the step runs: the cancel must not wait for the run's end.
            Thread client = new Thread(() -> runner.cancel(ID));
            client.start();
            try {
                client.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertFalse(client.isAlive(), "cancel blocked on the running execution");
            return "screened";
        };

        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null), screenThenBook,
                cancelledFromAnotherThread);

        assertEquals("cancelled",
                assertInstanceOf(ExecutionResult.Failed.class, result).failure().errorClass());
        assertEquals(List.of("steps/screen"), calls);
        assertEquals(ExecutionStatus.CANCELLED, runner.status(ID).orElseThrow());
    }

    @Test
    void cancelShouldWinOverAnEndReachedAfterIt() {
        Interpreter screenThenFail = view -> view.outcome(SCREEN).isEmpty()
                ? List.of(new Command.ScheduleAction(SCREEN, "screen", null))
                : List.of(new Command.Fail(new Failure("refused", "Ship in maintenance")));
        ActionExecutor cancelling = request -> {
            runner.cancel(ID);
            return "screened";
        };

        ExecutionResult result = runner.run(ExecutionRequest.of(ID, null), screenThenFail,
                cancelling);

        assertEquals("cancelled",
                assertInstanceOf(ExecutionResult.Failed.class, result).failure().errorClass());
        assertEquals(ExecutionStatus.CANCELLED, runner.status(ID).orElseThrow());
    }

    @Test
    void cancelShouldNotChangeAnEndedExecution() {
        runner.run(new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), CLEARANCE,
                recording);
        runner.cancel(ID);
        assertEquals(ExecutionStatus.COMPLETED, runner.status(ID).orElseThrow());
    }

    @Test
    void startShouldRecordTheInputLabelsAndEntryOnce() {
        ExecutionRequest request = ExecutionRequest.of(ID, "{\"voyageId\":\"VOY-2026-042\"}");

        assertTrue(runner.start(request, "mcp:shipyard-ops/clear", Map.of("taskId", "t-1")));
        assertFalse(runner.start(ExecutionRequest.of(ID, "other"), "mcp:x/y", Map.of()));

        assertEquals("{\"voyageId\":\"VOY-2026-042\"}", runner.input(ID).orElseThrow());
        assertEquals("mcp:shipyard-ops/clear", runner.record(ID).orElseThrow().entry());
        assertEquals("sha256:tree", runner.record(ID).orElseThrow().specDigest());
        assertEquals(List.of(ID), runner.findByLabel("taskId", "t-1").stream()
                .map(r -> r.executionId()).toList());
        assertEquals(1, runner.list(ExecutionStatus.RUNNING, 10).size());
        assertEquals("sha256:tree", runner.specDigest());
    }

    @Test
    void aStartedExecutionShouldRunAndReplayAfterItsInput() {
        runner.start(ExecutionRequest.of(ID, "input"), "mcp:shipyard-ops/clear", Map.of());

        ExecutionResult first = runner.run(new ExecutionRequest(ID, null, Map.of(CONFIRM, true),
                null), CLEARANCE, recording);
        ExecutionResult second = runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertEquals(first, second);
        assertEquals(List.of("steps/screen", "steps/book-slot"), calls);
        assertEquals(JournalEntry.Kind.EXECUTION_STARTED, stores.journal().read(ID).get(0).kind());
    }

    @Test
    void inputShouldBeEmptyForAnExecutionNotStartedThatWay() {
        assertTrue(runner.input(ID).isEmpty());
        runner.create(ExecutionRequest.of(ID, null), "mcp:shipyard-ops");
        assertTrue(runner.input(ID).isEmpty());
    }

    @Test
    void failShouldEndARunningExecutionWithoutRunningIt() {
        runner.start(ExecutionRequest.of(ID, "input"), "mcp:shipyard-ops/clear", Map.of());

        runner.fail(ID, new Failure("spec-changed", "The capability changed"));
        runner.fail(new ExecutionId("unknown"), new Failure("spec-changed", "ignored"));

        assertEquals(ExecutionStatus.FAILED, runner.status(ID).orElseThrow());
        assertEquals("spec-changed", assertInstanceOf(ExecutionResult.Failed.class,
                runner.result(ID).orElseThrow()).failure().errorClass());
        runner.fail(ID, new Failure("other", "An ended execution keeps its result"));
        assertEquals("spec-changed", assertInstanceOf(ExecutionResult.Failed.class,
                runner.result(ID).orElseThrow()).failure().errorClass());
        assertTrue(calls.isEmpty());
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
    void cancelShouldNotOverwriteAnEndReachedAfterItReadTheStatus() {
        runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);
        runner.deliver(ID, CONFIRM, true);
        StaleIndex index = new StaleIndex(stores.index());
        JournaledRunner racing = new JournaledRunner(new Stores(stores.journal(), index,
                stores.queue(), stores.inbox(), stores.blobs()), STRINGS, CLOCK, "sha256:tree");
        // The cancel reads SUSPENDED; the run completes before the cancel writes its status.
        index.afterNextFind = () -> racing.run(ExecutionRequest.of(ID, null), CLEARANCE,
                recording);

        racing.cancel(ID);

        assertEquals(ExecutionStatus.COMPLETED, racing.status(ID).orElseThrow(),
                "The index must agree with the journal's EXECUTION_COMPLETED");
        assertEquals(new ExecutionResult.Completed("result of book-slot"),
                racing.result(ID).orElseThrow());
    }

    @Test
    void theJournaledIdempotencyKeyShouldBeTheOneTheExecutorReceives() {
        ExecutionId nested = new ExecutionId("task-1/steps");
        List<String> received = new ArrayList<>();
        ActionExecutor keys = request -> {
            received.add(request.idempotencyKey());
            return "ok";
        };

        runner.run(new ExecutionRequest(nested, null, Map.of(CONFIRM, true), null), CLEARANCE,
                keys);

        List<String> journaled = stores.journal().read(nested).stream()
                .filter(e -> e.kind() == JournalEntry.Kind.ACTION_SCHEDULED)
                .map(e -> new String(e.payload(), StandardCharsets.UTF_8)).toList();
        assertEquals(List.of("task-1/steps:steps/screen", "task-1/steps:steps/book-slot"),
                journaled);
        assertEquals(journaled, received);
    }

    @Test
    void locksShouldBeReleasedOnceRunsEnd() {
        runner.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);
        runner.start(ExecutionRequest.of(new ExecutionId("task-2"), "voyage"), "mcp:x", Map.of());
        runner.fail(new ExecutionId("task-2"), new Failure("spec-changed", "changed"));
        runner.run(new ExecutionRequest(new ExecutionId("task-1/steps"), null,
                Map.of(CONFIRM, true), null), CLEARANCE, recording);

        assertEquals(0, runner.lockCount(), "A lock must not outlive the runs of its execution");
    }

    @Test
    void aLockAwaitedByAnotherRunShouldBeKeptUntilThatRunEnds() throws Exception {
        CountDownLatch inScreen = new CountDownLatch(1);
        CountDownLatch finishScreen = new CountDownLatch(1);
        ActionExecutor blocking = request -> {
            inScreen.countDown();
            try {
                assertTrue(finishScreen.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "screened";
        };
        List<ExecutionResult> results = new CopyOnWriteArrayList<>();
        Thread first = new Thread(() -> results.add(
                runner.run(ExecutionRequest.of(ID, null), CLEARANCE, blocking)));
        first.start();
        assertTrue(inScreen.await(10, TimeUnit.SECONDS));
        assertEquals(1, runner.lockCount());
        Thread second = new Thread(() -> results.add(runner.run(
                new ExecutionRequest(ID, null, Map.of(CONFIRM, true), null), CLEARANCE,
                recording)));
        second.start();

        finishScreen.countDown();
        first.join(10_000);
        second.join(10_000);

        assertEquals(2, results.size(), "Both runs must end");
        assertEquals(ExecutionStatus.COMPLETED, runner.status(ID).orElseThrow());
        assertEquals(0, runner.lockCount());
    }

    /** An index whose next {@code find} answers what it read, after running a hook. */
    private static final class StaleIndex implements ExecutionIndex {

        private final ExecutionIndex delegate;
        Runnable afterNextFind;

        StaleIndex(ExecutionIndex delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean create(ExecutionRecord record) {
            return delegate.create(record);
        }

        @Override
        public Optional<ExecutionRecord> find(ExecutionId executionId) {
            Optional<ExecutionRecord> found = delegate.find(executionId);
            Runnable hook = afterNextFind;
            afterNextFind = null;
            if (hook != null) {
                hook.run();
            }
            return found;
        }

        @Override
        public void updateStatus(ExecutionId executionId, ExecutionStatus status) {
            delegate.updateStatus(executionId, status);
        }

        @Override
        public boolean compareAndSetStatus(ExecutionId executionId, ExecutionStatus expected,
                ExecutionStatus status) {
            return delegate.compareAndSetStatus(executionId, expected, status);
        }

        @Override
        public List<ExecutionRecord> list(ExecutionStatus status, int limit) {
            return delegate.list(status, limit);
        }

        @Override
        public List<ExecutionRecord> findByLabel(String key, String value) {
            return delegate.findByLabel(key, value);
        }
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
