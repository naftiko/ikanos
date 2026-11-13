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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A precondition that cannot be parsed, raised inside a called aggregate flow, answers 400 like
 * any other bad input, not 500 (#1001), and the write never reaches the upstream.
 */
class FlowPreconditionIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> upstreamCalls = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private Capability capability;
    private int restPort;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1", exchange -> {
            upstreamCalls.add(exchange.getRequestMethod() + " "
                    + exchange.getRequestHeaders().getFirst("If-Match"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        restPort = freePort();
        capability = new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            update-item:
                              method: PUT
                              inputParameters:
                                If-Match:
                                  in: header
                                  value: "{{etag}}"
                  aggregates:
                    - namespace: catalog
                      flows:
                        update:
                          description: "Update an item if it did not change"
                          call: up.update-item
                        update-steps:
                          description: "Update an item if it did not change, in a step"
                          inputParameters:
                            etag:
                              type: string
                              description: "Entity tag the update is conditional on"
                          steps:
                            update:
                              type: call
                              call: up.update-item
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                        flow-ref:
                          path: "/flow-ref"
                          operations:
                            put:
                              method: PUT
                              inputParameters:
                                etag:
                                  in: query
                                  type: string
                                  description: "Entity tag the update is conditional on"
                              ref: catalog.update
                        flow-step:
                          path: "/flow-step"
                          operations:
                            put:
                              method: PUT
                              inputParameters:
                                etag:
                                  in: query
                                  type: string
                                  description: "Entity tag the update is conditional on"
                              steps:
                                update:
                                  type: call
                                  call: catalog.update
                        flow-steps-ref:
                          path: "/flow-steps-ref"
                          operations:
                            put:
                              method: PUT
                              inputParameters:
                                etag:
                                  in: query
                                  type: string
                                  description: "Entity tag the update is conditional on"
                              ref: catalog.update-steps
                        flow-steps-step:
                          path: "/flow-steps-step"
                          operations:
                            put:
                              method: PUT
                              inputParameters:
                                etag:
                                  in: query
                                  type: string
                                  description: "Entity tag the update is conditional on"
                              steps:
                                update:
                                  type: call
                                  call: catalog.update-steps
                """.formatted(IKANOS, upstream.getAddress().getPort(), restPort),
                IkanosSpec.class));
        capability.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        upstream.stop(0);
    }

    @Test
    void refToFlowShouldSendQuotedIfMatchUpstream() throws Exception {
        HttpResponse<String> response = put("/flow-ref?etag=%22v1%22");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("PUT \"v1\""), upstreamCalls);
    }

    @Test
    void refToFlowShouldAnswerBadRequestWhenIfMatchCannotBeParsed() throws Exception {
        HttpResponse<String> response = put("/flow-ref?etag=v1");

        assertEquals(400, response.statusCode(),
                "#1001: an invalid precondition raised in a called flow is bad input, not a "
                        + "server error: " + response.body());
        assertFalse(response.body().contains("v1"), "the error must not echo the value");
        assertTrue(upstreamCalls.isEmpty(), upstreamCalls.toString());
    }

    @Test
    void stepCallingFlowShouldAnswerBadRequestWhenIfMatchCannotBeParsed() throws Exception {
        HttpResponse<String> response = put("/flow-step?etag=v1");

        assertEquals(400, response.statusCode(),
                "#1001: an invalid precondition raised in a flow called by a step is bad input, "
                        + "not a server error: " + response.body());
        assertFalse(response.body().contains("v1"), "the error must not echo the value");
        assertTrue(upstreamCalls.isEmpty(), upstreamCalls.toString());
    }

    @Test
    void flowWithItsOwnCallStepShouldSendQuotedIfMatchUpstream() throws Exception {
        HttpResponse<String> response = put("/flow-steps-step?etag=%22v1%22");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("PUT \"v1\""), upstreamCalls);
    }

    @Test
    void flowWithItsOwnCallStepShouldAnswerBadRequestWhenIfMatchCannotBeParsed()
            throws Exception {
        for (String path : List.of("/flow-steps-step", "/flow-steps-ref")) {
            HttpResponse<String> response = put(path + "?etag=v1");

            assertEquals(400, response.statusCode(),
                    "#1001: an invalid precondition raised by the call step of a called flow is "
                            + "bad input, not a server error (" + path + "): " + response.body());
            assertFalse(response.body().contains("v1"), "the error must not echo the value");
        }
        assertTrue(upstreamCalls.isEmpty(), upstreamCalls.toString());
    }

    private HttpResponse<String> put(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + pathAndQuery))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
