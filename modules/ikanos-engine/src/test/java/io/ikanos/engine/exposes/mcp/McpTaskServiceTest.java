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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import io.ikanos.engine.execution.ExecutionTree;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.execution.Executions;
import io.ikanos.engine.execution.NodeRunner;
import io.ikanos.engine.execution.StepRun;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.OperationStepSpec;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.FaultInjectingStores;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.engine.execution.Durability;
import io.ikanos.engine.execution.JsonPayloadCodec;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.util.IkanosMappers;

public class McpTaskServiceTest {

    private final McpTaskService service = new McpTaskService(
            Durability.fromSpec(new DurabilitySpec("memory")).runner(), Clock.systemUTC(), null);

    @Test
    void anInlineCallLeftUnansweredPastItsStateExpiryShouldFail() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        McpTaskService now = new McpTaskService(runner, Clock.systemUTC(), null);
        InlineRounds.Round round = now.inRounds("mrtr:ops/tools/call/ask", "alice", null, null,
                0, null, () -> {
                    throw new InputRequiredException(List.of(
                            new InputRequiredException.Question(
                                    "steps/confirm",
                                    new OperationStepElicitSpec("confirm",
                                            "Confirm?", Map.of("ok", Map.of("type", "boolean"))))));
                });
        assertEquals(ExecutionStatus.SUSPENDED, runner.status(round.executionId()).orElseThrow());

        // Swept by a process whose clock is past the request state's expiry.
        new McpTaskService(runner, Clock.offset(Clock.systemUTC(),
                McpRequestState.TTL.plusMinutes(1)), null).sweepUnanswered();

        assertEquals(ExecutionStatus.FAILED, runner.status(round.executionId()).orElseThrow());
    }

    @Test
    void olderSuspendedTasksShouldNotHideAnExpiredInlineCallFromTheSweep() {
        Stores stores = MemoryStoreProvider.openStores(Clock.systemUTC());
        JournaledRunner runner = new JournaledRunner(stores, new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        // More suspended tasks than one sweep examines, all older than the inline call.
        Instant older = Instant.now().minus(Duration.ofDays(1));
        for (int i = 0; i < InlineRounds.SWEEP_LIMIT; i++) {
            stores.index().create(new ExecutionRecord(ExecutionId.random(), "test", "task",
                    ExecutionStatus.SUSPENDED, older, null, Map.of("task", "t" + i)));
        }
        InlineRounds.Round round = new McpTaskService(runner, Clock.systemUTC(), null)
                .inRounds("mrtr:ops/tools/call/ask", "alice", null, null, 0, null, () -> {
                    throw new InputRequiredException(List.of(
                            new InputRequiredException.Question(
                                    "steps/confirm",
                                    new OperationStepElicitSpec("confirm",
                                            "Confirm?", Map.of("ok", Map.of("type", "boolean"))))));
                });

        new McpTaskService(runner, Clock.offset(Clock.systemUTC(),
                McpRequestState.TTL.plusMinutes(1)), null).sweepUnanswered();

        assertEquals(ExecutionStatus.FAILED, runner.status(round.executionId()).orElseThrow());
    }

    @Test
    void anInlineCallStillWithinItsStateExpiryShouldNotBeSwept() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        McpTaskService service = new McpTaskService(runner, Clock.systemUTC(), null);
        InlineRounds.Round round = service.inRounds("mrtr:ops/tools/call/ask", "alice", null,
                null, 0, null, () -> {
                    throw new InputRequiredException(List.of(
                            new InputRequiredException.Question(
                                    "steps/confirm",
                                    new OperationStepElicitSpec("confirm",
                                            "Confirm?", Map.of("ok", Map.of("type", "boolean"))))));
                });

        new McpTaskService(runner, Clock.systemUTC(), null).sweepUnanswered();

        assertEquals(ExecutionStatus.SUSPENDED, runner.status(round.executionId()).orElseThrow());
    }

    @Test
    void anInlineCallShouldExpireFromItsLastQuestionNotFromItsStart() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        java.util.concurrent.atomic.AtomicInteger rounds =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Callable<tools.jackson.databind.JsonNode> work = () -> {
            throw new InputRequiredException(List.of(new InputRequiredException.Question(
                    rounds.incrementAndGet() == 1 ? "steps/confirm" : "steps/again",
                    new OperationStepElicitSpec("confirm", "Confirm?",
                            Map.of("ok", Map.of("type", "boolean"))))));
        };
        InlineRounds.Round first = new McpTaskService(runner, Clock.systemUTC(), null)
                .inRounds("mrtr:ops/tools/call/ask", "alice", null, null, 0, null, work);

        // Nine minutes later, the answer comes, and the call asks again: a new state, valid
        // for another ten minutes.
        Clock later = Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(9));
        new McpTaskService(runner, later, null).inRounds("mrtr:ops/tools/call/ask", "alice",
                null, first.executionId(), first.round(), IkanosMappers.json().createObjectNode()
                        .set("steps/confirm", IkanosMappers.json().createObjectNode()
                                .put("action", "accept")),
                work);
        assertEquals(2, rounds.get());

        new McpTaskService(runner, Clock.offset(Clock.systemUTC(),
                McpRequestState.TTL.plusMinutes(1)), null).sweepUnanswered();

        assertEquals(ExecutionStatus.SUSPENDED, runner.status(first.executionId()).orElseThrow(),
                "Asked again at minute 9, the call is still answerable at minute 11");

        new McpTaskService(runner, Clock.offset(Clock.systemUTC(),
                McpRequestState.TTL.plusMinutes(10)), null).sweepUnanswered();

        assertEquals(ExecutionStatus.FAILED, runner.status(first.executionId()).orElseThrow());
    }

    @Test
    void aStateReissuedForAnUnansweredQuestionShouldKeepTheRoundsExpiry() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        Callable<JsonNode> work = askingAgain(new AtomicInteger());
        InlineRounds.Round first = new McpTaskService(runner, Clock.systemUTC(), null)
                .inRounds("mrtr:ops/tools/call/ask", "alice", null, null, 0, null, work);

        // Retried at minute 9 without an answer: the same question, still open.
        InlineRounds.Round retried = new McpTaskService(runner,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(9)), null)
                .inRounds("mrtr:ops/tools/call/ask", "alice", null, first.executionId(),
                        first.round(), null, work);

        assertEquals(first.round(), retried.round());
        assertEquals(first.expiresAt(), retried.expiresAt(),
                "The state issued again expires when the sweep fails the call, not later");
        assertFalse(retried.expiresAt().isAfter(Instant.now().plus(McpRequestState.TTL)));
    }

    @Test
    void anExpiredInlineCallShouldCancelWhatItWaitsOn() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        InlineRounds.Round round = new McpTaskService(runner, Clock.systemUTC(), null)
                .inRounds("mrtr:ops/tools/call/ask", "alice", null, null, 0, null,
                        askingAgain(new AtomicInteger()));
        List<ExecutionId> cancelled = new CopyOnWriteArrayList<>();

        new McpTaskService(runner, Clock.offset(Clock.systemUTC(),
                McpRequestState.TTL.plusMinutes(1)), null)
                .whenCancelled(cancelled::add).sweepUnanswered();

        assertEquals(ExecutionStatus.FAILED, runner.status(round.executionId()).orElseThrow());
        assertEquals(List.of(round.executionId()), cancelled,
                "An upstream task the expired call waited on is cancelled too");
    }

    @Test
    void aTaskShouldShowEveryQuestionUnderAKeyNoOtherRoundShows() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        McpTaskService rounds = new McpTaskService(runner, Clock.systemUTC(), null);
        List<List<String>> asked = List.of(List.of("steps/confirm"),
                List.of("steps/confirm", "steps/confirm@2"), List.of("steps/confirm@2"));
        AtomicInteger round = new AtomicInteger();
        Callable<JsonNode> work = () -> {
            List<InputRequiredException.Question> questions = new java.util.ArrayList<>();
            for (String key : asked.get(Math.min(round.getAndIncrement(), 2))) {
                questions.add(new InputRequiredException.Question(key,
                        new OperationStepElicitSpec("confirm", "Confirm?",
                                Map.of("ok", Map.of("type", "boolean")))));
            }
            throw new InputRequiredException(questions);
        };
        String entry = "mrtr:ops/tools/call/ask";
        InlineRounds.Round first = rounds.inRounds(entry, "alice", null, null, 0, null, work);
        ObjectNodeAnswers answers = new ObjectNodeAnswers();
        InlineRounds.Round second = rounds.inRounds(entry, "alice", null, first.executionId(),
                first.round(), answers.to("steps/confirm"), work);
        var view = runner.view(first.executionId());

        assertEquals(List.of("steps/confirm@2.2", "steps/confirm@2"), keys(
                InputRounds.taskQuestions(view, second.round())),
                "Asked again, a key takes a form no question of the round uses: none is dropped");
        assertEquals("steps/confirm", InputRounds.taskAnswers(view, second.round(),
                answers.to("steps/confirm@2.2")).propertyNames().iterator().next());

        InlineRounds.Round third = rounds.inRounds(entry, "alice", null, first.executionId(),
                second.round(), answers.to("steps/confirm", "steps/confirm@2"), work);
        view = runner.view(first.executionId());

        assertEquals(List.of("steps/confirm@2@3"), keys(
                InputRounds.taskQuestions(view, third.round())),
                "A literal key an earlier round showed is qualified too");
        assertTrue(InputRounds.taskAnswers(view, third.round(),
                answers.to("steps/confirm@2")).isEmpty(),
                "A retried update of round 2 never answers round 3");
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new java.util.ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    /** Accepting responses to the given keys. */
    private static final class ObjectNodeAnswers {
        JsonNode to(String... keys) {
            var responses = IkanosMappers.json().createObjectNode();
            for (String key : keys) {
                responses.set(key, IkanosMappers.json().createObjectNode().put("action", "accept"));
            }
            return responses;
        }
    }

    @Test
    void aTaskCancelledBetweenAnUpstreamTasksCreationAndItsFirstPollShouldCancelIt()
            throws Exception {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        List<ExecutionId> cancelled = new CopyOnWriteArrayList<>();
        McpTaskService tasks = new McpTaskService(runner, Clock.systemUTC(), null)
                .whenCancelled(cancelled::add);
        CountDownLatch created = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ExecutionId> root =
                new java.util.concurrent.atomic.AtomicReference<>();
        // A consumed call's sub-run, as UpstreamCall runs it: create, then poll.
        Callable<JsonNode> work = () -> {
            io.ikanos.engine.execution.DurableScope.runJournaled(
                    view -> view.outcome(io.ikanos.durable.NodePath.of("create")).isEmpty()
                            ? List.of(new io.ikanos.durable.Command.ScheduleAction(
                                    io.ikanos.durable.NodePath.of("create"), "create", null))
                            : List.of(new io.ikanos.durable.Command.ScheduleAction(
                                    io.ikanos.durable.NodePath.of("await"), "await", null)),
                    action -> {
                        if (action.path().value().equals("create")) {
                            created.countDown();
                            // The cancel lands while the creation is in flight: its reply is
                            // journaled, and the run stops before the first poll.
                            while (root.get() == null || runner.status(root.get())
                                    .orElse(null) != ExecutionStatus.CANCELLED) {
                                Thread.onSpinWait();
                            }
                            return IkanosMappers.json().createObjectNode().put("kind", "task")
                                    .put("taskId", "up-1").put("namespace", "customs");
                        }
                        throw new AssertionError("Polled after the cancel");
                    });
            return IkanosMappers.json().createObjectNode();
        };
        String taskId = tasks.create("mcp:ops/declare", "alice", null, work).path("taskId")
                .asString();
        root.set(runner.findByLabel(McpTaskService.TASK_ID, taskId).get(0).executionId());
        assertTrue(created.await(5, TimeUnit.SECONDS));

        tasks.cancel(taskId, "alice");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cancelled.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(root.get()), cancelled,
                "Once the worker stops, what it left waiting upstream is cancelled");
    }

    @Test
    void anInlineRoundCancelledBetweenAnUpstreamTasksCreationAndItsFirstPollShouldCancelIt() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        List<ExecutionId> cancelled = new CopyOnWriteArrayList<>();
        McpTaskService rounds = new McpTaskService(runner, Clock.systemUTC(), null)
                .whenCancelled(cancelled::add);
        java.util.concurrent.atomic.AtomicReference<ExecutionId> root =
                new java.util.concurrent.atomic.AtomicReference<>();
        // A consumed call's sub-run, as UpstreamCall runs it: create, then poll.
        Callable<JsonNode> work = () -> {
            io.ikanos.engine.execution.DurableScope.runJournaled(
                    view -> view.outcome(io.ikanos.durable.NodePath.of("create")).isEmpty()
                            ? List.of(new io.ikanos.durable.Command.ScheduleAction(
                                    io.ikanos.durable.NodePath.of("create"), "create", null))
                            : List.of(new io.ikanos.durable.Command.ScheduleAction(
                                    io.ikanos.durable.NodePath.of("await"), "await", null)),
                    action -> {
                        if (action.path().value().equals("create")) {
                            // The call is cancelled while the creation is in flight, as a REST
                            // DELETE would: its reply is journaled, and the round stops before
                            // the first poll.
                            root.set(runner.findByLabel(InlineRounds.KIND, InlineRounds.MRTR)
                                    .get(0).executionId());
                            runner.cancel(root.get());
                            return IkanosMappers.json().createObjectNode().put("kind", "task")
                                    .put("taskId", "up-1").put("namespace", "customs");
                        }
                        throw new AssertionError("Polled after the cancel");
                    });
            return IkanosMappers.json().createObjectNode();
        };

        try {
            rounds.inRounds("mrtr:ops/tools/call/declare", "alice", null, null, 0, null, work);
        } catch (RuntimeException | io.ikanos.engine.execution.DurableScope.Stopped e) {
            // The round stops at the cancel; what matters here is the cleanup.
        }

        assertEquals(List.of(root.get()), cancelled,
                "Once the round stops, what it left waiting upstream is cancelled");
    }

    /** Work that asks the same question every round, as an upstream rejecting answers does. */
    private static Callable<JsonNode> askingAgain(AtomicInteger rounds) {
        return () -> {
            rounds.incrementAndGet();
            throw new InputRequiredException(List.of(new InputRequiredException.Question(
                    "steps/confirm", new OperationStepElicitSpec("confirm", "Confirm?",
                            Map.of("ok", Map.of("type", "boolean"))))));
        };
    }

    private static JsonNode accept() {
        return IkanosMappers.json().createObjectNode().set("steps/confirm",
                IkanosMappers.json().createObjectNode().put("action", "accept"));
    }

    @Test
    void aStateOfAnEarlierRoundShouldNotAnswerALaterQuestionWithTheSameKey() {
        AtomicInteger rounds = new AtomicInteger();
        Callable<JsonNode> work = askingAgain(rounds);
        String entry = "mrtr:ops/tools/call/ask";
        InlineRounds.Round first = service.inRounds(entry, "alice", null, null, 0, null, work);
        InlineRounds.Round second = service.inRounds(entry, "alice", null, first.executionId(),
                first.round(), accept(), work);
        assertEquals(2, rounds.get());
        assertEquals(first.round() + 1, second.round());

        // The first answer retried with its own, still valid, state.
        InlineRounds.Round retried = service.inRounds(entry, "alice", null,
                first.executionId(), first.round(), accept(), work);

        assertEquals(2, rounds.get(), "The earlier answer is not taken for the new question");
        assertEquals(second.round(), retried.round(), "The retry gets the question asked now");
        assertTrue(retried.questions().has("steps/confirm"));

        service.inRounds(entry, "alice", null, first.executionId(), retried.round(), accept(),
                work);
        assertEquals(3, rounds.get(), "The state of the current round answers it");
    }

    @Test
    void aStateShouldNotResumeACallStartedForAnotherEntry() {
        AtomicInteger rounds = new AtomicInteger();
        Callable<JsonNode> work = askingAgain(rounds);
        InlineRounds.Round first = service.inRounds("mrtr:ops-a/tools/call/ask", "alice", null,
                null, 0, null, work);

        assertThrows(IllegalArgumentException.class, () -> service.inRounds(
                "mrtr:ops-b/tools/call/ask", "alice", null, first.executionId(), first.round(),
                accept(), work), "Another adapter, same tool name and arguments");
        assertEquals(1, rounds.get());
        assertTrue(service.inRounds("mrtr:ops-a/tools/call/ask", "alice", null,
                first.executionId(), first.round(), null, work).questions() != null,
                "The call is still waiting for its own adapter's answer");
    }

    @Test
    void anotherPrincipalShouldNeitherSeeNorCancelATask() {
        String taskId = service.create("mcp:shipyard-ops/clear-for-departure", "agent-7",
                IkanosMappers.json().createObjectNode(),
                () -> IkanosMappers.json().createObjectNode()).path("taskId").asString();

        assertTrue(service.get(taskId, "agent-7").isPresent());
        assertTrue(service.get(taskId, "agent-8").isEmpty());
        assertFalse(service.cancel(taskId, "agent-8"));
        assertTrue(service.cancel(taskId, "agent-7"));
    }

    @Test
    void theStoreShouldKeepADigestOfThePrincipalNotThePrincipal() {
        service.create("mcp:shipyard-ops/clear-for-departure", "agent-7", null,
                () -> IkanosMappers.json().createObjectNode());

        assertEquals(64, McpTaskService.digest("agent-7").length());
        assertFalse(McpTaskService.digest("agent-7").contains("agent-7"));
        assertFalse(McpTaskService.digest("agent-7").equals(McpTaskService.digest("agent-8")));
    }

    @Test
    void resumeShouldSkipEntriesItDoesNotServe() {
        service.create("mcp:shipyard-ops/clear-for-departure", "agent-7", null,
                () -> {
                    Thread.sleep(200);
                    return IkanosMappers.json().createObjectNode();
                });

        assertEquals(0, service.resume((entry, arguments) -> null));
    }

    @Test
    void resumeShouldFailATaskWhoseArgumentsWereNeverRecorded() {
        Stores stores = MemoryStoreProvider.openStores(Clock.systemUTC());
        JournaledRunner beforeCrash = new JournaledRunner(FaultInjectingStores.wrap(stores,
                FaultInjectingStores.Boundary.BEFORE_STARTED, 1), new JsonPayloadCodec(),
                Clock.systemUTC(), "sha256:spec");
        assertThrows(FaultInjectingStores.Crash.class,
                () -> new McpTaskService(beforeCrash, Clock.systemUTC(), null).create(
                        "mcp:shipyard-ops/clear-for-departure", "agent-7",
                        IkanosMappers.json().createObjectNode().put("voyageId", "VOY-2026-042"),
                        () -> IkanosMappers.json().createObjectNode()));

        JournaledRunner afterRestart =
                new JournaledRunner(stores, new JsonPayloadCodec(), Clock.systemUTC(), "sha256:spec");
        ExecutionId task = afterRestart.list(ExecutionStatus.RUNNING, 10).get(0).executionId();
        AtomicInteger runs = new AtomicInteger();
        int resumed = new McpTaskService(afterRestart, Clock.systemUTC(), null).resume(
                (entry, arguments) -> () -> {
                    runs.incrementAndGet();
                    return IkanosMappers.json().createObjectNode();
                });

        assertEquals(0, resumed);
        assertEquals(0, runs.get(), "A task without its arguments must not run");
        assertEquals(ExecutionStatus.FAILED, afterRestart.status(task).orElseThrow());
        assertEquals(McpTaskService.INCOMPLETE, ((ExecutionResult.Failed) afterRestart
                .result(task).orElseThrow()).failure().errorClass());
    }

    @Test
    void drainShouldStopATaskAtItsNextActionForTheNextStartToResume() throws Exception {
        JournaledRunner runner = new JournaledRunner(MemoryStoreProvider.openStores(
                Clock.systemUTC()), new JsonPayloadCodec(), Clock.systemUTC(), "sha256:spec");
        CountDownLatch inBooking = new CountDownLatch(1);
        CountDownLatch finishBooking = new CountDownLatch(1);
        List<String> invoked = new CopyOnWriteArrayList<>();
        NodeRunner nodes = (node, run) -> {
            String name = node.step().getName();
            invoked.add(name);
            if (invoked.size() == 1) {
                inBooking.countDown();
                try {
                    finishBooking.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            JsonNode output = IkanosMappers.json().createObjectNode().put("step", name);
            run.stepContext().storeStepOutput(name, output);
            return output;
        };
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("book-slot", new OperationStepCallSpec("book-slot", "port.book-slot"));
        steps.put("declare", new OperationStepCallSpec("declare", "customs.declare"));
        ExecutionTree tree = ExecutionTree.compile(steps, null);
        Callable<JsonNode> work = () -> {
            Executions.runSteps(tree, new StepRun(null), nodes);
            return IkanosMappers.json().createObjectNode();
        };

        McpTaskService first = new McpTaskService(runner, Clock.systemUTC(), null);
        String taskId = first.create("mcp:shipyard-ops/clear-for-departure", "agent-7",
                IkanosMappers.json().createObjectNode(), work).path("taskId").asString();
        assertTrue(inBooking.await(10, TimeUnit.SECONDS));
        AtomicInteger cutShort = new AtomicInteger(-1);
        Thread draining = Thread.ofVirtual()
                .start(() -> cutShort.set(first.drain(Duration.ofSeconds(10))));
        Thread.sleep(100);
        finishBooking.countDown();
        draining.join();

        assertEquals(0, cutShort.get(), "The task stopped at its next action, within the timeout");
        assertEquals(List.of("book-slot"), invoked, "No action starts once the drain began");
        assertEquals("working", first.get(taskId, "agent-7").orElseThrow().path("status")
                .asString());
        assertThrows(IllegalStateException.class, () -> first.create(
                "mcp:shipyard-ops/clear-for-departure", "agent-7", null, work));

        McpTaskService next = new McpTaskService(runner, Clock.systemUTC(), null);
        assertEquals(1, next.resume((entry, arguments) -> work));
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && "working".equals(
                next.get(taskId, "agent-7").orElseThrow().path("status").asString())) {
            Thread.sleep(20);
        }
        assertEquals("completed", next.get(taskId, "agent-7").orElseThrow().path("status")
                .asString());
        assertEquals(List.of("book-slot", "declare"), invoked,
                "The booking finished before the drain is not made again");
    }

    @Test
    void anUnknownTaskShouldBeNeitherFoundNorCancelled() {
        assertTrue(service.get("unknown", "agent-7").isEmpty());
        assertTrue(service.get(null, "agent-7").isEmpty());
        assertFalse(service.cancel("unknown", "agent-7"));
    }
}
