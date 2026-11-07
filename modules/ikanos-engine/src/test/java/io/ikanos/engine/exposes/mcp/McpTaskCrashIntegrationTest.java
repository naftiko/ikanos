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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.engine.execution.DurableScope;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.mcp.handler.ToolsCallHandler;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An MCP task in the {@code local} mode cut short by a crash, then resumed by the next process on
 * the same H2 file, against a real upstream that counts its calls: a booking (a write) and a
 * declaration lookup. The flow has no output mappings, so its result is the last call's response.
 */
public class McpTaskCrashIntegrationTest {

    private static final String TOOL = "clear-for-departure";
    private static final String ENTRY = "mcp:shipyard-ops/" + TOOL;
    private static final String TASKS = "io.modelcontextprotocol/tasks";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    private final AtomicInteger bookings = new AtomicInteger();
    private final AtomicInteger declarations = new AtomicInteger();
    /** When set, the first declaration lookup closes this capability's store: the crash. */
    private final AtomicReference<Capability> crashOnDeclaration = new AtomicReference<>();
    private final AtomicBoolean crashed = new AtomicBoolean();
    private final CountDownLatch crash = new CountDownLatch(1);

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
                router.attach("/declarations", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        declarations.incrementAndGet();
                        Capability victim = crashOnDeclaration.get();
                        if (victim != null && crashed.compareAndSet(false, true)) {
                            // The process dies while this call is in flight: its store goes away
                            // before the step's outcome can be journaled.
                            victim.getDurability().close();
                            crash.countDown();
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"declaration\":\"cleared-by-customs\"}",
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
                  description: "Crash integration test"
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
                      declarations:
                        path: /declarations
                        operations:
                          get-declaration:
                            method: GET
                  aggregates:
                  - namespace: departure
                    flows:
                      clear-for-departure:
                        description: Book a slot, then read the customs declaration.
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
                          get-declaration:
                            type: call
                            call: port.get-declaration
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

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
                .putObject(TASKS);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private JsonNode awaitTerminal(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = dispatch(adapter, "tasks/get", "{\"taskId\":\"" + taskId + "\"}")
                    .path("result");
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not end");
    }

    @Test
    void aTaskWhoseFlowFinishedBeforeTheCrashShouldReturnItsResultWithoutCallingAgain()
            throws Exception {
        Capability before = load();
        JsonNode original;
        try {
            // The task is recorded and its flow runs to the end, journaled step by step; the
            // process dies before the task's own result is recorded.
            ExecutionId execution = ExecutionId.random();
            JsonNode arguments = jsonMapper.readTree("{\"voyageId\":\"VOY-2026-042\"}");
            before.getDurability().runner().start(ExecutionRequest.of(execution, arguments),
                    ENTRY, Map.of(McpTaskService.TASK_ID, "task-flow-done",
                            McpTaskService.PRINCIPAL,
                            McpTaskService.digest(McpCallContext.principal())));
            McpServerAdapter adapter = mcp(before);
            original = DurableScope.with(before.getDurability().runner(), execution,
                    () -> ToolsCallHandler.callTool(adapter, TOOL,
                            Map.of("voyageId", "VOY-2026-042")));
        } finally {
            before.getDurability().close();
        }
        assertEquals(1, bookings.get());
        assertEquals(1, declarations.get());

        Capability after = load();
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(1, adapter.resumeTasks());
            JsonNode task = awaitTerminal(adapter, "task-flow-done");

            assertEquals("completed", task.path("status").asString());
            assertEquals(original, task.path("result"),
                    "The result is rebuilt from the journal, as the live run returned it");
            assertEquals(1, bookings.get(), "No booking is made again");
            assertEquals(1, declarations.get(), "No lookup is made again");
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void aTaskCutShortDuringItsSecondCallShouldResumeWithoutBookingAgain() throws Exception {
        Capability before = load();
        crashOnDeclaration.set(before);
        String taskId = dispatch(mcp(before), "tools/call",
                "{\"name\":\"" + TOOL + "\",\"arguments\":{\"voyageId\":\"VOY-2026-042\"}}")
                .path("result").path("taskId").asString();
        assertTrue(crash.await(15, TimeUnit.SECONDS), "The crash happened");
        crashOnDeclaration.set(null);
        assertEquals(1, bookings.get());

        Capability after = load();
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(1, adapter.resumeTasks());
            JsonNode task = awaitTerminal(adapter, taskId);

            assertEquals("completed", task.path("status").asString(), task.toString());
            assertTrue(task.path("result").toString().contains("cleared-by-customs"),
                    task.toString());
            assertEquals(1, bookings.get(), "The booking journaled before the crash is restored");
            assertEquals(2, declarations.get(), "Only the call cut short runs again");
        } finally {
            after.getDurability().close();
        }
    }
}
