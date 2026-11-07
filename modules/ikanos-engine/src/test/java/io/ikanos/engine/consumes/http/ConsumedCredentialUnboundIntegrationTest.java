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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.LogCapture;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #843, end to end: a consumed bearer token that names an unbound variable (a typo) is never sent
 * to the upstream, and the capability warns about it when it loads.
 */
class ConsumedCredentialUnboundIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> upstreamAuthorizations = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private Capability capability;
    private int restPort;

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/item", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            upstreamAuthorizations.add(authorization == null ? "<none>" : authorization);
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
    void misspelledTokenVariableShouldNeverReachTheUpstream() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            start("TOKEN: \"s3cr3t\"\n", "{{TOKN}}");

            assertTrue(logs.messages().stream().anyMatch(
                    m -> m.contains("up authentication token uses TOKN")),
                    "a load-time warning names the variable: " + logs.messages());
            assertFalse(logs.messages().stream().anyMatch(m -> m.contains("s3cr3t")),
                    "no log shows the secret");
        }

        HttpResponse<String> response = get("/call");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(List.of(), upstreamAuthorizations, "nothing was sent to the upstream");
    }

    @Test
    void emptyTokenBindingShouldNeverReachTheUpstream() throws Exception {
        start("TOKEN: \"\"\n", "{{TOKEN}}");

        HttpResponse<String> response = get("/call");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(List.of(), upstreamAuthorizations, "nothing was sent to the upstream");
    }

    @Test
    void boundTokenShouldReachTheUpstream() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            start("TOKEN: \"s3cr3t\"\n", "{{TOKEN}}");

            assertFalse(logs.messages().stream()
                    .anyMatch(m -> m.contains("up authentication token")),
                    "no warning for a bound variable: " + logs.messages());
        }

        HttpResponse<String> response = get("/call");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("Bearer s3cr3t"), upstreamAuthorizations);
    }

    @Test
    void mcpUpstreamCredentialWithMisspelledVariableShouldBeWarnedAboutAtLoad() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            capability = new Capability(IkanosMappers.lenientYaml().readValue("""
                    ikanos: "%s"
                    capability:
                      consumes:
                        - namespace: billing
                          type: mcp
                          endpoint: "http://localhost:%d/"
                          discovery: off
                          authentication:
                            type: bearer
                            token: "{{TOKN}}"
                          tools:
                            get-invoice:
                              description: Retrieve one invoice
                      exposes:
                        - type: rest
                          address: "localhost"
                          port: %d
                          namespace: demo
                          resources:
                            call:
                              path: "/call"
                              operations:
                                get:
                                  method: GET
                                  call: billing.get-invoice
                    """.formatted(IKANOS, upstream.getAddress().getPort(), freePort()),
                    IkanosSpec.class));

            assertTrue(logs.messages().stream().anyMatch(
                    m -> m.contains("billing authentication token uses TOKN")),
                    "a load-time warning names the variable: " + logs.messages());
        }
    }

    private void start(String secrets, String token) throws Exception {
        Path secretsFile = dir.resolve("secrets.yaml");
        Files.writeString(secretsFile, secrets);
        restPort = freePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  binds:
                    - namespace: "secrets"
                      location: "%s"
                      keys:
                        TOKEN: "TOKEN"
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "http://localhost:%d"
                      authentication:
                        type: bearer
                        token: "%s"
                      resources:
                        item:
                          path: "/item"
                          operations:
                            get-item:
                              method: GET
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                        call:
                          path: "/call"
                          operations:
                            get:
                              method: GET
                              call: up.get-item
                """.formatted(IKANOS, secretsFile.toUri(), upstream.getAddress().getPort(), token,
                restPort);
        capability = new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        capability.start();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + restPort + path))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
