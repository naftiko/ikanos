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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.engine.execution.Durability;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepElicitSpec;

/**
 * A {@code subscriptions/listen} stream of task status notifications ({@link McpSubscription}),
 * on the memory durability mode.
 */
class McpSubscriptionTest {

    private static final String ENTRY = "mcp:shipyard-ops/clear-for-departure";
    private static final String OWNER = "agent-7";
    private static final JsonNode ID = IntNode.valueOf(12);

    private final JournaledRunner runner =
            Durability.fromSpec(new DurabilitySpec("memory")).runner();
    private final McpTaskService tasks = new McpTaskService(runner, Clock.systemUTC(), null);
    private final BlockingQueue<ObjectNode> sent = new LinkedBlockingQueue<>();
    private final List<CountDownLatch> releases = new ArrayList<>();

    @AfterEach
    void releaseWork() {
        releases.forEach(CountDownLatch::countDown);
    }

    @Test
    void runShouldAcknowledgeOnlyTheOwnersKnownTasks() throws Exception {
        String own = create(OWNER, blocked());
        String foreign = create("agent-8", blocked());
        McpSubscription subscription = subscribe(List.of(own, foreign, "no-such-task"));

        start(subscription);

        ObjectNode ack = next();
        assertEquals("notifications/subscriptions/acknowledged", ack.path("method").asString());
        assertEquals(List.of(own), strings(ack.path("params").path("notifications")
                .path("taskIds")), "Another owner's task is left out as an unknown one is");
    }

    @Test
    void runShouldSendTheCurrentStateThenTheEndAndAnswerTheRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        String taskId = create(OWNER, () -> {
            release.await(10, TimeUnit.SECONDS);
            return IkanosMappers.json().createObjectNode().put("cleared", true);
        });
        McpSubscription subscription = subscribe(List.of(taskId));
        Thread runner = start(subscription);

        next(); // the acknowledgement
        ObjectNode working = next();
        release.countDown();
        ObjectNode completed = next();
        ObjectNode result = next();

        assertEquals("working", working.path("params").path("status").asString());
        assertEquals("completed", completed.path("params").path("status").asString());
        assertTrue(completed.path("params").path("result").path("cleared").asBoolean(),
                "The notification carries the result, as tasks/get does");
        assertEquals(12, result.path("id").asInt(), "The stream ends with the request's response");
        assertEquals(12, result.path("result").path("_meta")
                .path(McpSubscription.SUBSCRIPTION_ID).asInt());
        runner.join(5000);
        assertFalse(runner.isAlive(), "Once every task ended, the subscription ends");
    }

    @Test
    void everyMessageShouldCarryTheSubscriptionId() throws Exception {
        String taskId = create(OWNER, blocked());
        start(subscribe(List.of(taskId)));

        ObjectNode ack = next();
        ObjectNode state = next();

        assertEquals(12, ack.path("params").path("_meta").path(McpSubscription.SUBSCRIPTION_ID)
                .asInt());
        assertEquals(12, state.path("params").path("_meta")
                .path(McpSubscription.SUBSCRIPTION_ID).asInt());
    }

    @Test
    void aNotificationShouldCarryTheTaskWithoutAResultType() throws Exception {
        String taskId = create(OWNER, blocked());
        start(subscribe(List.of(taskId)));

        next(); // the acknowledgement
        JsonNode params = next().path("params");

        assertEquals(taskId, params.path("taskId").asString());
        assertTrue(params.has("createdAt") && params.has("pollIntervalMs"));
        assertFalse(params.has("resultType"), "A DetailedTask, not a result");
    }

    @Test
    void runShouldNotifyAQuestionThenTheWorkResumedByItsAnswer() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        Callable<JsonNode> work = () -> {
            if (rounds.incrementAndGet() == 1) {
                throw question();
            }
            release.await(10, TimeUnit.SECONDS);
            return IkanosMappers.json().createObjectNode().put("cleared", true);
        };
        String taskId = create(OWNER, work);
        awaitStatus(taskId, "input_required");
        start(subscribe(List.of(taskId)));
        next(); // the acknowledgement

        ObjectNode asking = next();
        assertTrue(tasks.update(taskId, OWNER, accept(), (entry, arguments) -> work));
        ObjectNode resumed = next();
        release.countDown();
        ObjectNode completed = next();

        assertEquals("input_required", asking.path("params").path("status").asString());
        assertTrue(asking.path("params").path("inputRequests").has("steps/confirm"),
                "The notification carries the questions, so the client needs no tasks/get");
        assertEquals("working", resumed.path("params").path("status").asString());
        assertEquals("completed", completed.path("params").path("status").asString());
    }

    @Test
    void aCancelledTaskShouldBeNotifiedAndEndTheSubscription() throws Exception {
        String taskId = create(OWNER, blocked());
        start(subscribe(List.of(taskId)));
        next(); // the acknowledgement
        next(); // working

        assertTrue(tasks.cancel(taskId, OWNER));

        assertEquals("cancelled", next().path("params").path("status").asString());
        assertTrue(next().has("result"), "Nothing is left to watch: the request is answered");
    }

    @Test
    void aChangeThatLeavesTheStatusAsItWasShouldNotBeSentAgain() throws Exception {
        String taskId = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(taskId));
        start(subscription);
        next(); // the acknowledgement
        next(); // working

        // A change that leaves the task working, as a drain that stops a round does.
        tasks.changed(runner.findByLabel(McpTaskService.TASK_ID, taskId).get(0).executionId());
        subscription.close();

        assertTrue(next().has("result"), "No second working notification before the end");
    }

    @Test
    void closeShouldAnswerTheRequestWhileTasksStillRun() throws Exception {
        String taskId = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(taskId));
        Thread runner = start(subscription);
        next(); // the acknowledgement
        next(); // working

        subscription.close();

        assertTrue(next().has("result"), "A graceful end answers the request");
        runner.join(5000);
        assertFalse(runner.isAlive());
    }

    @Test
    void cancelShouldEndTheSubscriptionWithoutAnsweringTheRequest() throws Exception {
        String taskId = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(taskId));
        Thread runner = start(subscription);
        next(); // the acknowledgement
        next(); // working

        subscription.cancel();
        runner.join(5000);

        assertFalse(runner.isAlive());
        assertNull(sent.poll(200, TimeUnit.MILLISECONDS), "The client cancelled: no response");
    }

    @Test
    void aSubscriptionToNoTaskShouldBeAcknowledgedEmptyAndAnswered() throws Exception {
        start(subscribe(List.of()));

        ObjectNode ack = next();

        assertTrue(ack.path("params").path("notifications").isObject());
        assertFalse(ack.path("params").path("notifications").has("taskIds"),
                "No type agreed to: the filter is empty");
        assertTrue(next().has("result"));
    }

    @Test
    void retainShouldLeaveOutTheTasksItRefuses() throws Exception {
        String kept = create(OWNER, blocked());
        String refused = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(kept, refused));

        subscription.retain(kept::equals);
        start(subscription);

        assertEquals(List.of(kept), strings(next().path("params").path("notifications")
                .path("taskIds")));
    }

    @Test
    void anExpiredSubscriptionShouldEndGracefully() throws Exception {
        String taskId = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(taskId));
        subscription.expireAt(Instant.now().minusSeconds(1));

        start(subscription);
        next(); // the acknowledgement
        next(); // working

        assertTrue(next().has("result"), "The token expired: the request is answered");
    }

    @Test
    void aSubscriptionShouldAgreeToAtMostMaxTasks() throws Exception {
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i <= McpSubscription.MAX_TASKS; i++) {
            taskIds.add(create(OWNER, () -> IkanosMappers.json().createObjectNode()));
        }

        assertEquals(McpSubscription.MAX_TASKS, subscribe(taskIds).agreed().size());
    }

    @Test
    void aSubscriptionThatEndedShouldStopHearingOfChanges() throws Exception {
        String taskId = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(taskId));
        Thread thread = start(subscription);
        next(); // the acknowledgement
        next(); // working
        assertEquals(1, tasks.listenerCount());

        subscription.cancel();
        thread.join(5000);

        assertEquals(0, tasks.listenerCount(), "The subscription removed its listener");
    }

    @Test
    void aSinkThatFailsShouldEndTheSubscription() throws Exception {
        String taskId = create(OWNER, blocked());
        McpSubscription subscription = subscribe(List.of(taskId));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                subscription.run(message -> {
                    throw new IOException("The stream is gone");
                });
            } catch (IOException e) {
                failure.set(e);
            }
        });
        runner.join(5000);

        assertNotNull(failure.get());
        assertFalse(runner.isAlive());
    }

    private String create(String owner, Callable<JsonNode> work) {
        return tasks.create(ENTRY, owner, IkanosMappers.json().createObjectNode(), work)
                .path("taskId").asString();
    }

    private Callable<JsonNode> blocked() {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        return () -> {
            release.await(10, TimeUnit.SECONDS);
            return IkanosMappers.json().createObjectNode();
        };
    }

    private McpSubscription subscribe(List<String> taskIds) {
        return new McpSubscription(ID, OWNER, taskIds, tasks, Clock.systemUTC());
    }

    private Thread start(McpSubscription subscription) {
        return Thread.ofVirtual().start(() -> {
            try {
                subscription.run(sent::add);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private ObjectNode next() throws InterruptedException {
        ObjectNode message = sent.poll(10, TimeUnit.SECONDS);
        assertNotNull(message, "A message within 10 s");
        return message;
    }

    private void awaitStatus(String taskId, String status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !status.equals(
                tasks.get(taskId, OWNER).orElseThrow().path("status").asString())) {
            Thread.sleep(20);
        }
        assertEquals(status, tasks.get(taskId, OWNER).orElseThrow().path("status").asString());
    }

    private static InputRequiredException question() {
        return new InputRequiredException(List.of(new InputRequiredException.Question(
                "steps/confirm", new OperationStepElicitSpec("confirm", "Confirm?",
                        Map.of("ok", Map.of("type", "boolean"))))));
    }

    private static JsonNode accept() {
        return IkanosMappers.json().createObjectNode().set("steps/confirm",
                IkanosMappers.json().createObjectNode().put("action", "accept"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
