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
package io.ikanos.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * End-to-end tests of the control port over real HTTP (issue #608): a capability is started
 * in-process with a {@code type: control} port and a REST adapter backed by a local stub API,
 * and every control route is called with a real HTTP client.
 *
 * <p>OpenTelemetry is a global singleton, so each test starts from a reset instance and only the
 * tests about active telemetry initialize the SDK, before the capability is built (the control
 * adapter wires its trace buffer into the SDK at construction).</p>
 */
class ControlPortHttpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISPLAY = "Control Port HTTP Test";

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Capability capability;
    private HttpServer upstream;
    private int controlPort;
    private int restPort;

    @BeforeEach
    void resetTelemetry() {
        TelemetryBootstrap.reset();
    }

    @AfterEach
    void stopEverything() throws Exception {
        try {
            if (capability != null) {
                capability.stop();
            }
        } finally {
            if (upstream != null) {
                upstream.stop(0);
            }
            TelemetryBootstrap.reset();
        }
    }

    @Test
    void healthLiveShouldReturnUpOverHttp() throws Exception {
        start(new Setup());

        HttpResponse<String> response = get("/health/live");

        assertEquals(200, response.statusCode());
        assertJson(response);
        assertEquals("{\"status\":\"UP\"}", response.body());
    }

    @Test
    void healthReadyShouldReturnUpWhenEveryAdapterIsStarted() throws Exception {
        start(new Setup().withRest());

        HttpResponse<String> response = get("/health/ready");

        assertEquals(200, response.statusCode(), response.body());
        assertJson(response);
        JsonNode body = JSON.readTree(response.body());
        assertEquals("UP", body.path("status").asString());
        assertEquals("started", adapter(body, "rest").path("state").asString());
        assertEquals(restPort, adapter(body, "rest").path("port").asInt());
        assertEquals("started", adapter(body, "control").path("state").asString());
    }

    @Test
    void healthReadyShouldReturnDegradedWhenABusinessAdapterStops() throws Exception {
        start(new Setup().withRest());
        serverAdapter("rest").stop();

        HttpResponse<String> response = get("/health/ready");

        assertEquals(503, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("DEGRADED", body.path("status").asString());
        assertEquals("stopped", adapter(body, "rest").path("state").asString());
        // Liveness does not depend on the other adapters.
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void statusShouldDescribeTheCapabilityWhenInfoIsEnabled() throws Exception {
        start(new Setup().withRest());

        HttpResponse<String> response = get("/status");

        assertEquals(200, response.statusCode(), response.body());
        assertJson(response);
        JsonNode body = JSON.readTree(response.body());
        assertEquals(DISPLAY, body.path("capability").path("display").asString());
        assertEquals(VersionHelper.getSchemaVersion(),
                body.path("capability").path("specVersion").asString());
        assertFalse(body.path("engine").path("version").asString().isEmpty(), response.body());
        assertFalse(body.path("engine").path("native").asBoolean(), response.body());
        assertTrue(body.path("uptime").asString().startsWith("PT"), response.body());
        assertEquals("inactive", body.path("otel").path("status").asString());
        assertEquals("started", adapter(body, "rest").path("state").asString());
    }

    @Test
    void statusShouldNotExistWhenInfoIsDisabled() throws Exception {
        start(new Setup().info(false));

        assertEquals(404, get("/status").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void healthRoutesShouldNotExistWhenHealthIsDisabled() throws Exception {
        start(new Setup().health(false));

        assertEquals(404, get("/health/live").statusCode());
        assertEquals(404, get("/health/ready").statusCode());
        assertEquals(200, get("/status").statusCode());
    }

    @Test
    void routesOutsideTheControlApiShouldReturnNotFound() throws Exception {
        start(new Setup());

        assertEquals(404, get("/health").statusCode());
        assertEquals(404, get("/ready").statusCode());
        assertEquals(404, get("/scripting").statusCode());
    }

    @Test
    void metricsShouldExposePrometheusTextForRealTrafficWhenTelemetryIsActive()
            throws Exception {
        TelemetryBootstrap.init("ikanos-" + DISPLAY);
        start(new Setup().withRest());
        assertEquals(200, callRest().statusCode());

        HttpResponse<String> response = get("/metrics");

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/plain"), response.headers().toString());
        assertTrue(response.body().contains("ikanos_capability_active"), response.body());
        assertTrue(response.body().contains("ikanos_request_duration_seconds"), response.body());
        assertTrue(response.body().contains("ikanos_adapter_type=\"rest\""), response.body());
        assertEquals("active",
                JSON.readTree(get("/status").body()).path("otel").path("status").asString());
    }

    @Test
    void tracesShouldListAndDetailARealRequestWhenTelemetryIsActive() throws Exception {
        TelemetryBootstrap.init("ikanos-" + DISPLAY);
        start(new Setup().withRest());
        assertEquals(200, callRest().statusCode());

        JsonNode trace = awaitTrace("rest.request");
        assertEquals("OK", trace.path("status").asString());
        String traceId = trace.path("traceId").asString();

        HttpResponse<String> detail = get("/traces/" + traceId);
        assertEquals(200, detail.statusCode(), detail.body());
        JsonNode spans = JSON.readTree(detail.body()).path("spans");
        List<String> names = new ArrayList<>();
        spans.forEach(span -> names.add(span.path("name").asString()));
        assertTrue(names.contains("rest.request"), names.toString());
        assertTrue(names.contains("http.client.GET"), names.toString());

        assertEquals(404, get("/traces/00000000000000000000000000000000").statusCode());
        JsonNode errorsOnly = JSON.readTree(get("/traces?status=ERROR").body());
        assertEquals(0, errorsOnly.path("traces").size(), errorsOnly.toString());
    }

    @Test
    void metricsAndTracesShouldReturnUnavailableWhenTelemetryIsInactive() throws Exception {
        start(new Setup());

        HttpResponse<String> metrics = get("/metrics");
        HttpResponse<String> traces = get("/traces");

        assertEquals(503, metrics.statusCode(), metrics.body());
        assertTrue(metrics.body().contains("OpenTelemetry is not active"), metrics.body());
        assertEquals(503, traces.statusCode(), traces.body());
        assertTrue(traces.body().contains("OpenTelemetry is not active"), traces.body());
    }

    @Test
    void metricsAndTracesShouldNotExistWhenObservabilityIsDisabled() throws Exception {
        start(new Setup().observabilityDisabled());

        assertEquals(404, get("/metrics").statusCode());
        assertEquals(404, get("/traces").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    // ── Setup ───────────────────────────────────────────────────────────────

    /** What the started capability declares. Defaults: health and info on, no REST adapter. */
    private static final class Setup {
        private boolean health = true;
        private boolean info = true;
        private boolean rest;
        private boolean observabilityDisabled;

        Setup health(boolean value) {
            this.health = value;
            return this;
        }

        Setup info(boolean value) {
            this.info = value;
            return this;
        }

        Setup withRest() {
            this.rest = true;
            return this;
        }

        Setup observabilityDisabled() {
            this.observabilityDisabled = true;
            return this;
        }
    }

    private void start(Setup setup) throws Exception {
        controlPort = freePort();
        StringBuilder yaml = new StringBuilder("""
                ikanos: "%s"
                info:
                  display: "%s"
                capability:
                  exposes:
                    - type: "control"
                      address: "localhost"
                      port: %d
                      management:
                        health: %b
                        info: %b
                """.formatted(VersionHelper.getSchemaVersion(), DISPLAY, controlPort,
                setup.health, setup.info));
        if (setup.observabilityDisabled) {
            yaml.append("""
                          observability:
                            enabled: false
                    """);
        }
        if (setup.rest) {
            int upstreamPort = startUpstream();
            restPort = freePort();
            yaml.append("""
                        - type: "rest"
                          address: "localhost"
                          port: %d
                          namespace: "probe"
                          resources:
                            - path: "/hello"
                              name: "hello"
                              operations:
                                - method: "GET"
                                  name: "get-hello"
                                  call: "upstream.get-hello"
                      consumes:
                        - type: "http"
                          namespace: "upstream"
                          baseUri: "http://localhost:%d"
                          resources:
                            - path: "/hello"
                              name: "hello"
                              operations:
                                - method: "GET"
                                  name: "get-hello"
                    """.formatted(restPort, upstreamPort));
        }

        ObjectMapper yamlMapper = IkanosMappers.lenientYaml();
        capability = new Capability(yamlMapper.readValue(yaml.toString(), IkanosSpec.class));
        capability.start();
    }

    /** A stub upstream API answering {@code GET /hello} with a small JSON body. */
    private int startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/hello", exchange -> {
            byte[] body = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
        return upstream.getAddress().getPort();
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws Exception {
        return send(controlPort, path);
    }

    private HttpResponse<String> callRest() throws Exception {
        return send(restPort, "/hello");
    }

    private HttpResponse<String> send(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Spans end after the response is written, so the trace may appear a moment later. */
    private JsonNode awaitTrace(String operation) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get("/traces");
            assertEquals(200, response.statusCode(), response.body());
            last = response.body();
            for (JsonNode trace : JSON.readTree(last).path("traces")) {
                if (operation.equals(trace.path("operation").asString())) {
                    return trace;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No '" + operation + "' trace on /traces: " + last);
    }

    private static void assertJson(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json"), response.headers().toString());
    }

    private static JsonNode adapter(JsonNode body, String type) {
        for (JsonNode adapter : body.path("adapters")) {
            if (type.equals(adapter.path("type").asString())) {
                return adapter;
            }
        }
        throw new AssertionError("No '" + type + "' adapter in " + body);
    }

    private ServerAdapter serverAdapter(String type) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (type.equals(adapter.getSpec().getType())) {
                return adapter;
            }
        }
        throw new AssertionError("No '" + type + "' server adapter");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
