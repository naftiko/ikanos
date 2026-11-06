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
 * #840, end to end: a consumed apikey without placement reaches the upstream as a header, where
 * the call used to fail before anything was sent.
 */
class ConsumedApiKeyPlacementIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> upstreamKeys = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private Capability capability;

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/items", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            upstreamKeys.add(key == null ? "<none>" : key);
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
    void apiKeyWithoutPlacementShouldReachTheUpstreamAsAHeader() throws Exception {
        int restPort = freePort();
        capability = new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "http://localhost:%d"
                      authentication:
                        type: apikey
                        key: X-Api-Key
                        value: "k1"
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
                """.formatted(IKANOS, upstream.getAddress().getPort(), restPort),
                IkanosSpec.class));
        capability.start();

        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/items"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("k1"), upstreamKeys);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
