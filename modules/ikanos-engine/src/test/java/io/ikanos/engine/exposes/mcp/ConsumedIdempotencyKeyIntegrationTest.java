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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A consumed operation that declares {@code idempotencyKey} sends the same key on every attempt of
 * a journaled step, also after a restart cut the first attempt short, and a fresh key per call
 * outside a journal (Execution &amp; Resolution Model §7.4.1).
 */
public class ConsumedIdempotencyKeyIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    /** The Idempotency-Key header of each booking the upstream received, in order. */
    private final List<String> keys = new CopyOnWriteArrayList<>();

    /** When set, the first booking closes this capability's store while it is in flight. */
    private final AtomicReference<Capability> crashOnBooking = new AtomicReference<>();
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
                        keys.add(String.valueOf(
                                request.getHeaders().getFirstValue("Idempotency-Key", true)));
                        Capability victim = crashOnBooking.get();
                        if (victim != null && crashed.compareAndSet(false, true)) {
                            // The process dies while the booking is in flight: the upstream books,
                            // the step's outcome is never journaled.
                            victim.getDurability().close();
                            crash.countDown();
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"slotId\":\"slot-1\"}", MediaType.APPLICATION_JSON);
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
                  display: "Departure slots"
                  description: "Idempotency key integration test"
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
                            idempotencyKey: true
                  aggregates:
                  - namespace: departure
                    flows:
                      book-as-task:
                        description: Book a departure slot, as a task.
                        semantics:
                          interaction:
                            mode: task
                        steps:
                          book-slot:
                            type: call
                            call: port.book-slot
                      book-inline:
                        description: Book a departure slot, inline.
                        steps:
                          book-slot:
                            type: call
                            call: port.book-slot
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      book-as-task:
                        description: Book a slot as a task.
                        ref: departure.book-as-task
                      book-inline:
                        description: Book a slot inline.
                        ref: departure.book-inline
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
    void aBookingRetriedAfterARestartShouldSendTheSameKey() throws Exception {
        Capability before = load();
        crashOnBooking.set(before);
        String taskId = dispatch(mcp(before), "tools/call",
                "{\"name\":\"book-as-task\",\"arguments\":{}}")
                .path("result").path("taskId").asString();
        assertTrue(crash.await(15, TimeUnit.SECONDS), "The crash happened");
        crashOnBooking.set(null);

        Capability after = load();
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(1, adapter.resumeTasks());
            JsonNode task = awaitTerminal(adapter, taskId);
            assertEquals("completed", task.path("status").asString(), task.toString());
        } finally {
            after.getDurability().close();
        }

        assertEquals(2, keys.size(), "The booking cut short runs again: " + keys);
        assertEquals(keys.get(0), keys.get(1),
                "Both attempts carry the same key, so the upstream books once");
        assertEquals(5, UUID.fromString(keys.get(0)).version(), "A name-based key");
    }

    @Test
    void inlineCallsOutsideAJournalShouldSendAFreshKeyEach() throws Exception {
        Capability capability = load();
        try {
            McpServerAdapter adapter = mcp(capability);
            for (int i = 0; i < 2; i++) {
                JsonNode result = dispatch(adapter, "tools/call",
                        "{\"name\":\"book-inline\",\"arguments\":{}}").path("result");
                assertTrue(result.toString().contains("slot-1"), result.toString());
            }
        } finally {
            capability.getDurability().close();
        }

        assertEquals(2, keys.size(), keys.toString());
        assertNotEquals(keys.get(0), keys.get(1));
        assertEquals(4, UUID.fromString(keys.get(0)).version(), "A random key");
    }
}
