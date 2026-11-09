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
import java.util.List;
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
 * A task that drains a paginated list journals each page with its checkpoint: cut short by a
 * crash in the middle of the drain, it resumes at the page in flight after a restart, without
 * fetching the pages it already has (the Shipyard use case's S6 × F1, Execution &amp; Resolution
 * Model §9.4).
 */
public class PaginationCheckpointIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    /** The cursor of each page request the upstream received, "first" for the first page. */
    private final List<String> ledger = new CopyOnWriteArrayList<>();

    /** When set, the request for page 3 closes this capability's store while it is in flight. */
    private final AtomicReference<Capability> crashOnPage3 = new AtomicReference<>();
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
                router.attach("/voyages/{voyageId}/cargo", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        // Cargo 1 to 10, two units per page: five pages, cursors c3 to c9.
                        String cursor = request.getResourceRef().getQueryAsForm()
                                .getFirstValue("cursor");
                        ledger.add(cursor == null ? "first" : cursor);
                        int from = cursor == null ? 1 : Integer.parseInt(cursor.substring(1));
                        Capability victim = crashOnPage3.get();
                        if (from == 5 && victim != null && crashed.compareAndSet(false, true)) {
                            // The process dies while page 3 is in flight.
                            victim.getDurability().close();
                            crash.countDown();
                        }
                        String next = from + 2 <= 10 ? "\"c" + (from + 2) + "\"" : "null";
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"cargo\":[" + from + "," + (from + 1)
                                + "],\"next_cursor\":" + next + "}", MediaType.APPLICATION_JSON);
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
                  display: "Cargo manifest"
                  description: "Pagination checkpoint integration test"
                capability:
                  consumes:
                  - type: http
                    namespace: registry
                    baseUri: "http://localhost:%d"
                    resources:
                      cargo:
                        path: "/voyages/{{voyageId}}/cargo"
                        operations:
                          list-voyage-cargo:
                            method: GET
                            pagination:
                              style: cursor
                              items: $.cargo
                              cursor:
                                from: $.next_cursor
                                param: cursor
                  aggregates:
                  - namespace: departure
                    flows:
                      read-manifest:
                        description: Read a voyage's whole cargo manifest.
                        semantics:
                          interaction:
                            mode: task
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage whose manifest to read
                        steps:
                          read-cargo:
                            type: call
                            call: registry.list-voyage-cargo
                            with:
                              voyageId: "{{voyageId}}"
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      read-manifest:
                        description: Read a voyage's cargo manifest.
                        ref: departure.read-manifest
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

    private static final String READ =
            "{\"name\":\"read-manifest\",\"arguments\":{\"voyageId\":\"VOY-2026-047\"}}";

    @Test
    void aDrainCutShortShouldResumeAtThePageInFlight() throws Exception {
        Capability before = load();
        crashOnPage3.set(before);
        String taskId = dispatch(mcp(before), "tools/call", READ)
                .path("result").path("taskId").asString();
        assertTrue(crash.await(15, TimeUnit.SECONDS), "The crash happened");
        crashOnPage3.set(null);
        assertEquals(List.of("first", "c3", "c5"), ledger);

        Capability after = load();
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(1, adapter.resumeTasks());
            JsonNode task = awaitTerminal(adapter, taskId);
            assertEquals("completed", task.path("status").asString(), task.toString());
            assertTrue(task.path("result").toString().contains("[1,2,3,4,5,6,7,8,9,10]"),
                    task.toString());
        } finally {
            after.getDurability().close();
        }

        assertEquals(List.of("first", "c3", "c5", "c5", "c7", "c9"), ledger,
                "Pages 1 and 2 are restored from the journal; only page 3 is fetched again");
    }

    @Test
    void aDrainWithoutACrashShouldFetchEachPageOnce() throws Exception {
        Capability capability = load();
        try {
            McpServerAdapter adapter = mcp(capability);
            String taskId = dispatch(adapter, "tools/call", READ)
                    .path("result").path("taskId").asString();
            JsonNode task = awaitTerminal(adapter, taskId);
            assertEquals("completed", task.path("status").asString(), task.toString());
            assertTrue(task.path("result").toString().contains("[1,2,3,4,5,6,7,8,9,10]"),
                    task.toString());
        } finally {
            capability.getDurability().close();
        }
        assertEquals(List.of("first", "c3", "c5", "c7", "c9"), ledger);
    }
}
