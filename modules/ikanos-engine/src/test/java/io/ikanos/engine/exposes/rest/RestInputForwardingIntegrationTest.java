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
import java.util.ArrayList;
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
 * #1078, end to end: a REST input read as its declared type reaches a consumed API as that type.
 * A JSON object query input forwarded to a consumed query parameter, header or URI stays JSON, and
 * an {@code integer} query input is a JSON number in a consumed JSON body, as over MCP.
 */
class RestInputForwardingIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> upstreamQueries = new CopyOnWriteArrayList<>();
    private final List<String> upstreamFilterHeaders = new CopyOnWriteArrayList<>();
    private final List<String> upstreamBodies = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private Capability capability;
    private int restPort;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            upstreamQueries.add(String.valueOf(exchange.getRequestURI().getRawQuery()));
            upstreamFilterHeaders.add(
                    String.valueOf(exchange.getRequestHeaders().getFirst("X-Filter")));
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
                        items:
                          path: "/items?f={{filter}}"
                          operations:
                            search:
                              method: GET
                              inputParameters:
                                filter:
                                  in: query
                                  type: object
                                filters:
                                  in: query
                                  type: array
                                X-Filter:
                                  in: header
                                  value: "{{filter}}"
                        counts:
                          path: "/counts"
                          operations:
                            count:
                              method: POST
                              body:
                                type: json
                                data:
                                  limit: "{{limit}}"
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                        items:
                          path: "/items"
                          operations:
                            search:
                              method: GET
                              call: up.search
                              inputParameters:
                                filter:
                                  in: query
                                  type: object
                                  description: Filter on item fields
                                filters:
                                  in: query
                                  type: array
                                  items:
                                    type: object
                                  description: Alternative filters
                        counts:
                          path: "/counts"
                          operations:
                            count:
                              method: GET
                              call: up.count
                              inputParameters:
                                limit:
                                  in: query
                                  type: integer
                                  description: Largest count
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
    void anObjectQueryInputShouldReachTheUpstreamAsJson() throws Exception {
        HttpResponse<String> response = get("/items?filter="
                + encode("{\"flag\":\"NO\"}") + "&filters=" + encode("[{\"flag\":\"PA\"}]"));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, upstreamQueries.size(), upstreamQueries.toString());
        // In the URI template, as a consumed query parameter and per element of a list
        assertEquals(List.of("f={\"flag\":\"NO\"}", "filter={\"flag\":\"NO\"}",
                "filters={\"flag\":\"PA\"}"), decodedParameters(upstreamQueries.get(0)),
                "raw query: " + upstreamQueries.get(0));
        assertEquals("{\"flag\":\"NO\"}", upstreamFilterHeaders.get(0));
    }

    @Test
    void anIntegerQueryInputShouldReachAJsonBodyAsANumber() throws Exception {
        HttpResponse<String> response = get("/counts?limit=5");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("{\"limit\":5}"), upstreamBodies);
    }

    @Test
    void anIntegerQueryInputThatDoesNotParseShouldAnswerBadRequest() throws Exception {
        HttpResponse<String> response = get("/counts?limit=abc");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(List.of(), upstreamBodies);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + pathAndQuery))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Each parameter as {@code name=value}, decoded as an API reads a query string. */
    private static List<String> decodedParameters(String rawQuery) {
        List<String> parameters = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.add(URLDecoder.decode(parts[0], StandardCharsets.UTF_8) + "="
                    + (parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""));
        }
        return parameters;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
