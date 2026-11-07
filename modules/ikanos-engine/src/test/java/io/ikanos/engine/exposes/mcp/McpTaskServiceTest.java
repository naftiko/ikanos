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
        McpTaskService.Round round = now.inRounds("mrtr:ops/tools/call/ask", "alice", null, null,
                null, () -> {
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
    void anInlineCallStillWithinItsStateExpiryShouldNotBeSwept() {
        JournaledRunner runner = new JournaledRunner(
                MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
                Clock.systemUTC(), "test");
        McpTaskService service = new McpTaskService(runner, Clock.systemUTC(), null);
        McpTaskService.Round round = service.inRounds("mrtr:ops/tools/call/ask", "alice", null,
                null, null, () -> {
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
