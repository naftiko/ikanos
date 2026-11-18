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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
 * #1039, end to end: a flow input that a REST operation inherits reaches the flow, and the API it
 * calls, with its declared type, as it does through an MCP tool. An {@code integer} read from the
 * query is a JSON number in a consumed JSON body, a value that is not an integer answers
 * {@code 400}, and an {@code object} read from the query is sent on as JSON.
 */
class RestRefInheritedInputsForwardingIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> upstreamQueries = new CopyOnWriteArrayList<>();
    private final List<String> upstreamBodies = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private Capability capability;
    private int restPort;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            upstreamQueries.add(query == null ? ""
                    : URLDecoder.decode(query, StandardCharsets.UTF_8));
            upstreamBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
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
                      baseUri: "http://localhost:%d"
                      resources:
                        counts:
                          path: "/counts"
                          operations:
                            count:
                              method: POST
                              body:
                                type: json
                                data:
                                  limit: "{{limit}}"
                                  active: "{{active}}"
                        items:
                          path: "/items"
                          operations:
                            search:
                              method: GET
                              inputParameters:
                                filter:
                                  in: query
                                  type: object
                  aggregates:
                    - namespace: fleet
                      flows:
                        count-ships:
                          description: Count ships.
                          inputParameters:
                            limit:
                              type: integer
                              description: Largest count
                            active:
                              type: boolean
                              description: Active ships only
                          call: up.count
                        find-ships:
                          description: Find ships matching a filter.
                          inputParameters:
                            filter:
                              type: object
                              description: Filter on ship fields
                          call: up.search
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: fleet-api
                      resources:
                        counts:
                          path: "/counts"
                          operations:
                            count:
                              method: GET
                              ref: fleet.count-ships
                        find:
                          path: "/find"
                          operations:
                            find:
                              method: GET
                              ref: fleet.find-ships
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
    void inheritedTypedScalarsShouldReachAJsonBodyWithTheirType() throws Exception {
        HttpResponse<String> response = get("/counts?limit=5&active=true");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("{\"limit\":5,\"active\":true}"), upstreamBodies);
    }

    @Test
    void anInheritedIntegerThatDoesNotParseShouldAnswerBadRequest() throws Exception {
        for (String query : List.of("limit=abc", "limit=1.5", "active=yes")) {
            HttpResponse<String> response = get("/counts?" + query);

            assertEquals(400, response.statusCode(), query + ": " + response.body());
        }
        assertEquals(List.of(), upstreamBodies);
    }

    @Test
    void anInheritedObjectQueryInputShouldReachTheUpstreamAsJson() throws Exception {
        HttpResponse<String> response = get("/find?filter="
                + URLEncoder.encode("{\"flag\":\"NO\"}", StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("filter={\"flag\":\"NO\"}"), upstreamQueries);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + pathAndQuery))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
