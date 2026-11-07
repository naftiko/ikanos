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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #841, end to end: an API key placed in the query reaches the upstream with the exact value it
 * is bound to, even with characters that have a meaning in a query string.
 */
class ConsumedApiKeyQueryEncodingIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> upstreamQueries = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private Capability capability;

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/items", exchange -> {
            upstreamQueries.add(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        upstream.stop(0);
    }

    @Test
    void apiKeyInTheQueryShouldReachTheUpstreamUnchanged() throws Exception {
        Path secrets = dir.resolve("secrets.yaml");
        Files.writeString(secrets, "API_KEY: \"a+b&c=d #1\"\n");
        int restPort = freePort();
        capability = new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  binds:
                    - namespace: "secrets"
                      location: "%s"
                      keys:
                        API_KEY: "API_KEY"
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "http://localhost:%d"
                      authentication:
                        type: apikey
                        key: api_key
                        placement: query
                        value: "{{API_KEY}}"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: GET
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                        items:
                          path: "/items"
                          operations:
                            get:
                              method: GET
                              call: up.list
                """.formatted(IKANOS, secrets.toUri(), upstream.getAddress().getPort(),
                restPort), IkanosSpec.class));
        capability.start();

        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/items"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, upstreamQueries.size(), upstreamQueries.toString());
        assertEquals(List.of("api_key=a+b&c=d #1"), decodedParameters(upstreamQueries.get(0)),
                "raw query: " + upstreamQueries.get(0));
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
