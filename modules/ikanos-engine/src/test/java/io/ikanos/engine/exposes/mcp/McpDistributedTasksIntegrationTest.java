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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.jdbc.PostgresStoreProvider;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP tasks in the {@code distributed} mode: two capabilities loaded on the same PostgreSQL
 * database stand for two replicas behind a load balancer. A task created on one is read, answered
 * and finished on the other, and a replica takes over the task of a replica that stopped once its
 * lease expires.
 */
public class McpDistributedTasksIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final String ENTRY = "mcp:shipyard-ops/clear-for-departure";
    private static final String KEY = "steps/confirm";
    private static final String CLEAR =
            "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-051\"}}";
    private static final String ACCEPT =
            "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}";
    /** A test key of 128 zero bits, shared by both replicas as the distributed mode requires. */
    private static final String SEALING_KEY =
            java.util.Base64.getEncoder().encodeToString(new byte[16]);
    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    private static EmbeddedPostgres server;

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();
    private final AtomicInteger bookings = new AtomicInteger();
    private final List<Capability> replicas = new ArrayList<>();

    private Component upstream;
    private int port;
    private String url;

    @BeforeAll
    static void startDatabase() throws IOException {
        server = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String schema = "replicas_" + SCHEMAS.incrementAndGet();
        try (Connection c = server.getPostgresDatabase().getConnection();
                Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        url = server.getJdbcUrl("postgres", "postgres") + "&currentSchema=" + schema;

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, port);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/slots", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        int n = bookings.incrementAndGet();
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"slotId\":\"slot-" + n + "\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        upstream.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Capability replica : replicas) {
            replica.getDurability().close();
        }
        upstream.stop();
    }

    private Capability replica() throws Exception {
        return replica("A task that asks before it finishes, on replicas sharing a store");
    }

    /** A replica; another {@code description} stands for another version of the capability. */
    private Capability replica(String description) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "%s"
                capability:
                  consumes:
                  - type: http
                    namespace: port
                    baseUri: "http://localhost:%d"
                    resources:
                      slots:
                        path: /slots
                        operations:
                          book-slot:
                            method: POST
                  aggregates:
                  - namespace: departure
                    flows:
                      clear-for-departure:
                        description: Book a slot, then ask the duty officer to confirm.
                        semantics:
                          interaction:
                            mode: task
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to clear
                        steps:
                          book-slot:
                            type: call
                            call: port.book-slot
                          confirm:
                            type: elicit
                            message: Clear the voyage for departure?
                            fields:
                              confirmed:
                                type: boolean
                                description: Whether the duty officer clears it
                        mappings:
                        - target: confirmed
                          value: $.confirm.confirmed
                        outputParameters:
                          confirmed:
                            type: boolean
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      clear-for-departure:
                        description: Clear a voyage for departure.
                        ref: departure.clear-for-departure
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: distributed
                      store:
                        type: postgres
                        url: "%s"
                      sealingKey: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), description, port, url,
                SEALING_KEY);
        Capability capability = new Capability(yamlMapper.readValue(yaml, IkanosSpec.class));
        replicas.add(capability);
        return capability;
    }

    private static McpServerAdapter mcp(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("extensions").putObject(TASKS);
        capabilities.putObject("elicitation");
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private JsonNode get(McpServerAdapter adapter, String taskId) throws Exception {
        return dispatch(adapter, "tasks/get", "{\"taskId\":\"" + taskId + "\"}").path("result");
    }

    /** Polls until the task leaves {@code working}. */
    private JsonNode awaitSettled(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = get(adapter, taskId);
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not settle");
    }

    @Test
    void aTaskCreatedOnOneReplicaShouldBeAnsweredAndFinishedOnAnother() throws Exception {
        McpServerAdapter podA = mcp(replica());
        McpServerAdapter podB = mcp(replica());

        String taskId = dispatch(podA, "tools/call", CLEAR).path("result").path("taskId")
                .asString();
        assertEquals("input_required", awaitSettled(podA, taskId).path("status").asString());

        JsonNode waiting = get(podB, taskId);
        assertEquals("input_required", waiting.path("status").asString(), waiting.toString());
        assertTrue(waiting.path("inputRequests").has(KEY), waiting.toString());

        JsonNode ack = dispatch(podB, "tasks/update", "{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"" + KEY + "\":" + ACCEPT + "}}");
        assertEquals("complete", ack.path("result").path("resultType").asString(),
                ack.toString());

        JsonNode done = awaitSettled(podB, taskId);
        assertEquals("completed", done.path("status").asString(), done.toString());
        assertTrue(done.path("result").toString().contains("\"confirmed\":true"),
                done.toString());
        // Both replicas read the same task; lastUpdatedAt alone is kept per replica, in memory.
        JsonNode onA = awaitSettled(podA, taskId);
        assertEquals("completed", onA.path("status").asString(), onA.toString());
        assertEquals(done.path("result"), onA.path("result"));
        assertEquals(1, bookings.get(), "The slot booked before the question is booked once");
    }

    @Test
    void aTaskShouldBeCancelledFromAnotherReplica() throws Exception {
        McpServerAdapter podA = mcp(replica());
        McpServerAdapter podB = mcp(replica());
        String taskId = dispatch(podA, "tools/call", CLEAR).path("result").path("taskId")
                .asString();
        awaitSettled(podA, taskId);

        dispatch(podB, "tasks/cancel", "{\"taskId\":\"" + taskId + "\"}");

        assertEquals("cancelled", get(podA, taskId).path("status").asString());
    }

    @Test
    void aReplicaShouldTakeOverTheTaskOfAStoppedReplicaOnceItsLeaseExpired() throws Exception {
        Capability before = replica();
        McpServerAdapter podB = mcp(replica());
        // Pod A recorded the task and died while running it, holding the execution's lease.
        ExecutionId id = new ExecutionId("task-of-a-stopped-replica");
        before.getDurability().runner().start(
                ExecutionRequest.of(id, jsonMapper.readTree("{\"voyageId\":\"VOY-2026-051\"}")),
                ENTRY, Map.of(McpTaskService.TASK_ID, "task-a", McpTaskService.PRINCIPAL,
                        McpTaskService.digest(McpCallContext.principal())));
        try (Stores store = PostgresStoreProvider.openStores(url, null, null,
                Clock.systemUTC())) {
            store.queue().acquire(id, "pod-a", Duration.ofMillis(500)).orElseThrow();

            assertEquals(0, podB.getTaskService().takeOver(podB.taskWork()),
                    "Pod B leaves a task whose lease is held");
            assertEquals(0, podB.resumeTasks(), "So does a replica starting");
            assertEquals(0, bookings.get());
            assertEquals("working", get(podB, "task-a").path("status").asString());

            Thread.sleep(500);
            podB.getTaskService().takeOver(podB.taskWork());
        }

        JsonNode waiting = awaitSettled(podB, "task-a");
        assertEquals("input_required", waiting.path("status").asString(), waiting.toString());
        assertEquals(1, bookings.get());
    }

    @Test
    void answersSentToAReplicaWhileAnotherRunsTheTaskShouldBeKept() throws Exception {
        McpServerAdapter podA = mcp(replica());
        Capability replicaB = replica();
        McpServerAdapter podB = mcp(replicaB);
        String taskId = dispatch(podA, "tools/call", CLEAR).path("result").path("taskId")
                .asString();
        awaitSettled(podA, taskId);
        // Pod A is running the round that asks: the task is RUNNING, under pod A's lease.
        ExecutionId id = replicaB.getDurability().runner().findByLabel(McpTaskService.TASK_ID,
                taskId).get(0).executionId();
        try (Stores store = PostgresStoreProvider.openStores(url, null, null,
                Clock.systemUTC())) {
            store.index().compareAndSetStatus(id, ExecutionStatus.SUSPENDED,
                    ExecutionStatus.RUNNING);
            store.queue().acquire(id, "pod-a", Duration.ofSeconds(30)).orElseThrow();

            dispatch(podB, "tasks/update", "{\"taskId\":\"" + taskId
                    + "\",\"inputResponses\":{\"" + KEY + "\":" + ACCEPT + "}}");

            assertTrue(replicaB.getDurability().runner().hasPendingUpdates(id),
                    "The answers wait in the inbox for the replica running the task");
        }
    }

    @Test
    void anUpdateShouldFailATaskRecordedByAnotherVersionOfTheCapability() throws Exception {
        McpServerAdapter podA = mcp(replica());
        McpServerAdapter newer = mcp(replica("The next version of the capability"));
        String taskId = dispatch(podA, "tools/call", CLEAR).path("result").path("taskId")
                .asString();
        awaitSettled(podA, taskId);

        dispatch(newer, "tasks/update", "{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"" + KEY + "\":" + ACCEPT + "}}");

        JsonNode failed = awaitSettled(podA, taskId);
        assertEquals("failed", failed.path("status").asString(), failed.toString());
        assertTrue(failed.toString().contains("changed"), failed.toString());
        assertEquals(1, bookings.get(), "The newer replica runs none of the older steps");
    }

    @Test
    void aSweepShouldRunAWaitingTaskWhoseAnswersArrived() throws Exception {
        McpServerAdapter podA = mcp(replica());
        Capability replicaB = replica();
        McpServerAdapter podB = mcp(replicaB);
        String taskId = dispatch(podA, "tools/call", CLEAR).path("result").path("taskId")
                .asString();
        awaitSettled(podA, taskId);
        assertEquals(0, podB.getTaskService().takeOver(podB.taskWork()),
                "A task waiting on a person costs a sweep nothing");

        // The answers reached the inbox, but the replica that delivered them stopped before
        // running the task.
        JournaledRunner runner = replicaB.getDurability().runner();
        ExecutionId id = runner.findByLabel(McpTaskService.TASK_ID, taskId).get(0).executionId();
        ExecutionView view = runner.view(id);
        int round = InputRounds.lastRound(view);
        runner.deliver(id, McpTaskService.ANSWERS.visit(round), InputRounds.taskAnswers(view,
                round, jsonMapper.readTree("{\"" + KEY + "\":" + ACCEPT + "}")));

        assertEquals(1, podB.getTaskService().takeOver(podB.taskWork()));
        assertEquals("completed", awaitSettled(podB, taskId).path("status").asString());
        assertEquals(1, bookings.get());
    }
}
