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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A capability past its {@code maxRequestsInProgress} refuses the excess with a 503, keeps
 * answering the requests it admitted, and its control port keeps answering (#920).
 */
class RequestsInProgressIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    /** Upstream calls wait here until the test lets them through. */
    private final Semaphore upstreamGate = new Semaphore(0);
    private final CountDownLatch upstreamReached = new CountDownLatch(2);
    private Component upstream;
    private Capability capability;
    private int restPort;
    private int controlPort;

    @BeforeEach
    void startCapability() throws Exception {
        int upstreamPort = freePort();
        restPort = freePort();
        controlPort = freePort();
        upstream = slowUpstream(upstreamPort);
        upstream.start();
        capability = capabilityFromYaml(yaml(upstreamPort, restPort, controlPort, "maxRequestsInProgress: 2"));
        capability.start();
    }

    @AfterEach
    void stop() throws Exception {
        upstreamGate.release(100);
        if (capability != null) {
            capability.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void restAdapterShouldRefuseTheExcessWith503AndKeepAnsweringTheAdmittedRequests()
            throws Exception {
        List<CompletableFuture<HttpResponse<String>>> admitted = new ArrayList<>();
        admitted.add(sendAsync("/slow"));
        admitted.add(sendAsync("/slow"));
        assertTrue(upstreamReached.await(10, TimeUnit.SECONDS),
                "the first two requests should be admitted and reach the upstream");

        HttpResponse<String> refused = send("/slow");
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + controlPort + "/health/live")).build(),
                HttpResponse.BodyHandlers.ofString());
        upstreamGate.release(2);

        assertEquals(503, refused.statusCode(), refused.body());
        assertTrue(refused.headers().firstValue("Retry-After").isPresent(),
                "a refused client should be told when to retry");
        assertEquals(200, health.statusCode(), "the control port is never limited");
        for (CompletableFuture<HttpResponse<String>> response : admitted) {
            assertEquals(200, response.get(10, TimeUnit.SECONDS).statusCode());
        }
    }

    @Test
    void restAdapterShouldAdmitRequestsAgainOnceTheOnesInProgressAreDone() throws Exception {
        upstreamGate.release(100);

        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("/slow").statusCode());
        }
    }

    @Test
    void adaptersShouldUseTheConfiguredLimitAndTheControlPortNone() {
        ServerAdapter rest = capability.getServerAdapters().get(0);
        ServerAdapter control = capability.getServerAdapters().get(1);

        RequestsInProgressFilter limit = assertInstanceOf(RequestsInProgressFilter.class,
                rest.getHandler());
        assertEquals(2, limit.getLimit());
        assertTrue(!(control.getHandler() instanceof RequestsInProgressFilter),
                "the control port must not be limited");
        assertEquals(0, control.getRequestsInProgressLimit());
    }

    @Test
    void adapterShouldDefaultTo16RequestsPerCpu() throws Exception {
        Capability unset = capabilityFromYaml(yaml(freePort(), freePort(), freePort(), ""));

        assertEquals(ServerAdapter.DEFAULT_REQUESTS_IN_PROGRESS_PER_CPU
                * Runtime.getRuntime().availableProcessors(),
                unset.getServerAdapters().get(0).maxRequestsInProgress());
    }

    @Test
    void adapterShouldGetEnoughJettyThreadsForALimitAbove200() throws Exception {
        Capability large = capabilityFromYaml(yaml(freePort(), freePort(), freePort(),
                "maxRequestsInProgress: 300"));
        Capability small = capabilityFromYaml(yaml(freePort(), freePort(), freePort(),
                "maxRequestsInProgress: 4"));

        assertEquals("350", maxThreads(large.getServerAdapters().get(0).getServer()));
        assertEquals(null, maxThreads(small.getServerAdapters().get(0).getServer()),
                "below Restlet's default of 200 threads, it is left as it is");
    }

    @Test
    void sharedListenerShouldGetEnoughJettyThreadsForAllItsAdapters() throws Exception {
        int port = freePort();
        Capability shared = capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      path: "/a"
                      namespace: a
                      maxRequestsInProgress: 150
                      resources:
                        ping:
                          path: "/ping"
                          operations:
                            ping:
                              method: GET
                              outputParameters:
                                - type: string
                                  value: "ok"
                    - type: rest
                      address: "localhost"
                      port: %d
                      path: "/b"
                      namespace: b
                      maxRequestsInProgress: 100
                      resources:
                        ping:
                          path: "/ping"
                          operations:
                            ping:
                              method: GET
                              outputParameters:
                                - type: string
                                  value: "ok"
                  consumes: []
                """.formatted(IKANOS, port, port));

        assertEquals("300",
                maxThreads(shared.getServerHost().getSharedListeners().get(0).server()));
    }

    @Test
    void requestsRefusedByAuthenticationShouldNotTakeASlot() throws Exception {
        int upstreamPort = freePort();
        int port = freePort();
        Component secured = slowUpstream(upstreamPort);
        secured.start();
        Capability withKey = capabilityFromYaml(yaml(upstreamPort, port, freePort(), """
                maxRequestsInProgress: 1
                      authentication:
                        type: apikey
                        key: X-Api-Key
                        value: s3cr3t"""));
        withKey.start();
        try {
            CompletableFuture<HttpResponse<String>> admitted = client.sendAsync(
                    keyed(port, "s3cr3t"), HttpResponse.BodyHandlers.ofString());
            // The latch counts two upstream calls; with one slot, only one gets there
            long deadline = System.currentTimeMillis() + 10_000;
            while (upstreamReached.getCount() > 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, upstreamReached.getCount(), "the admitted request should reach the upstream");

            List<Integer> badKeys = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                badKeys.add(client.send(keyed(port, "wrong"),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            int goodKeyWhileFull = client.send(keyed(port, "s3cr3t"),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
            upstreamGate.release(1);

            assertEquals(List.of(401, 401, 401, 401, 401), badKeys);
            assertEquals(503, goodKeyWhileFull, "the one slot is taken by the admitted request");
            assertEquals(200, admitted.get(10, TimeUnit.SECONDS).statusCode());
        } finally {
            withKey.stop();
            secured.stop();
        }
    }

    @Test
    void mcpAdapterShouldBeLimitedToo() throws Exception {
        Capability mcp = capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: mcp
                      address: "localhost"
                      port: %d
                      namespace: demo
                      description: "Demo"
                      maxRequestsInProgress: 3
                      tools:
                        hello:
                          description: "Say hello"
                          outputParameters:
                            - type: string
                              value: "hi"
                  consumes: []
                """.formatted(IKANOS, freePort()));

        assertEquals(3, mcp.getServerAdapters().get(0).getRequestsInProgressLimit());
    }

    private HttpRequest keyed(int port, String key) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/slow"))
                .header("X-Api-Key", key).timeout(Duration.ofSeconds(15)).build();
    }

    private static String maxThreads(org.restlet.Server server) {
        return server.getContext().getParameters().getFirstValue("threadPool.maxThreads");
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(String path) {
        return client.sendAsync(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String path) throws Exception {
        return client.send(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + restPort + path))
                .timeout(Duration.ofSeconds(15)).build();
    }

    private Component slowUpstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                return new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        upstreamReached.countDown();
                        try {
                            upstreamGate.tryAcquire(15, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
                    }
                };
            }
        });
        return component;
    }

    private static String yaml(int upstreamPort, int restPort, int controlPort, String limitLine) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: upstream
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        slow:
                          path: "/slow"
                          operations:
                            get-slow:
                              method: GET
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      %s
                      resources:
                        slow:
                          path: "/slow"
                          operations:
                            get-slow:
                              method: GET
                              call: upstream.get-slow
                    - type: control
                      address: "localhost"
                      port: %d
                      management:
                        health: true
                """.formatted(IKANOS, upstreamPort, restPort, limitLine, controlPort);
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
