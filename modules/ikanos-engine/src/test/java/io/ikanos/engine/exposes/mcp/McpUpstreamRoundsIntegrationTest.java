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
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private final ObjectMapper json = IkanosMappers.json();
    /** The {@code inputResponses} of every {@code tools/call} the upstream received. */
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();

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
        String state = params.path("requestState").asString("");
        if (state.equals("r2")) {
            return json.readTree("{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\","
                    + "\"text\":\"cleared\"}],\"structuredContent\":{\"cleared\":true}}");
        }
        return json.readTree("{\"resultType\":\"input_required\",\"requestState\":\""
                + (state.isEmpty() ? "r1" : "r2") + "\",\"inputRequests\":{\"confirm\":{"
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

    private McpServerAdapter start() throws Exception {
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
                  exposes:
                    - type: mcp
                      transport: http
                      address: localhost
                      port: 0
                      namespace: ops-mcp
                      tools:
                        confirm-up: { description: Confirm, ref: ops.confirm-up }
                    - type: control
                      address: localhost
                      port: 0
                      durability:
                        mode: memory
                """.formatted(VersionHelper.getSchemaVersion(), upstreamPort);
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
            params.putObject("inputResponses").set(KEY, json.readTree(answer));
        }
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("elicitation");
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody().path("result");
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
}
