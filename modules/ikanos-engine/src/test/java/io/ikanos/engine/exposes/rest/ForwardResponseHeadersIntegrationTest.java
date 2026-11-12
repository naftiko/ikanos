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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * End-to-end check of the response headers a forward-mode REST resource relays from its upstream
 * (#1002): a real upstream, the adapter on a real port, and a real HTTP client.
 */
public class ForwardResponseHeadersIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static HttpServer upstream;
    private static ServerAdapter adapter;
    private static int upstreamPort;
    private static int exposedPort;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        upstreamPort = freePort();
        upstream = HttpServer.create(new InetSocketAddress("localhost", upstreamPort), 0);
        respond("/v1/created", 201, Map.of(
                "Location", List.of("http://localhost:" + upstreamPort + "/v1/items/42"),
                "Cache-Control", List.of("max-age=60"),
                "Vary", List.of("Accept"),
                "X-Request-Id", List.of("r-1"),
                "X-Internal-Node", List.of("node-7"),
                "Set-Cookie", List.of("session=abc"),
                "ETag", List.of("\"v1\""),
                "Content-Disposition", List.of("attachment; filename=\"my plan.pdf\"")));
        respond("/v1/relative", 201, Map.of("Location", List.of("/v1/items/43")));
        respond("/v1/relative-outside", 201, Map.of("Location", List.of("/internal/x")));
        respond("/v1/upstream-outside", 201,
                Map.of("Location", List.of("http://localhost:" + upstreamPort + "/admin")));
        respond("/v1/vary-cookie", 200, Map.of("Vary", List.of("Cookie")));
        respond("/v1/negotiate", 401, Map.of("WWW-Authenticate", List.of("Negotiate")));
        respond("/v1/busy", 503, Map.of("Retry-After", List.of("120")));
        respond("/v1/locked", 401, Map.of("WWW-Authenticate", List.of("Basic realm=\"up\"")));
        respond("/v1/read-only", 405, Map.of("Allow", List.of("GET, HEAD")));
        upstream.start();

        exposedPort = freePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                      resources:
                        - path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedResponseHeaders:
                              - X-Request-Id
                        - path: "/cookies/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedResponseHeaders:
                              - Set-Cookie
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://localhost:%d/v1"
                """.formatted(IKANOS, exposedPort, upstreamPort);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        adapter = new Capability(spec).getServerAdapters().get(0);
        adapter.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static void respond(String path, int status, Map<String, List<String>> headers) {
        upstream.createContext(path, exchange -> {
            headers.forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + exposedPort + path)).GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    public void forwardShouldRewriteAbsoluteLocationToAPathAbsoluteExposedPath()
            throws Exception {
        HttpResponse<String> response = get("/proxy/created");

        assertEquals(201, response.statusCode());
        assertEquals("/proxy/items/42", response.headers().firstValue("Location").orElse(null));
    }

    @Test
    public void forwardShouldRewriteRelativeLocationToAPathAbsoluteExposedPath()
            throws Exception {
        assertEquals("/proxy/items/43",
                get("/proxy/relative").headers().firstValue("Location").orElse(null));
    }

    @Test
    public void forwardShouldRelayARelativeLocationOutsideTheBaseUriUnchanged() throws Exception {
        assertEquals("/internal/x",
                get("/proxy/relative-outside").headers().firstValue("Location").orElse(null));
    }

    @Test
    public void forwardShouldDropALocationOnTheUpstreamOutsideTheBaseUri() throws Exception {
        HttpResponse<String> response = get("/proxy/upstream-outside");

        assertEquals(201, response.statusCode());
        assertFalse(response.headers().firstValue("Location").isPresent(),
                "the upstream host must not reach the caller");
    }

    @Test
    public void forwardShouldVaryOnEverythingWhenTheUpstreamVariesOnAnUnknownField()
            throws Exception {
        assertEquals("*", get("/proxy/vary-cookie").headers().firstValue("Vary").orElse(null));
    }

    @Test
    public void forwardShouldRelayABareChallenge() throws Exception {
        assertEquals("Negotiate",
                get("/proxy/negotiate").headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    public void forwardShouldRelayRetryAfterOnServiceUnavailable() throws Exception {
        HttpResponse<String> response = get("/proxy/busy");

        assertEquals(503, response.statusCode());
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        ZonedDateTime when = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME);
        long seconds = when.toEpochSecond() - ZonedDateTime.now().toEpochSecond();
        assertTrue(seconds > 60 && seconds <= 121,
                "Retry-After should still ask for about 120 s, got " + retryAfter);
    }

    @Test
    public void forwardShouldRelayCacheControl() throws Exception {
        assertEquals("max-age=60",
                get("/proxy/created").headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    public void forwardShouldRelayVary() throws Exception {
        assertEquals("Accept", get("/proxy/created").headers().firstValue("Vary").orElse(null));
    }

    @Test
    public void forwardShouldRelayTrustedExtensionHeader() throws Exception {
        assertEquals("r-1",
                get("/proxy/created").headers().firstValue("X-Request-Id").orElse(null));
    }

    @Test
    public void forwardShouldNotRelayUntrustedExtensionHeader() throws Exception {
        assertFalse(get("/proxy/created").headers().firstValue("X-Internal-Node").isPresent());
    }

    @Test
    public void forwardShouldNotRelaySetCookieByDefault() throws Exception {
        assertFalse(get("/proxy/created").headers().firstValue("Set-Cookie").isPresent());
    }

    @Test
    public void forwardShouldRelaySetCookieWhenTrusted() throws Exception {
        String cookie = get("/cookies/created").headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith("session=abc"), "got Set-Cookie: " + cookie);
    }

    @Test
    public void forwardShouldRelayWwwAuthenticateOnUnauthorized() throws Exception {
        HttpResponse<String> response = get("/proxy/locked");

        assertEquals(401, response.statusCode());
        assertEquals("Basic realm=\"up\"",
                response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    public void forwardShouldRelayAllowOnMethodNotAllowed() throws Exception {
        HttpResponse<String> response = get("/proxy/read-only");

        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    public void forwardShouldKeepEntityHeaders() throws Exception {
        HttpResponse<String> response = get("/proxy/created");

        assertEquals("\"v1\"", response.headers().firstValue("ETag").orElse(null));
        assertEquals("attachment; filename=\"my plan.pdf\"",
                response.headers().firstValue("Content-Disposition").orElse(null));
    }

    @Test
    public void capabilityShouldFailToLoadWhenAHopByHopHeaderIsTrusted() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      port: 0
                      namespace: "proxy"
                      resources:
                        - path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedResponseHeaders:
                              - Connection
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://localhost:9/v1"
                """.formatted(IKANOS);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new Capability(spec));
        assertTrue(e.getMessage().contains("Connection"), e.getMessage());
    }
}
