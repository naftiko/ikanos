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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An upstream MCP tool that asks the same question twice, as one that rejects an answer does:
 * the second time, the consumer asks its own caller again, and forwards only the new answer,
 * never the one the upstream already had (Execution &amp; Resolution Model §7.14.12).
 */
public class McpUpstreamRoundsIntegrationTest {

    private static final String KEY = "steps/ask/confirm";
    /** The key under which the consumer carries an upstream's requestState under none. */
    private static final String UPSTREAM_STATE = "upstream-state";

    private final ObjectMapper json = IkanosMappers.json();
    /** The {@code inputResponses} of every {@code tools/call} the upstream received. */
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    /** The {@code requestState} of every {@code tools/call} the upstream received. */
    private final List<JsonNode> states = new CopyOnWriteArrayList<>();
    /** The key the upstream asks under. */
    private volatile String upstreamKey = "confirm";
    /** When set, the upstream answers every call with it. */
    private volatile String fixedReply;

    private Component upstream;
    private int upstreamPort;
    private Capability consumer;

    @BeforeEach
    void startUpstream() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            upstreamPort = socket.getLocalPort();
        }
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, upstreamPort);
        upstream.getDefaultHost().attachDefault(new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                try {
                    JsonNode call = json.readTree(request.getEntity().getText());
                    ObjectNode reply = json.createObjectNode();
                    reply.put("jsonrpc", "2.0");
                    reply.set("id", call.get("id"));
                    reply.set("result", answer(call.path("params")));
                    response.setStatus(Status.SUCCESS_OK);
                    response.setEntity(reply.toString(), MediaType.APPLICATION_JSON);
                } catch (Exception e) {
                    response.setStatus(Status.SERVER_ERROR_INTERNAL);
                }
            }
        });
        upstream.start();
    }

    /** Asks with state r1, asks the same key again with state r2, then completes. */
    private JsonNode answer(JsonNode params) throws Exception {
        received.add(params.path("inputResponses"));
        states.add(params.path("requestState"));
        if (fixedReply != null) {
            return json.readTree(fixedReply);
        }
        String state = params.path("requestState").asString("");
        if (state.equals("r2")) {
            return json.readTree("{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\","
                    + "\"text\":\"cleared\"}],\"structuredContent\":{\"cleared\":true}}");
        }
        return json.readTree("{\"resultType\":\"input_required\",\"requestState\":\""
                + (state.isEmpty() ? "r1" : "r2") + "\",\"inputRequests\":{\"" + upstreamKey
                + "\":{"
                + "\"method\":\"elicitation/create\",\"params\":{\"message\":\"Confirm?\","
                + "\"requestedSchema\":{\"type\":\"object\",\"properties\":{}}}}}}");
    }

    @AfterEach
    void stopAll() throws Exception {
        if (consumer != null) {
            consumer.stop();
        }
        upstream.stop();
    }

    private static final String MEMORY = "      durability:\n        mode: memory\n";

    private McpServerAdapter start() throws Exception {
        return start(MEMORY);
    }

    private McpServerAdapter start(String durability) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: mcp
                      namespace: customs
                      endpoint: http://localhost:%d/
                      discovery: off
                      tools:
                        confirm: { description: Confirm twice, mayElicit: true }
                  aggregates:
                    - namespace: ops
                      flows:
                        confirm-up:
                          description: Ask through the upstream.
                          steps:
                            ask:
                              type: call
                              call: customs.confirm
                        confirm-task:
                          description: Ask through the upstream, as a task.
                          semantics:
                            interaction:
                              mode: task
                          steps:
                            ask:
                              type: call
                              call: customs.confirm
                  exposes:
                    - type: mcp
                      transport: http
                      address: localhost
                      port: 0
                      namespace: ops-mcp
                      tools:
                        confirm-up: { description: Confirm, ref: ops.confirm-up }
                        confirm-task: { description: Confirm, ref: ops.confirm-task }
                    - type: control
                      address: localhost
                      port: 0
                %s
                """.formatted(VersionHelper.getSchemaVersion(), upstreamPort, durability);
        consumer = new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        consumer.start();
        for (ServerAdapter adapter : consumer.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private JsonNode call(McpServerAdapter adapter, JsonNode asked, String answer)
            throws Exception {
        ObjectNode params = (ObjectNode) json.readTree("{\"name\":\"confirm-up\",\"arguments\":{}}");
        if (asked != null) {
            params.put("requestState", asked.path("requestState").asString());
            if (answer != null) {
                params.putObject("inputResponses").set(KEY, json.readTree(answer));
            }
        }
        return send(adapter, "tools/call", params);
    }

    private JsonNode send(McpServerAdapter adapter, String method, ObjectNode params) {
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("elicitation");
        capabilities.putObject("extensions").putObject("io.modelcontextprotocol/tasks");
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody().path("result");
    }

    private JsonNode awaitSettled(McpServerAdapter adapter, String taskId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            ObjectNode params = json.createObjectNode();
            params.put("taskId", taskId);
            JsonNode task = send(adapter, "tasks/get", params);
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Task " + taskId + " did not settle");
    }

    private void update(McpServerAdapter adapter, String taskId, String key, String answer)
            throws Exception {
        ObjectNode params = json.createObjectNode();
        params.put("taskId", taskId);
        params.putObject("inputResponses").set(key, json.readTree(answer));
        send(adapter, "tasks/update", params);
    }

    @Test
    void anUnsupportedResultTypeShouldNotBecomeStepOutputInADurableMode() throws Exception {
        fixedReply = "{\"resultType\":\"something-else\",\"structuredContent\":"
                + "{\"cleared\":true}}";
        McpServerAdapter adapter = start();

        JsonNode result = call(adapter, null, null);

        assertTrue(result.isMissingNode() || result.path("isError").asBoolean(false),
                "The call fails: " + result);
        assertFalse(result.toString().contains("\"cleared\":true"),
                "The upstream's content is no step output: " + result);
    }

    @Test
    void anUpstreamAskingAgainShouldGetANewAnswerNotTheOneItHad() throws Exception {
        McpServerAdapter adapter = start();
        String first = "{\"action\":\"accept\",\"content\":{\"note\":\"first\"}}";
        String second = "{\"action\":\"accept\",\"content\":{\"note\":\"second\"}}";

        JsonNode asked = call(adapter, null, null);
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());

        JsonNode askedAgain = call(adapter, asked, first);
        assertEquals("input_required", askedAgain.path("resultType").asString(),
                "The upstream asked again: its caller is asked again, " + askedAgain);
        assertEquals(2, received.size(), "The first answer was sent once: " + received);

        JsonNode done = call(adapter, askedAgain, second);
        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertEquals(3, received.size());
        assertEquals("first", received.get(1).path("confirm").path("content").path("note")
                .asString());
        assertEquals("second", received.get(2).path("confirm").path("content").path("note")
                .asString());
    }

    @Test
    void theFirstAnswerRetriedAfterTheUpstreamAskedAgainShouldNotAnswerTheNewQuestion()
            throws Exception {
        McpServerAdapter adapter = start();
        String first = "{\"action\":\"accept\",\"content\":{\"note\":\"first\"}}";

        JsonNode asked = call(adapter, null, null);
        JsonNode askedAgain = call(adapter, asked, first);
        assertEquals("input_required", askedAgain.path("resultType").asString());

        // A client retrying the first round, with its still valid state and the same answer.
        JsonNode retried = call(adapter, asked, first);

        assertEquals("input_required", retried.path("resultType").asString(),
                "The retry gets the question asked now, not a completion: " + retried);
        assertEquals(2, received.size(),
                "The upstream never got the first answer for its second question: " + received);
        JsonNode done = call(adapter, retried, "{\"action\":\"accept\",\"content\":"
                + "{\"note\":\"second\"}}");
        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertEquals("second", received.get(2).path("confirm").path("content").path("note")
                .asString());
    }

    @Test
    void underNoneTheRejectedAnswerShouldNotBeReplayedForTheNewQuestion() throws Exception {
        McpServerAdapter adapter = start("");
        String first = "{\"action\":\"accept\",\"content\":{\"note\":\"first\"}}";

        JsonNode asked = call(adapter, null, null);
        JsonNode askedAgain = call(adapter, asked, first);
        assertEquals("input_required", askedAgain.path("resultType").asString(),
                askedAgain.toString());

        // The new state retried without an answer: the upstream's question is still open.
        call(adapter, askedAgain, null);

        assertEquals(3, received.size(), received.toString());
        assertFalse(received.get(2).has("confirm"),
                "The answer the upstream rejected is not sent for its new question: " + received);
    }

    @Test
    void aTaskUpdateRetriedAfterTheUpstreamAskedAgainShouldNotAnswerTheNewQuestion()
            throws Exception {
        McpServerAdapter adapter = start();
        String first = "{\"action\":\"accept\",\"content\":{\"note\":\"first\"}}";
        ObjectNode create = (ObjectNode) json.readTree(
                "{\"name\":\"confirm-task\",\"arguments\":{}}");
        String taskId = send(adapter, "tools/call", create).path("taskId").asString();

        JsonNode waiting = awaitSettled(adapter, taskId);
        assertEquals("input_required", waiting.path("status").asString(), waiting.toString());
        String firstKey = waiting.path("inputRequests").propertyNames().iterator().next();
        update(adapter, taskId, firstKey, first);
        JsonNode waitingAgain = awaitSettled(adapter, taskId);
        assertEquals("input_required", waitingAgain.path("status").asString(),
                "The upstream asked again: " + waitingAgain);
        assertEquals(2, received.size(), received.toString());
        String secondKey = waitingAgain.path("inputRequests").propertyNames().iterator().next();
        assertNotEquals(firstKey, secondKey, "The question asked again has a key of its own");

        // The first update, retried after the task asked again.
        update(adapter, taskId, firstKey, first);

        JsonNode stillWaiting = awaitSettled(adapter, taskId);
        assertEquals("input_required", stillWaiting.path("status").asString(),
                "The retried update does not answer the new question: " + stillWaiting);
        assertEquals(2, received.size(), "The upstream never got the first answer again");

        update(adapter, taskId, secondKey,
                "{\"action\":\"accept\",\"content\":{\"note\":\"second\"}}");
        JsonNode done = awaitSettled(adapter, taskId);
        assertEquals("completed", done.path("status").asString(), done.toString());
        assertEquals("second", received.get(2).path("confirm").path("content").path("note")
                .asString());
    }

    @Test
    void underNoneAnUpstreamQuestionKeyedLikeTheCarriedStateShouldNotOverwriteIt()
            throws Exception {
        upstreamKey = UPSTREAM_STATE;
        McpServerAdapter adapter = start("");

        JsonNode asked = call(adapter, null, null);
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        ObjectNode retry = (ObjectNode) json.readTree(
                "{\"name\":\"confirm-up\",\"arguments\":{}}");
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").set("steps/ask/" + UPSTREAM_STATE,
                json.readTree("{\"action\":\"accept\",\"content\":{\"note\":\"first\"}}"));
        send(adapter, "tools/call", retry);

        assertEquals("r1", states.get(1).asString(),
                "The upstream gets its own requestState back, not the answer: " + states);
        assertEquals("first", received.get(1).path(UPSTREAM_STATE).path("content")
                .path("note").asString(), "and the answer to its question: " + received);
    }

    /** An upstream key that is no valid path, answered under the key the caller was shown. */
    private void answersAnUpstreamKeyThatIsNoPath(String durability) throws Exception {
        upstreamKey = "/confirm/";
        McpServerAdapter adapter = start(durability);

        JsonNode asked = call(adapter, null, null);
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        String shown = asked.path("inputRequests").propertyNames().iterator().next();
        ObjectNode retry = (ObjectNode) json.readTree(
                "{\"name\":\"confirm-up\",\"arguments\":{}}");
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").set(shown,
                json.readTree("{\"action\":\"accept\",\"content\":{\"note\":\"first\"}}"));
        JsonNode answered = send(adapter, "tools/call", retry);

        assertEquals("input_required", answered.path("resultType").asString(),
                "The call resumed and reached the upstream again: " + answered);
        assertEquals("first", received.get(1).path("/confirm/").path("content").path("note")
                .asString(), "The upstream gets the answer under its own key: " + received);
    }

    @Test
    void anUpstreamKeyThatIsNoPathShouldBeAnsweredUnderNone() throws Exception {
        answersAnUpstreamKeyThatIsNoPath("");
    }

    @Test
    void anUpstreamKeyThatIsNoPathShouldBeAnsweredInADurableMode() throws Exception {
        answersAnUpstreamKeyThatIsNoPath(MEMORY);
    }
}
