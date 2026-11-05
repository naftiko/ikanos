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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The exit gate of the {@code local} durability mode (E2): an MCP task created before the Ikanos
 * process is killed completes after a restart, and its write is effective once.
 *
 * <p>The capability runs as a separate {@code ikanos serve} process on an H2 file. Its flow books a
 * departure slot, then looks up the customs declaration. The upstream, in this JVM, holds the
 * lookup open; the process is then killed without warning ({@link Process#destroyForcibly()}:
 * {@code SIGKILL} on Unix, {@code TerminateProcess} on Windows). A second process on the same
 * file resumes the task at startup: the booking, journaled before the kill, is not made again,
 * and only the lookup cut short runs a second time.</p>
 */
public class DurableTaskKillIntegrationTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final Duration STARTUP = Duration.ofSeconds(90);

    private final ObjectMapper json = IkanosMappers.json();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private final AtomicInteger bookings = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final CountDownLatch lookupInFlight = new CountDownLatch(1);
    private final CountDownLatch releaseLookup = new CountDownLatch(1);

    @TempDir
    Path directory;

    private HttpServer upstream;
    private int mcpPort;
    private Path capability;
    private Process first;
    private Process second;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @BeforeEach
    void startUpstreamAndWriteCapability() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/slots", exchange -> respond(exchange,
                "{\"slotId\":\"slot-" + bookings.incrementAndGet() + "\"}"));
        upstream.createContext("/declarations", exchange -> {
            if (lookups.incrementAndGet() == 1) {
                // Hold the first lookup open: the process is killed while it is in flight.
                lookupInFlight.countDown();
                try {
                    releaseLookup.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, "{\"declaration\":\"cleared-by-customs\"}");
        });
        upstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        upstream.start();

        mcpPort = freePort();
        String store = directory.resolve("durable").toString().replace('\\', '/');
        capability = directory.resolve("clearance.yml");
        Files.writeString(capability, """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "Kill -9 exit gate of the local durability mode"
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
                        description: Book a slot, then look up the customs declaration.
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
                    port: %d
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      clear-for-departure:
                        description: Clear a voyage for departure.
                        ref: departure.clear-for-departure
                  - type: control
                    address: localhost
                    port: %d
                    durability:
                      mode: local
                      store:
                        path: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), upstream.getAddress().getPort(),
                mcpPort, freePort(), store));
    }

    @AfterEach
    void stopEverything() {
        releaseLookup.countDown();
        for (Process process : new Process[] {first, second}) {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        upstream.stop(0);
    }

    private static String classpath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null ? surefire : System.getProperty("java.class.path");
    }

    private Process serve(String log) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath(), "io.ikanos.Cli", "serve", capability.toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve(log).toFile())
                .start();
        Instant deadline = Instant.now().plus(STARTUP);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new AssertionError("ikanos serve exited: "
                        + Files.readString(directory.resolve(log)));
            }
            try {
                if (rpc("server/discover", null, "{}").has("result")) {
                    return process;
                }
            } catch (IOException notYet) {
                // The port is not open yet.
            }
            Thread.sleep(250);
        }
        throw new AssertionError("ikanos serve did not start: "
                + Files.readString(directory.resolve(log)));
    }

    private JsonNode rpc(String method, String name, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":"
                + params.replaceFirst("^\\{", "{\"_meta\":{\"io.modelcontextprotocol/"
                        + "protocolVersion\":\"" + PROTOCOL_VERSION + "\",\"io.modelcontextprotocol/"
                        + "clientCapabilities\":{\"extensions\":{\"io.modelcontextprotocol/tasks\""
                        + ":{}}}}" + (params.equals("{}") ? "" : ","))
                + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + mcpPort + "/"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", method)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        return json.readTree(http.send(request.build(), HttpResponse.BodyHandlers.ofString())
                .body());
    }

    @Test
    void aTaskCreatedBeforeAKillShouldCompleteAfterARestartWithItsWriteEffectiveOnce()
            throws Exception {
        first = serve("first.log");
        JsonNode created = rpc("tools/call", "clear-for-departure",
                "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-042\"}}");
        String taskId = created.path("result").path("taskId").asString();
        assertEquals("task", created.path("result").path("resultType").asString(),
                created.toString());

        assertTrue(lookupInFlight.await(60, TimeUnit.SECONDS), "The lookup started");
        assertEquals(1, bookings.get());
        first.destroyForcibly();
        assertTrue(first.waitFor(30, TimeUnit.SECONDS), "The first process is gone");
        releaseLookup.countDown();

        second = serve("second.log");
        JsonNode response = null;
        JsonNode task = null;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            // Over HTTP, a tasks/* request names its task in Mcp-Name, for routing.
            response = rpc("tasks/get", taskId, "{\"taskId\":\"" + taskId + "\"}");
            task = response.path("result");
            if (!"working".equals(task.path("status").asString())) {
                break;
            }
            Thread.sleep(200);
        }

        assertEquals("completed", task.path("status").asString(), response + "\n"
                + Files.readString(directory.resolve("second.log")));
        assertTrue(task.path("result").toString().contains("cleared-by-customs"),
                task.toString());
        assertEquals(1, bookings.get(), "The booking journaled before the kill is not made again");
        assertEquals(2, lookups.get(), "Only the lookup cut short by the kill runs again");
    }

    @Test
    void aSigtermShouldLetTheCallInFlightFinishSoNothingRunsTwice() throws Exception {
        // Process.destroy() sends SIGTERM on Unix; on Windows it terminates without warning.
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"),
                "SIGTERM needs a Unix process");
        first = serve("first.log");
        String taskId = rpc("tools/call", "clear-for-departure",
                "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-042\"}}")
                .path("result").path("taskId").asString();
        assertTrue(lookupInFlight.await(60, TimeUnit.SECONDS), "The lookup started");

        first.destroy();
        Thread.sleep(500);
        releaseLookup.countDown();
        assertTrue(first.waitFor(60, TimeUnit.SECONDS), "The first process drained and exited: "
                + Files.readString(directory.resolve("first.log")));

        second = serve("second.log");
        JsonNode task = null;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            task = rpc("tasks/get", taskId, "{\"taskId\":\"" + taskId + "\"}").path("result");
            if (!"working".equals(task.path("status").asString())) {
                break;
            }
            Thread.sleep(200);
        }

        assertEquals("completed", task.path("status").asString(), String.valueOf(task));
        assertEquals(1, bookings.get());
        assertEquals(1, lookups.get(),
                "The drain let the lookup in flight finish and journal its outcome; first process:\n"
                        + Files.readString(directory.resolve("first.log")));
    }
}
