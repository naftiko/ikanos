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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.rest.RestServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Shipyard departure clearance (internal-docs, Shipyard Departure Clearance §2, §5, §6) as
 * scenario tests: the rows of its test matrix that the durable engine serves today, against a
 * stateful mock that selects its behaviour by voyage ID and keeps a ledger of every upstream
 * call, so each test asserts how many times the slot was really booked.
 *
 * <p>Scenarios: S1 happy path, S2 hazardous cargo confirmed before any write, S5 inspection fee
 * asked after the declaration, S7 ship refused before any write; fault F4 (restart while waiting
 * for an answer) and F6 (cancel before the booking). Clients: C1 MCP with tasks, C2 MCP without
 * tasks, C3 REST. S3, S4 and S6 wait for durable timers, compensation and pagination
 * checkpoints.</p>
 */
public class ShipyardDepartureScenarioIntegrationTest {

    private static final String S1 = "VOY-2026-042";
    private static final String S2 = "VOY-2026-043";
    private static final String S5 = "VOY-2026-046";
    private static final String S7 = "VOY-2026-048";
    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private final ObjectMapper json = IkanosMappers.json();
    /** Upstream calls, by "operation voyage". */
    private final Map<String, AtomicInteger> ledger = new ConcurrentHashMap<>();
    /** When set, the eligibility check waits on it: a call in flight, for F6. */
    private final AtomicReference<CountDownLatch> holdEligibility = new AtomicReference<>();

    @TempDir
    Path directory;

    private Component upstream;
    private int port;
    private Capability capability;

    @BeforeEach
    void startMock() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, port);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/voyages/{voyageId}/eligibility", answer("check-eligibility",
                        voyage -> S7.equals(voyage)
                                ? new Reply(422, "{\"error\":\"Ship in maintenance\"}")
                                : new Reply(200, "{\"eligible\":true}")));
                router.attach("/voyages/{voyageId}/dangerous-goods", answer("check-dangerous-goods",
                        voyage -> new Reply(200, S2.equals(voyage)
                                ? "{\"hazardous\":3,\"un\":\"UN1203\"}" : "{\"hazardous\":0}")));
                router.attach("/voyages/{voyageId}/slots", answer("book-departure-slot",
                        voyage -> new Reply(200, "{\"slotId\":\"SLOT-" + voyage + "\"}")));
                router.attach("/voyages/{voyageId}/declarations", answer("file-declaration",
                        voyage -> new Reply(200, S5.equals(voyage)
                                ? "{\"status\":\"inspection-required\",\"fee\":450}"
                                : "{\"status\":\"accepted\"}")));
                router.attach("/voyages/{voyageId}/status", answer("update-voyage-status",
                        voyage -> new Reply(200, "{\"status\":\"cleared\"}")));
                return router;
            }
        });
        upstream.start();
    }

    @AfterEach
    void stop() throws Exception {
        CountDownLatch hold = holdEligibility.getAndSet(null);
        if (hold != null) {
            hold.countDown();
        }
        if (capability != null && capability.getDurability() != null) {
            capability.getDurability().close();
        }
        upstream.stop();
    }

    private record Reply(int status, String body) {
    }

    private Restlet answer(String operation, java.util.function.Function<String, Reply> reply) {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                String voyage = String.valueOf(request.getAttributes().get("voyageId"));
                ledger.computeIfAbsent(operation + " " + voyage, k -> new AtomicInteger())
                        .incrementAndGet();
                CountDownLatch hold = holdEligibility.get();
                if (hold != null && operation.equals("check-eligibility")) {
                    try {
                        hold.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Reply r = reply.apply(voyage);
                response.setStatus(Status.valueOf(r.status()));
                response.setEntity(r.body(), MediaType.APPLICATION_JSON);
            }
        };
    }

    private int calls(String operation, String voyage) {
        AtomicInteger n = ledger.get(operation + " " + voyage);
        return n == null ? 0 : n.get();
    }

    private String yaml(String durability) {
        return """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "Shipyard departure clearance scenarios"
                capability:
                  consumes:
                    - type: http
                      namespace: port
                      baseUri: "http://localhost:%d"
                      resources:
                        eligibility:
                          path: /voyages/{{voyageId}}/eligibility
                          operations:
                            check-eligibility:
                              method: GET
                              inputParameters:
                                voyageId: { in: path }
                        dangerous-goods:
                          path: /voyages/{{voyageId}}/dangerous-goods
                          operations:
                            check-dangerous-goods:
                              method: GET
                              inputParameters:
                                voyageId: { in: path }
                        slots:
                          path: /voyages/{{voyageId}}/slots
                          operations:
                            book-departure-slot:
                              method: POST
                              inputParameters:
                                voyageId: { in: path }
                        declarations:
                          path: /voyages/{{voyageId}}/declarations
                          operations:
                            file-declaration:
                              method: POST
                              inputParameters:
                                voyageId: { in: path }
                        status:
                          path: /voyages/{{voyageId}}/status
                          operations:
                            update-voyage-status:
                              method: PUT
                              inputParameters:
                                voyageId: { in: path }
                  aggregates:
                    - namespace: departure
                      flows:
                        clear:
                          description: Clear a voyage for departure.
                          semantics: { interaction: { mode: task } }
                          inputParameters:
                            voyageId: { type: string, description: Voyage to clear }
                          steps:
                            eligibility: { type: call, call: port.check-eligibility, with: { voyageId: departure.voyageId } }
                            book-slot: { type: call, call: port.book-departure-slot, with: { voyageId: departure.voyageId } }
                            declare: { type: call, call: port.file-declaration, with: { voyageId: departure.voyageId } }
                            mark-cleared: { type: call, call: port.update-voyage-status, with: { voyageId: departure.voyageId } }
                          mappings:
                            - { target: slotId, value: $.book-slot.slotId }
                            - { target: customs, value: $.declare.status }
                          outputParameters:
                            slotId: { type: string }
                            customs: { type: string }
                        clear-hazardous:
                          description: Clear a voyage carrying hazardous cargo, once confirmed.
                          semantics: { interaction: { mode: task } }
                          inputParameters:
                            voyageId: { type: string, description: Voyage to clear }
                          steps:
                            eligibility: { type: call, call: port.check-eligibility, with: { voyageId: departure.voyageId } }
                            screen: { type: call, call: port.check-dangerous-goods, with: { voyageId: departure.voyageId } }
                            confirm-hazardous:
                              type: elicit
                              message: Confirm the hazardous cargo declaration?
                              fields:
                                confirmed: { type: boolean, description: Whether the officer confirms it }
                            book-slot: { type: call, call: port.book-departure-slot, with: { voyageId: departure.voyageId } }
                            declare: { type: call, call: port.file-declaration, with: { voyageId: departure.voyageId } }
                          mappings:
                            - { target: slotId, value: $.book-slot.slotId }
                          outputParameters:
                            slotId: { type: string }
                        clear-with-inspection:
                          description: Clear a voyage, paying the inspection fee customs asks for.
                          semantics: { interaction: { mode: task } }
                          inputParameters:
                            voyageId: { type: string, description: Voyage to clear }
                          steps:
                            book-slot: { type: call, call: port.book-departure-slot, with: { voyageId: departure.voyageId } }
                            declare: { type: call, call: port.file-declaration, with: { voyageId: departure.voyageId } }
                            accept-fee:
                              type: elicit
                              message: Customs requires an inspection; pay the fee?
                              fields:
                                accepted: { type: boolean, description: Whether the fee is paid }
                            mark-cleared: { type: call, call: port.update-voyage-status, with: { voyageId: departure.voyageId } }
                          mappings:
                            - { target: accepted, value: $.accept-fee.accepted }
                          outputParameters:
                            accepted: { type: boolean }
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: shipyard-ops
                      description: Shipyard operations.
                      tools:
                        clear-for-departure: { description: Clear a voyage, ref: departure.clear }
                        clear-hazardous: { description: Clear hazardous cargo, ref: departure.clear-hazardous }
                        clear-with-inspection: { description: Clear with inspection, ref: departure.clear-with-inspection }
                    - type: rest
                      address: localhost
                      port: 0
                      namespace: departures
                      resources:
                        clearances:
                          path: /clearances/{{voyageId}}
                          operations:
                            clear:
                              method: POST
                              inputParameters:
                                voyageId: { in: path, type: string, description: Voyage to clear }
                              ref: departure.clear
                        hazardous:
                          path: /hazardous-clearances/{{voyageId}}
                          operations:
                            clear-hazardous:
                              method: POST
                              inputParameters:
                                voyageId: { in: path, type: string, description: Voyage to clear }
                              ref: departure.clear-hazardous
                    - type: control
                      address: localhost
                      port: 0
                %s
                """.formatted(VersionHelper.getSchemaVersion(), port, durability);
    }

    private static final String MEMORY = "      durability:\n        mode: memory\n";

    private String local() {
        String path = directory.resolve("clearance").toString().replace('\\', '/');
        return "      durability:\n        mode: local\n        store:\n          path: \"" + path
                + "\"\n";
    }

    private Capability load(String durability) throws Exception {
        capability = new Capability(YAML.readValue(yaml(durability), IkanosSpec.class));
        return capability;
    }

    private static <T extends ServerAdapter> T adapter(Capability capability, Class<T> type) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (type.isInstance(adapter)) {
                return type.cast(adapter);
            }
        }
        throw new IllegalStateException("No " + type.getSimpleName());
    }

    /** An MCP request; {@code tasks} makes the client C1, without it C2. */
    private ObjectNode mcp(McpServerAdapter adapter, String method, ObjectNode params,
            boolean tasks) throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("elicitation");
        if (tasks) {
            capabilities.putObject("extensions").putObject(TASKS);
        }
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private ObjectNode call(String tool, String voyage) throws Exception {
        return (ObjectNode) json.readTree("{\"name\":\"" + tool + "\",\"arguments\":{\"voyageId\":\""
                + voyage + "\"}}");
    }

    private ObjectNode answer(String key, String field, boolean value) {
        ObjectNode responses = json.createObjectNode();
        responses.putObject(key).put("action", "accept").putObject("content").put(field, value);
        return responses;
    }

    private String startTask(McpServerAdapter adapter, String tool, String voyage)
            throws Exception {
        JsonNode created = mcp(adapter, "tools/call", call(tool, voyage), true).path("result");
        assertEquals("task", created.path("resultType").asString(), created.toString());
        return created.path("taskId").asString();
    }

    private JsonNode settle(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = mcp(adapter, "tasks/get",
                    (ObjectNode) json.readTree("{\"taskId\":\"" + taskId + "\"}"), true)
                    .path("result");
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not settle");
    }

    private static Response rest(RestServerAdapter adapter, Method method, String path,
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

    // S1 × none × C1, C2 (E1)

    @Test
    void s1ShouldClearTheVoyageAsATaskBookingOnce() throws Exception {
        McpServerAdapter mcp = adapter(load(MEMORY), McpServerAdapter.class);

        JsonNode done = settle(mcp, startTask(mcp, "clear-for-departure", S1));

        assertEquals("completed", done.path("status").asString(), done.toString());
        assertTrue(done.toString().contains("SLOT-" + S1), done.toString());
        assertEquals(1, calls("check-eligibility", S1));
        assertEquals(1, calls("book-departure-slot", S1));
        assertEquals(1, calls("file-declaration", S1));
        assertEquals(1, calls("update-voyage-status", S1));
    }

    @Test
    void s1ShouldClearTheVoyageForAClientWithoutTasks() throws Exception {
        McpServerAdapter mcp = adapter(load(MEMORY), McpServerAdapter.class);

        JsonNode done = mcp(mcp, "tools/call", call("clear-for-departure", S1), false)
                .path("result");

        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertTrue(done.toString().contains("SLOT-" + S1), done.toString());
        assertEquals(1, calls("book-departure-slot", S1));
    }

    // S2 × none × C2 (E1), and × C3 in local (E2)

    @Test
    void s2ShouldAskBeforeAnyWriteAndBookOnceAnswered() throws Exception {
        McpServerAdapter mcp = adapter(load(MEMORY), McpServerAdapter.class);

        JsonNode asked = mcp(mcp, "tools/call", call("clear-hazardous", S2), false).path("result");
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        assertEquals(0, calls("book-departure-slot", S2), "Nothing is booked before the answer");

        ObjectNode retry = call("clear-hazardous", S2);
        retry.put("requestState", asked.path("requestState").asString());
        retry.set("inputResponses", answer("steps/confirm-hazardous", "confirmed", true));
        JsonNode done = mcp(mcp, "tools/call", retry, false).path("result");

        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertEquals(1, calls("book-departure-slot", S2));
    }

    @Test
    void s2ShouldAskARestClientWith202AndBookOnceAnswered() throws Exception {
        RestServerAdapter api = adapter(load(local()), RestServerAdapter.class);

        Response asked = rest(api, Method.POST, "/hazardous-clearances/" + S2, null);
        assertEquals(Status.SUCCESS_ACCEPTED, asked.getStatus());
        assertEquals(0, calls("book-departure-slot", S2));

        ObjectNode body = json.createObjectNode();
        body.set("inputResponses", answer("steps/confirm-hazardous", "confirmed", true));
        Response done = rest(api, Method.POST, asked.getLocationRef().getPath(), body.toString());

        assertEquals(Status.SUCCESS_OK, done.getStatus());
        assertEquals(1, calls("book-departure-slot", S2));
    }

    // S7 × none × C1, C3 (E1)

    @Test
    void s7ShouldBeRefusedBeforeAnyWrite() throws Exception {
        Capability loaded = load(MEMORY);
        McpServerAdapter mcp = adapter(loaded, McpServerAdapter.class);

        JsonNode done = settle(mcp, startTask(mcp, "clear-for-departure", S7));
        Response overRest = rest(adapter(loaded, RestServerAdapter.class), Method.POST,
                "/clearances/" + S7, null);

        assertEquals("completed", done.path("status").asString(), done.toString());
        assertTrue(done.path("result").path("isError").asBoolean(), done.toString());
        assertTrue(overRest.getStatus().isError(), String.valueOf(overRest.getStatus()));
        assertEquals(0, calls("book-departure-slot", S7), "Nothing is booked for a refused ship");
    }

    // F6 before book-slot × C1 (E1)

    @Test
    void f6ACancelBeforeTheBookingShouldBookNothing() throws Exception {
        McpServerAdapter mcp = adapter(load(MEMORY), McpServerAdapter.class);
        CountDownLatch hold = new CountDownLatch(1);
        holdEligibility.set(hold);

        String taskId = startTask(mcp, "clear-for-departure", S1);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (calls("check-eligibility", S1) == 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
        mcp(mcp, "tasks/cancel", (ObjectNode) json.readTree("{\"taskId\":\"" + taskId + "\"}"),
                true);
        holdEligibility.set(null);
        hold.countDown();

        assertEquals("cancelled", settle(mcp, taskId).path("status").asString());
        Thread.sleep(200);
        assertEquals(0, calls("book-departure-slot", S1), "A cancelled clearance books nothing");
    }

    // S5 × none × C1 (tasks/update), C2 (journal-backed MRTR); S5 × F4 × C1 (E3)

    private String askForTheFee(McpServerAdapter mcp) throws Exception {
        String taskId = startTask(mcp, "clear-with-inspection", S5);
        JsonNode waiting = settle(mcp, taskId);
        assertEquals("input_required", waiting.path("status").asString(), waiting.toString());
        assertTrue(waiting.path("inputRequests").has("steps/accept-fee"), waiting.toString());
        return taskId;
    }

    private void payTheFee(McpServerAdapter mcp, String taskId) throws Exception {
        ObjectNode update = (ObjectNode) json.readTree("{\"taskId\":\"" + taskId + "\"}");
        update.set("inputResponses", answer("steps/accept-fee", "accepted", true));
        mcp(mcp, "tasks/update", update, true);
        JsonNode done = settle(mcp, taskId);

        assertEquals("completed", done.path("status").asString(), done.toString());
        assertEquals(1, calls("book-departure-slot", S5), "The slot is booked once");
        assertEquals(1, calls("file-declaration", S5), "The declaration is filed once");
        assertEquals(1, calls("update-voyage-status", S5));
    }

    @Test
    void s5ShouldAskForTheFeeAfterTheWritesAndFinishOnceAnswered() throws Exception {
        McpServerAdapter mcp = adapter(load(local()), McpServerAdapter.class);

        payTheFee(mcp, askForTheFee(mcp));
    }

    @Test
    void s5ShouldFinishAfterARestartWhileWaitingForTheFee() throws Exception {
        String taskId = askForTheFee(adapter(load(local()), McpServerAdapter.class));
        capability.getDurability().close();

        payTheFee(adapter(load(local()), McpServerAdapter.class), taskId);
    }

    @Test
    void s5ShouldResumeAClientWithoutTasksFromTheJournal() throws Exception {
        McpServerAdapter mcp = adapter(load(MEMORY), McpServerAdapter.class);

        JsonNode asked = mcp(mcp, "tools/call", call("clear-with-inspection", S5), false)
                .path("result");
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());

        ObjectNode retry = call("clear-with-inspection", S5);
        retry.put("requestState", asked.path("requestState").asString());
        retry.set("inputResponses", answer("steps/accept-fee", "accepted", true));
        JsonNode done = mcp(mcp, "tools/call", retry, false).path("result");

        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertEquals(1, calls("book-departure-slot", S5), "The booking is not repeated");
        assertEquals(1, calls("file-declaration", S5), "The declaration is not repeated");
    }
}
