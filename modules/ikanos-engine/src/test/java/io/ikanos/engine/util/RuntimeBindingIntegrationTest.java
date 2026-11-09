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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
 * #845: a credential takes a secret from the environment through a `binds` entry without
 * `location` and a `{{VAR}}` expression. The schema used to document `$env.NAME` instead, which
 * no code reads. {@value #ENV} is set by the engine's surefire configuration.
 */
class RuntimeBindingIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String ENV = "IKANOS_TEST_BIND_TOKEN";
    private static final String UNSET = "IKANOS_TEST_BIND_NEVER_SET";

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
    void consumedTokenShouldBeReadFromTheEnvironmentThroughBinds() throws Exception {
        String secret = requireEnv();
        startConsumer(ENV, "{{TOKEN}}");

        HttpResponse<String> response = get("/call", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("Bearer " + secret), upstreamAuthorizations);
    }

    @Test
    void exposedTokenShouldBeReadFromTheEnvironmentThroughBinds() throws Exception {
        String secret = requireEnv();
        restPort = freePort();
        capability = new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                binds:
                  - namespace: "env"
                    keys:
                      TOKEN: "%s"
                capability:
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      authentication:
                        type: bearer
                        token: "{{TOKEN}}"
                      resources:
                        hello:
                          path: "/hello"
                          operations:
                            get:
                              method: GET
                              outputParameters:
                                - type: string
                                  value: "ok"
                """.formatted(IKANOS, ENV, restPort), IkanosSpec.class));
        capability.start();

        assertEquals(200, get("/hello", "Bearer " + secret).statusCode());
        assertEquals(401, get("/hello", "Bearer {{TOKEN}}").statusCode(),
                "the expression text is not the token");
        assertEquals(401, get("/hello", null).statusCode());
    }

    @Test
    void envExpressionShouldBeSentAsLiteralText() throws Exception {
        String secret = requireEnv();
        startConsumer(ENV, "$env." + ENV);

        HttpResponse<String> response = get("/call", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("Bearer $env." + ENV), upstreamAuthorizations,
                "no code reads $env.NAME, which is why the schema no longer documents it");
        assertFalse(upstreamAuthorizations.get(0).contains(secret));
    }

    @Test
    void missingEnvironmentVariableShouldStopTheCapabilityFromLoading() {
        assertNull(System.getenv(UNSET), UNSET + " must not be set for this test");

        Exception e = assertThrows(Exception.class, () -> startConsumer(UNSET, "{{TOKEN}}"));

        assertTrue(String.valueOf(e.getMessage()).contains(UNSET), String.valueOf(e));
        assertEquals(List.of(), upstreamAuthorizations, "nothing was sent to the upstream");
    }

    private void startConsumer(String environmentVariable, String token) throws Exception {
        restPort = freePort();
        String yaml = """
                ikanos: "%s"
                binds:
                  - namespace: "env"
                    keys:
                      TOKEN: "%s"
                capability:
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
                """.formatted(IKANOS, environmentVariable, upstream.getAddress().getPort(),
                token, restPort);
        capability = new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        capability.start();
    }

    private static String requireEnv() {
        String value = System.getenv(ENV);
        assumeTrue(value != null && !value.isEmpty(),
                ENV + " is set by the engine's surefire configuration");
        return value;
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + restPort + path))
                .timeout(Duration.ofSeconds(15));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
