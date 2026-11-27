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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
 * Task status notifications (SEP-2663) on a {@code subscriptions/listen} stream (MCP 2026-07-28),
 * over the Streamable HTTP transport, on the memory durability mode: the stream is a
 * {@code text/event-stream} answer to the POST, whose events are the acknowledgement, the
 * notifications and, at the end, the response to the request.
 */
class McpTaskNotificationsIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final ObjectMapper JSON = IkanosMappers.json();

    private final HttpClient http = HttpClient.newHttpClient();
    private McpServerAdapter adapter;
    private String url;
    private int nextId = 1;
    private int listenId;

    @BeforeEach
    void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "Task notifications integration test"
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
                    address: 127.0.0.1
                    port: %d
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
                """.formatted(VersionHelper.getSchemaVersion(), port);
        IkanosSpec spec = IkanosMappers.yaml().readValue(yaml, IkanosSpec.class);
        for (ServerAdapter server : new Capability(spec).getServerAdapters()) {
            if (server instanceof McpServerAdapter mcp) {
                adapter = mcp;
            }
        }
        adapter.start();
        url = "http://127.0.0.1:" + port + "/";
    }

    @AfterEach
    void stop() throws Exception {
        adapter.stop();
    }

    @Test
    void aSubscriberShouldHearTheQuestionThenTheResultWithoutPolling() throws Exception {
        String taskId = createTask();
        BlockingQueue<JsonNode> events = listen(List.of(taskId), true);

        JsonNode ack = next(events);
        assertEquals("notifications/subscriptions/acknowledged", ack.path("method").asString());
        assertEquals(taskId, ack.path("params").path("notifications").path("taskIds").path(0)
                .asString());
        JsonNode asking = until(events, "input_required");
        assertTrue(asking.path("params").path("inputRequests").has("steps/confirm"));

        JsonNode update = post("tasks/update", taskId, JSON.readTree("{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"steps/confirm\":{\"action\":\"accept\","
                + "\"content\":{\"confirmed\":true}}}}"), true);
        assertTrue(update.has("result"), "The update is acknowledged: " + update);

        JsonNode completed = until(events, "completed");
        assertTrue(completed.path("params").path("result").path("structuredContent")
                .path("confirmed").asBoolean(), "The notification carries the result");
        JsonNode end = next(events);
        assertEquals(listenId, end.path("id").asInt(),
                "The stream ends with the request's response");
        assertEquals(listenId, end.path("result").path("_meta")
                .path(McpSubscription.SUBSCRIPTION_ID).asInt());
    }

    @Test
    void askingForTaskNotificationsWithoutTheTasksExtensionShouldFail() throws Exception {
        String taskId = createTask();

        JsonNode error = post("subscriptions/listen", null,
                JSON.readTree("{\"notifications\":{\"taskIds\":[\"" + taskId + "\"]}}"), false);

        assertEquals(-32021, error.path("error").path("code").asInt());
    }

    @Test
    void stoppingTheServerShouldAnswerAnOpenSubscription() throws Exception {
        String taskId = createTask();
        BlockingQueue<JsonNode> events = listen(List.of(taskId), true);
        next(events); // the acknowledgement
        until(events, "input_required");

        adapter.stop();

        JsonNode end = next(events);
        assertTrue(end.has("result"), "A graceful end, told apart from a dropped stream: " + end);
        assertEquals(0, adapter.subscriptionCount());
        adapter.start(); // for @AfterEach
    }

    private String createTask() throws Exception {
        JsonNode created = post("tools/call", "clear-for-departure", JSON.readTree(
                "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-051\"}}"),
                true);
        String taskId = created.path("result").path("taskId").asString(null);
        assertNotNull(taskId, "A task handle: " + created);
        return taskId;
    }

    /** Opens a subscription and reads its events, as JSON messages, on a virtual thread. */
    private BlockingQueue<JsonNode> listen(List<String> taskIds, boolean declareTasks)
            throws Exception {
        ObjectNode params = JSON.createObjectNode();
        taskIds.forEach(params.putObject("notifications").putArray("taskIds")::add);
        listenId = nextId;
        HttpResponse<Stream<String>> response = http.send(request("subscriptions/listen", null,
                params, declareTasks), HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream"));
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> response.body()
                .filter(line -> line.startsWith("data:"))
                .forEach(line -> events.add(JSON.readTree(line.substring(5).trim()))));
        return events;
    }

    private JsonNode post(String method, String name, JsonNode params, boolean declareTasks)
            throws Exception {
        HttpResponse<String> response = http.send(
                request(method, name, (ObjectNode) params, declareTasks),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    private HttpRequest request(String method, String name, ObjectNode params,
            boolean declareTasks) {
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("elicitation");
        if (declareTasks) {
            capabilities.putObject("extensions").putObject(TASKS);
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", nextId++);
        body.put("method", method);
        body.set("params", params);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", ProtocolDispatcher.MCP_PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        return request.build();
    }

    private static JsonNode next(BlockingQueue<JsonNode> events) throws InterruptedException {
        JsonNode event = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(event, "An event within 10 s");
        return event;
    }

    /** The first notification of the given status, skipping the earlier ones. */
    private static JsonNode until(BlockingQueue<JsonNode> events, String status)
            throws InterruptedException {
        while (true) {
            JsonNode event = next(events);
            if (status.equals(event.path("params").path("status").asString(""))) {
                return event;
            }
        }
    }
}
