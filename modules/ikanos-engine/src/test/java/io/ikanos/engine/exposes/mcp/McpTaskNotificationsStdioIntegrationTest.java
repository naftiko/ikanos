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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Task status notifications (SEP-2663) on a {@code subscriptions/listen} subscription (MCP
 * 2026-07-28) over stdio, where every subscription shares stdout with the responses: messages are
 * told apart by their subscription ID, and a client ends a subscription with
 * {@code notifications/cancelled}.
 */
class McpTaskNotificationsStdioIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final String KEY = McpSubscription.SUBSCRIPTION_ID;
    private static final ObjectMapper JSON = IkanosMappers.json();

    private final BlockingQueue<JsonNode> out = new LinkedBlockingQueue<>();
    private PipedOutputStream stdin;
    private StdioJsonRpcHandler handler;
    private Thread reader;
    private int nextId = 1;

    @BeforeEach
    void start() throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "Task notifications over stdio"
                capability:
                  aggregates:
                  - namespace: departure
                    flows:
                      clear-for-departure:
                        description: Ask the duty officer to clear a voyage for departure.
                        semantics:
                          interaction:
                            mode: task
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to clear
                        steps:
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
                    transport: stdio
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
                      mode: memory
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.yaml().readValue(yaml, IkanosSpec.class);
        McpServerAdapter adapter = null;
        for (ServerAdapter server : new Capability(spec).getServerAdapters()) {
            if (server instanceof McpServerAdapter mcp) {
                adapter = mcp;
            }
        }
        stdin = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(stdin, 1 << 16);
        handler = new StdioJsonRpcHandler(new ProtocolDispatcher(adapter), in, new LineSink(out));
        reader = Thread.ofVirtual().start(handler);
    }

    @AfterEach
    void stop() throws Exception {
        handler.shutdown();
        reader.join(5000);
    }

    @Test
    void aSubscriptionShouldCarryItsIdAndEndWithTheRequestsResponse() throws Exception {
        String taskId = createTask();
        int listenId = send("subscriptions/listen",
                "{\"notifications\":{\"taskIds\":[\"" + taskId + "\"]}}");

        JsonNode ack = take(message -> message.path("method").asString("")
                .equals("notifications/subscriptions/acknowledged"));
        assertEquals(listenId, ack.path("params").path("_meta").path(KEY).asInt());
        take(status("input_required"));

        int updateId = send("tasks/update", "{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"steps/confirm\":{\"action\":\"accept\","
                + "\"content\":{\"confirmed\":true}}}}");

        // The update's response and the subscription's messages share stdout, in any order.
        JsonNode completed = null;
        JsonNode updated = null;
        JsonNode end = null;
        while (completed == null || updated == null || end == null) {
            JsonNode message = take(any -> true);
            if (status("completed").test(message)) {
                completed = message;
            } else if (message.path("id").asInt(-1) == updateId) {
                updated = message;
            } else if (message.path("id").asInt(-1) == listenId) {
                assertNotNull(completed, "The request is answered after the last notification");
                end = message;
            }
        }
        assertEquals(listenId, completed.path("params").path("_meta").path(KEY).asInt());
        assertTrue(end.has("result"), "Every task ended: the request is answered");
    }

    @Test
    void aCancelledSubscriptionShouldSendNothingMore() throws Exception {
        String taskId = createTask();
        int listenId = send("subscriptions/listen",
                "{\"notifications\":{\"taskIds\":[\"" + taskId + "\"]}}");
        take(status("input_required"));

        notify("notifications/cancelled", "{\"requestId\":" + listenId + "}");
        awaitNoSubscription();
        int updateId = send("tasks/update", "{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"steps/confirm\":{\"action\":\"accept\","
                + "\"content\":{\"confirmed\":true}}}}");
        take(message -> message.path("id").asInt(-1) == updateId);

        JsonNode late = out.poll(500, TimeUnit.MILLISECONDS);
        assertTrue(late == null || !late.path("params").path("_meta").has(KEY),
                "No notification after the client cancelled: " + late);
    }

    @Test
    void closingStdinShouldEndEverySubscription() throws Exception {
        String taskId = createTask();
        send("subscriptions/listen", "{\"notifications\":{\"taskIds\":[\"" + taskId + "\"]}}");
        take(status("input_required"));

        stdin.close();
        reader.join(5000);

        assertFalse(reader.isAlive());
        awaitNoSubscription();
    }

    private String createTask() throws Exception {
        int id = send("tools/call",
                "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-052\"}}");
        String taskId = take(message -> message.path("id").asInt(-1) == id)
                .path("result").path("taskId").asString(null);
        assertNotNull(taskId);
        return taskId;
    }

    private int send(String method, String paramsJson) throws IOException {
        int id = nextId++;
        write(method, paramsJson, id);
        return id;
    }

    private void notify(String method, String paramsJson) throws IOException {
        write(method, paramsJson, null);
    }

    private void write(String method, String paramsJson, Integer id) throws IOException {
        ObjectNode params = (ObjectNode) JSON.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("elicitation");
        capabilities.putObject("extensions").putObject(TASKS);
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        if (id != null) {
            message.put("id", id);
        }
        message.put("method", method);
        message.set("params", params);
        stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** The next message that matches, skipping the others. */
    private JsonNode take(Predicate<JsonNode> matches) throws InterruptedException {
        while (true) {
            JsonNode message = out.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "A matching message within 10 s");
            if (matches.test(message)) {
                return message;
            }
        }
    }

    private static Predicate<JsonNode> status(String status) {
        return message -> "notifications/tasks".equals(message.path("method").asString(""))
                && status.equals(message.path("params").path("status").asString(""));
    }

    private void awaitNoSubscription() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handler.subscriptionCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, handler.subscriptionCount());
    }

    /** Stdout, split into JSON messages, one per line. */
    private static final class LineSink extends OutputStream {

        private final BlockingQueue<JsonNode> messages;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineSink(BlockingQueue<JsonNode> messages) {
            this.messages = messages;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.UTF_8).trim();
                line.reset();
                if (!text.isEmpty()) {
                    messages.add(JSON.readTree(text));
                }
            } else {
                line.write(b);
            }
        }
    }
}
