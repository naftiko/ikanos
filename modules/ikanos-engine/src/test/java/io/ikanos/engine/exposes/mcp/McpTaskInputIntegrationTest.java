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

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tasks that ask for input (SEP-2663): a task whose flow reaches an {@code elicit} step waits in
 * {@code input_required}, is answered with {@code tasks/update}, and completes, in the
 * {@code local} mode, across a restart, without booking the slot twice.
 */
public class McpTaskInputIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final String KEY = "steps/confirm";
    private static final String CLEAR =
            "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-044\"}}";
    private static final String ACCEPT =
            "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();
    private final AtomicInteger bookings = new AtomicInteger();

    @TempDir
    Path directory;

    private Component upstream;
    private int port;

    @BeforeEach
    void startUpstream() throws Exception {
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
    void stopUpstream() throws Exception {
        upstream.stop();
    }

    private Capability load() throws Exception {
        String path = directory.resolve("durable").toString().replace('\\', '/');
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "A task that asks before it finishes"
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
                      mode: local
                      store:
                        path: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), port, path);
        return new Capability(yamlMapper.readValue(yaml, IkanosSpec.class));
    }

    private static McpServerAdapter mcp(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson,
            boolean elicitation) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("extensions").putObject(TASKS);
        if (elicitation) {
            capabilities.putObject("elicitation");
        }
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private String create(McpServerAdapter adapter) throws Exception {
        JsonNode created = dispatch(adapter, "tools/call", CLEAR, true).path("result");
        assertEquals("task", created.path("resultType").asString(), String.valueOf(created));
        return created.path("taskId").asString();
    }

    private JsonNode get(McpServerAdapter adapter, String taskId) throws Exception {
        return dispatch(adapter, "tasks/get", "{\"taskId\":\"" + taskId + "\"}", true)
                .path("result");
    }

    /** Polls until the task leaves {@code working}. */
    private JsonNode awaitSettled(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = get(adapter, taskId);
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not settle");
    }

    private ObjectNode update(McpServerAdapter adapter, String taskId, String key, String answer)
            throws Exception {
        return dispatch(adapter, "tasks/update", "{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"" + key + "\":" + answer + "}}", true);
    }

    @Test
    void aTaskThatAsksShouldWaitThenCompleteWithTheAnswer() throws Exception {
        Capability capability = load();
        try {
            McpServerAdapter adapter = mcp(capability);
            String taskId = create(adapter);

            JsonNode waiting = awaitSettled(adapter, taskId);
            assertEquals("input_required", waiting.path("status").asString(), waiting.toString());
            JsonNode request = waiting.path("inputRequests").path(KEY);
            assertEquals("elicitation/create", request.path("method").asString());
            assertEquals("Clear the voyage for departure?",
                    request.path("params").path("message").asString());

            JsonNode ack = update(adapter, taskId, KEY, ACCEPT);
            assertEquals("complete", ack.path("result").path("resultType").asString(),
                    ack.toString());

            JsonNode done = awaitSettled(adapter, taskId);
            assertEquals("completed", done.path("status").asString(), done.toString());
            assertTrue(done.path("result").toString().contains("\"confirmed\":true"),
                    done.toString());
            assertEquals(1, bookings.get(), "The slot booked before the question is booked once");
        } finally {
            capability.getDurability().close();
        }
    }

    @Test
    void aWaitingTaskShouldBeAnsweredAfterARestart() throws Exception {
        Capability before = load();
        String taskId;
        JsonNode questions;
        try {
            McpServerAdapter adapter = mcp(before);
            taskId = create(adapter);
            questions = awaitSettled(adapter, taskId).path("inputRequests");
        } finally {
            before.getDurability().close();
        }

        Capability after = load();
        try {
            McpServerAdapter adapter = mcp(after);
            JsonNode waiting = get(adapter, taskId);
            assertEquals("input_required", waiting.path("status").asString());
            assertEquals(questions, waiting.path("inputRequests"),
                    "The questions come from the journal, the same after a restart");

            update(adapter, taskId, KEY, ACCEPT);
            JsonNode done = awaitSettled(adapter, taskId);

            assertEquals("completed", done.path("status").asString(), done.toString());
            assertEquals(1, bookings.get(), "The booking is restored, not made again");
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void answersDeliveredJustBeforeAStopShouldBeReadOnRestart() throws Exception {
        Capability before = load();
        String taskId;
        try {
            McpServerAdapter adapter = mcp(before);
            taskId = create(adapter);
            awaitSettled(adapter, taskId);
            // The process stops right after the answers reach the inbox, before any run reads it.
            ExecutionId execution = before.getDurability().runner()
                    .findByLabel(McpTaskService.TASK_ID, taskId).get(0).executionId();
            before.getDurability().runner().deliver(execution,
                    McpTaskService.ANSWERS.visit(1),
                    jsonMapper.readTree("{\"" + KEY + "\":" + ACCEPT + "}"));
        } finally {
            before.getDurability().close();
        }

        Capability after = load();
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(1, adapter.resumeTasks(), "The waiting task is picked up at start");
            JsonNode done = awaitSettled(adapter, taskId);

            assertEquals("completed", done.path("status").asString(), done.toString());
            assertEquals(1, bookings.get());
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void aDeclinedAnswerShouldCompleteTheTaskWithAToolError() throws Exception {
        Capability capability = load();
        try {
            McpServerAdapter adapter = mcp(capability);
            String taskId = create(adapter);
            awaitSettled(adapter, taskId);

            update(adapter, taskId, KEY, "{\"action\":\"decline\"}");
            JsonNode done = awaitSettled(adapter, taskId);

            assertEquals("completed", done.path("status").asString(), done.toString());
            assertTrue(done.path("result").path("isError").asBoolean(), done.toString());
        } finally {
            capability.getDurability().close();
        }
    }

    @Test
    void responsesToKeysTheTaskDoesNotWaitOnShouldBeIgnored() throws Exception {
        Capability capability = load();
        try {
            McpServerAdapter adapter = mcp(capability);
            String taskId = create(adapter);
            awaitSettled(adapter, taskId);

            JsonNode ack = update(adapter, taskId, "steps/other", ACCEPT);
            assertEquals("complete", ack.path("result").path("resultType").asString());

            assertEquals("input_required", get(adapter, taskId).path("status").asString());
        } finally {
            capability.getDurability().close();
        }
    }

    @Test
    void tasksUpdateShouldRejectAnUnknownTask() throws Exception {
        Capability capability = load();
        try {
            JsonNode response = update(mcp(capability), "no-such-task", KEY, ACCEPT);

            assertEquals(-32602, response.path("error").path("code").asInt(), response.toString());
        } finally {
            capability.getDurability().close();
        }
    }

    @Test
    void aTaskThatMayAskShouldNeedTheElicitationCapability() throws Exception {
        Capability capability = load();
        try {
            JsonNode response = dispatch(mcp(capability), "tools/call", CLEAR, false);

            assertEquals(-32021, response.path("error").path("code").asInt(), response.toString());
            assertEquals(0, bookings.get(), "Nothing runs for a task that could not be answered");
        } finally {
            capability.getDurability().close();
        }
    }
}
