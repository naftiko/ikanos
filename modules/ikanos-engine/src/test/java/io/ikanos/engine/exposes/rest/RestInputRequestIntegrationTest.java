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
package io.ikanos.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * REST input requests (E3c): an operation that reaches an {@code elicit} step answers
 * {@code 202} with MCP's {@code inputRequests} and a {@code Location}, and a {@code POST} of the
 * answers there completes it, replayed under {@code none}, journal-backed in {@code memory} and
 * {@code local} so a write before the question is made once.
 */
public class RestInputRequestIntegrationTest {

    private static final String ANSWER =
            "{\"inputResponses\":{\"steps/confirm\":{\"action\":\"accept\","
                    + "\"content\":{\"confirmed\":true}}}}";
    private static final String DECLINE =
            "{\"inputResponses\":{\"steps/confirm\":{\"action\":\"decline\"}}}";
    /** A test key shared by both processes of the restart test, made here, not written out. */
    private static final String SEALING_KEY = Base64.getEncoder().encodeToString(new byte[16]);

    private final ObjectMapper json = IkanosMappers.json();
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

    private String capability(String control, String extraResource) {
        return """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "REST input requests"
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
                      confirm-departure:
                        description: Ask the duty officer to confirm a departure.
                        steps:
                          confirm:
                            type: elicit
                            message: Confirm the departure?
                            fields:
                              confirmed:
                                type: boolean
                                description: Whether the departure is confirmed
                        mappings:
                        - target: confirmed
                          value: $.confirm.confirmed
                        outputParameters:
                          confirmed:
                            type: boolean
                      book-then-confirm:
                        description: Book a slot, then ask to keep it.
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
                                description: Whether the slot is kept
                        mappings:
                        - target: confirmed
                          value: $.confirm.confirmed
                        outputParameters:
                          confirmed:
                            type: boolean
                  exposes:
                  - type: rest
                    address: localhost
                    port: 0
                    namespace: departures
                    resources:
                      confirm:
                        path: /confirm
                        operations:
                          confirm:
                            method: POST
                            ref: departure.confirm-departure
                      book:
                        path: /book
                        operations:
                          book:
                            method: POST
                            ref: departure.book-then-confirm
                %s
                  - type: control
                    address: localhost
                    port: 0
                %s
                """.formatted(VersionHelper.getSchemaVersion(), port, extraResource, control);
    }

    private static final String NONE = "";

    private static final String MEMORY = "    durability:\n      mode: memory\n";

    private String local() {
        String path = directory.resolve("durable").toString().replace('\\', '/');
        return "    durability:\n      mode: local\n      store:\n        path: \"" + path
                + "\"\n      sealingKey: \"" + SEALING_KEY + "\"\n";
    }

    private Capability load(String control) throws Exception {
        return new Capability(IkanosMappers.yaml().readValue(capability(control, ""),
                IkanosSpec.class));
    }

    private static RestServerAdapter rest(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof RestServerAdapter rest) {
                return rest;
            }
        }
        throw new IllegalStateException("No REST adapter");
    }

    private static Response send(RestServerAdapter adapter, Method method, String path,
            String body) {
        Request request = new Request(method, new Reference("http://localhost" + path));
        request.getResourceRef().setBaseRef("http://localhost");
        if (body != null) {
            request.setEntity(body, MediaType.APPLICATION_JSON);
        }
        Response response = new Response(request);
        adapter.getRouter().handle(request, response);
        return response;
    }

    private JsonNode body(Response response) throws Exception {
        return json.readTree(response.getEntity().getText());
    }

    @Test
    void anOperationThatAsksShouldAnswer202WithItsQuestionsAndALocation() throws Exception {
        Response asked = send(rest(load(NONE)), Method.POST, "/confirm", null);

        assertEquals(Status.SUCCESS_ACCEPTED, asked.getStatus());
        assertTrue(asked.getLocationRef().getPath().startsWith(RestInputRequests.PREFIX),
                String.valueOf(asked.getLocationRef()));
        JsonNode body = body(asked);
        assertEquals("input_required", body.path("status").asString());
        assertEquals("Confirm the departure?", body.path("inputRequests").path("steps/confirm")
                .path("params").path("message").asString());
    }

    @Test
    void postingTheAnswerShouldCompleteTheOperationUnderNone() throws Exception {
        RestServerAdapter adapter = rest(load(NONE));
        Response asked = send(adapter, Method.POST, "/confirm", null);

        Response done = send(adapter, Method.POST, asked.getLocationRef().getPath(), ANSWER);

        assertEquals(Status.SUCCESS_OK, done.getStatus());
        assertTrue(body(done).path("confirmed").asBoolean(), String.valueOf(body(done)));
    }

    @Test
    void aDeclinedAnswerShouldAnswer409() throws Exception {
        RestServerAdapter adapter = rest(load(NONE));
        Response asked = send(adapter, Method.POST, "/confirm", null);

        Response declined = send(adapter, Method.POST, asked.getLocationRef().getPath(),
                DECLINE);

        assertEquals(Status.CLIENT_ERROR_CONFLICT, declined.getStatus());
    }

    @Test
    void underNoneTheHandleShouldOnlyTakeAnswers() throws Exception {
        RestServerAdapter adapter = rest(load(NONE));
        String location = send(adapter, Method.POST, "/confirm", null).getLocationRef().getPath();

        assertEquals(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED,
                send(adapter, Method.GET, location, null).getStatus());
        assertEquals(Status.CLIENT_ERROR_GONE,
                send(adapter, Method.POST, RestInputRequests.PREFIX + "tampered", ANSWER)
                        .getStatus());
    }

    @Test
    void aWriteBeforeTheQuestionShouldBeMadeOnceInADurableMode() throws Exception {
        RestServerAdapter adapter = rest(load(MEMORY));
        Response asked = send(adapter, Method.POST, "/book", null);
        assertEquals(Status.SUCCESS_ACCEPTED, asked.getStatus());
        String location = asked.getLocationRef().getPath();

        assertEquals(Status.SUCCESS_ACCEPTED, send(adapter, Method.GET, location, null)
                .getStatus(), "GET replays the open questions");

        Response done = send(adapter, Method.POST, location, ANSWER);
        assertEquals(Status.SUCCESS_OK, done.getStatus());
        assertTrue(body(done).path("confirmed").asBoolean());
        assertEquals(1, bookings.get(), "The slot booked before the question is booked once");

        Response again = send(adapter, Method.GET, location, null);
        assertEquals(Status.SUCCESS_OK, again.getStatus(), "GET returns the recorded response");
        assertEquals(1, bookings.get());
    }

    @Test
    void anAnswerShouldCompleteTheOperationAfterARestart() throws Exception {
        Capability before = load(local());
        String location;
        try {
            location = send(rest(before), Method.POST, "/book", null).getLocationRef().getPath();
        } finally {
            before.getDurability().close();
        }

        Capability after = load(local());
        try {
            Response done = send(rest(after), Method.POST, location, ANSWER);

            assertEquals(Status.SUCCESS_OK, done.getStatus());
            assertEquals(1, bookings.get(), "The booking is restored from the journal");
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void aCancelledOperationShouldNoLongerTakeAnswers() throws Exception {
        RestServerAdapter adapter = rest(load(MEMORY));
        String location = send(adapter, Method.POST, "/book", null).getLocationRef().getPath();

        assertEquals(Status.SUCCESS_NO_CONTENT,
                send(adapter, Method.DELETE, location, null).getStatus());
        assertEquals(Status.CLIENT_ERROR_GONE,
                send(adapter, Method.POST, location, ANSWER).getStatus());
    }

    @Test
    void aResourceUnderTheReservedPrefixShouldFailToLoad() {
        String yaml = capability(NONE, """
                      reserved:
                        path: /_ikanos/mine
                        operations:
                          mine:
                            method: GET
                            ref: departure.confirm-departure
                """);

        assertThrows(Exception.class, () -> new Capability(
                IkanosMappers.yaml().readValue(yaml, IkanosSpec.class)));
    }
}
