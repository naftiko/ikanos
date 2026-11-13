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
package io.ikanos.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #942, end to end: a template may name a binding by its bind's namespace and key, as in
 * {@code {{secrets.TOKEN}}}, which the examples, the schema and the rules teach. Before, the engine
 * only knew {@code {{TOKEN}}}: a consumed credential written that way was never sent, an exposed one
 * refused the capability, and a base URI kept the placeholder.
 */
class NamespacedBindingIntegrationTest {

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
    void consumedTokenNamedByNamespaceShouldReachTheUpstream() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            start("{{secrets.TOKEN}}", null, "http://localhost:" + upstream.getAddress().getPort());

            assertFalse(logs.messages().stream()
                    .anyMatch(m -> m.contains("up authentication token")),
                    "no load-time warning for a bound reference: " + logs.messages());
        }

        HttpResponse<String> response = get("/call", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("Bearer s3cr3t"), upstreamAuthorizations);
    }

    @Test
    void consumedTokenWithAPrefixAndANamespaceShouldReachTheUpstream() throws Exception {
        start("sk-{{secrets.TOKEN}}", null, "http://localhost:" + upstream.getAddress().getPort());

        assertEquals(200, get("/call", null).statusCode());
        assertEquals(List.of("Bearer sk-s3cr3t"), upstreamAuthorizations);
    }

    @Test
    void consumedTokenNamedByKeyAloneShouldStillReachTheUpstream() throws Exception {
        start("{{TOKEN}}", null, "http://localhost:" + upstream.getAddress().getPort());

        assertEquals(200, get("/call", null).statusCode());
        assertEquals(List.of("Bearer s3cr3t"), upstreamAuthorizations);
    }

    @Test
    void consumedTokenWithAMisspelledNamespaceShouldNeverReachTheUpstream() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            start("{{secret.TOKEN}}", null, "http://localhost:" + upstream.getAddress().getPort());

            assertTrue(logs.messages().stream()
                    .anyMatch(m -> m.contains("up authentication token uses secret.TOKEN")),
                    "a load-time warning names the reference: " + logs.messages());
        }

        HttpResponse<String> response = get("/call", null);

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(List.of(), upstreamAuthorizations, "nothing was sent to the upstream");
    }

    @Test
    void baseUriNamedByNamespaceShouldReachTheUpstream() throws Exception {
        start("{{TOKEN}}", null, "http://localhost:{{secrets.PORT}}");

        assertEquals(200, get("/call", null).statusCode());
        assertEquals(1, upstreamAuthorizations.size());
    }

    @Test
    void exposedTokenNamedByNamespaceShouldBeTheOneExpected() throws Exception {
        start("{{TOKEN}}", "{{secrets.EXPOSED}}",
                "http://localhost:" + upstream.getAddress().getPort());

        assertEquals(200, get("/call", "Bearer inbound-key").statusCode());
        assertEquals(401, get("/call", "Bearer {{secrets.EXPOSED}}").statusCode(),
                "the template text is not the credential");
        assertEquals(401, get("/call", null).statusCode());
    }

    @Test
    void exposedTokenWithAMisspelledNamespaceShouldRefuseTheCapability() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> start("{{TOKEN}}", "{{secret.EXPOSED}}",
                        "http://localhost:" + upstream.getAddress().getPort()));

        assertTrue(e.getMessage().contains("secret.EXPOSED"), e.getMessage());
        assertFalse(e.getMessage().contains("inbound-key"), "the error must not show a secret");
    }

    /**
     * @param consumedToken the consumed bearer token template
     * @param exposedToken the exposed bearer token template, or null for no authentication
     * @param baseUri the consumed base URI, a template or not
     */
    private void start(String consumedToken, String exposedToken, String baseUri)
            throws Exception {
        Path secretsFile = dir.resolve("secrets.yaml");
        Files.writeString(secretsFile, """
                token: "s3cr3t"
                exposed: "inbound-key"
                port: "%d"
                """.formatted(upstream.getAddress().getPort()));
        restPort = freePort();
        String exposedAuthentication = exposedToken == null ? "" : """
                      authentication:
                        type: bearer
                        token: "%s"
                """.formatted(exposedToken);
        String yaml = """
                ikanos: "%s"
                binds:
                  - namespace: "secrets"
                    location: "%s"
                    keys:
                      TOKEN: "token"
                      EXPOSED: "exposed"
                      PORT: "port"
                capability:
                  consumes:
                    - namespace: up
                      type: http
                      baseUri: "%s"
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
                %s      resources:
                        call:
                          path: "/call"
                          operations:
                            get:
                              method: GET
                              call: up.get-item
                """.formatted(IKANOS, secretsFile.toUri(), baseUri, consumedToken, restPort,
                exposedAuthentication);
        capability = new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        capability.start();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + path)).timeout(Duration.ofSeconds(15));
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
