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
import java.util.Base64;
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
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Journal-backed multi round-trip requests (E3b): in a durable mode, an inline call that asks
 * after a write resumes its journal on each retry instead of replaying its steps, so the write is
 * made once, also across a restart in the {@code local} mode.
 */
public class McpJournaledRoundTripIntegrationTest {

    private static final String KEY = "steps/confirm";
    /** A test key shared by both processes of the restart test, made here, not written out. */
    private static final String SEALING_KEY = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String BOOK =
            "{\"name\":\"book-then-confirm\",\"arguments\":{\"voyageId\":\"VOY-2026-045\"}}";
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

    private Capability load(String durability) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Departure booking"
                  description: "An inline call that asks after a write"
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
                      book-then-confirm:
                        description: Book a slot, then ask the duty officer to confirm.
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to book
                        steps:
                          book-slot:
                            type: call
                            call: port.book-slot
                          confirm:
                            type: elicit
                            message: Keep the booked slot?
                            fields:
                              confirmed:
                                type: boolean
                                description: Whether the duty officer keeps it
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
                      book-then-confirm:
                        description: Book a slot, then confirm it.
                        ref: departure.book-then-confirm
                      book-only:
                        description: Book a slot.
                        steps:
                          book-slot:
                            type: call
                            call: port.book-slot
                    resources:
                      slot-note:
                        uri: shipyard://slots/note
                        description: Book a slot and publish its note, once confirmed.
                        steps:
                          book-slot:
                            type: call
                            call: port.book-slot
                          confirm:
                            type: elicit
                            message: Publish the slot note?
                            fields:
                              confirmed:
                                type: boolean
                                description: Whether the note is published
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                %s
                """.formatted(VersionHelper.getSchemaVersion(), port, durability);
        return new Capability(yamlMapper.readValue(yaml, IkanosSpec.class));
    }

    private static final String MEMORY = """
                      mode: memory
                      sealingKey: "%s"
            """.formatted(SEALING_KEY);

    private String local() {
        String path = directory.resolve("durable").toString().replace('\\', '/');
        return """
                      mode: local
                      store:
                        path: "%s"
                      sealingKey: "%s"
            """.formatted(path, SEALING_KEY);
    }

    private static McpServerAdapter mcp(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode send(McpServerAdapter adapter, String method, ObjectNode params)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("elicitation");
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private ObjectNode params(String json) throws Exception {
        return (ObjectNode) jsonMapper.readTree(json);
    }

    private ObjectNode retry(String json, String state) throws Exception {
        ObjectNode params = params(json);
        params.put("requestState", state);
        params.putObject("inputResponses").set(KEY, jsonMapper.readTree(ACCEPT));
        return params;
    }

    @Test
    void aQuestionAfterAWriteShouldNotRepeatTheWrite() throws Exception {
        Capability capability = load(MEMORY);
        McpServerAdapter adapter = mcp(capability);

        JsonNode asked = send(adapter, "tools/call", params(BOOK)).path("result");
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        assertEquals("Keep the booked slot?",
                asked.path("inputRequests").path(KEY).path("params").path("message").asString());
        String state = asked.path("requestState").asString();

        JsonNode done = send(adapter, "tools/call", retry(BOOK, state)).path("result");
        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertTrue(done.toString().contains("\"confirmed\":true"), done.toString());
        assertEquals(1, bookings.get(), "The slot booked before the question is booked once");

        JsonNode again = send(adapter, "tools/call", retry(BOOK, state)).path("result");
        assertEquals(done, again, "A retry after the end gets the recorded result");
        assertEquals(1, bookings.get());
    }

    @Test
    void aRetryShouldResumeTheCallAfterARestart() throws Exception {
        Capability before = load(local());
        String state;
        try {
            state = send(mcp(before), "tools/call", params(BOOK)).path("result")
                    .path("requestState").asString();
        } finally {
            before.getDurability().close();
        }

        Capability after = load(local());
        try {
            JsonNode done = send(mcp(after), "tools/call", retry(BOOK, state)).path("result");

            assertEquals("complete", done.path("resultType").asString(), done.toString());
            assertEquals(1, bookings.get(), "The booking is restored from the journal");
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void aStateNamingACallTheStoreLostShouldBeRejected() throws Exception {
        String state = send(mcp(load(MEMORY)), "tools/call", params(BOOK)).path("result")
                .path("requestState").asString();

        // Another process on the memory mode: same key, but the execution went with the first.
        JsonNode response = send(mcp(load(MEMORY)), "tools/call", retry(BOOK, state));

        assertEquals(-32602, response.path("error").path("code").asInt(), response.toString());
    }

    @Test
    void aResourceShouldAskAfterAWriteWithoutRepeatingIt() throws Exception {
        McpServerAdapter adapter = mcp(load(MEMORY));
        String read = "{\"uri\":\"shipyard://slots/note\"}";

        JsonNode asked = send(adapter, "resources/read", params(read)).path("result");
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());

        JsonNode done = send(adapter, "resources/read",
                retry(read, asked.path("requestState").asString())).path("result");
        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertEquals(1, bookings.get());
    }

    @Test
    void aCallThatCannotAskShouldNotStartAnExecution() throws Exception {
        Capability capability = load(MEMORY);

        JsonNode done = send(mcp(capability), "tools/call",
                params("{\"name\":\"book-only\",\"arguments\":{}}")).path("result");

        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertTrue(capability.getDurability().runner()
                .findByLabel(McpTaskService.KIND, McpTaskService.MRTR).isEmpty());
        assertEquals(1, bookings.get());
    }
}
