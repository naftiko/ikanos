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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static volatile String upstreamDate;

    private static HttpServer upstream;
    private static final AtomicInteger redirectTargetHits = new AtomicInteger();
    private static final List<String> otherHostKeys = new CopyOnWriteArrayList<>();
    private static HttpServer otherHost;
    private static List<ServerAdapter> adapters;
    private static int upstreamPort;
    private static int exposedPort;
    private static int securedExposedPort;
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
        respond("/v1/moved", 302, Map.of("Location",
                List.of("http://localhost:" + upstreamPort + "/v1/moved-target")));
        respond("/v1/moved-outside", 307, Map.of("Location",
                List.of("http://localhost:" + upstreamPort + "/internal/secret")));
        otherHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        otherHost.createContext("/", exchange -> {
            otherHostKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-API-Key")));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        otherHost.start();
        respond("/v1/cross-host", 302, Map.of("Location",
                List.of("http://127.0.0.1:" + otherHost.getAddress().getPort() + "/landing")));
        upstream.createContext("/v1/moved-target", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        upstream.createContext("/internal", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        upstream.createContext("/v1/dated", exchange -> {
            // The JDK server writes its own Date when the headers are sent: the body comes
            // later, so a Date the gateway wrote itself would be at least a second later
            exchange.getResponseHeaders().put("Expires", List.of("Mon, 02 Jan 2023 10:01:00 GMT"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            upstreamDate = exchange.getResponseHeaders().getFirst("Date");
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.createContext("/v1/not-modified", exchange -> {
            ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
            exchange.getResponseHeaders().put("ETag", List.of("\"v2\""));
            exchange.getResponseHeaders().put("Date",
                    List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(now)));
            exchange.getResponseHeaders().put("Cache-Control", List.of("must-revalidate"));
            exchange.getResponseHeaders().put("Expires",
                    List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(120))));
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        upstream.createContext("/v1/not-modified-expires-only", exchange -> {
            ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
            exchange.getResponseHeaders().put("ETag", List.of("\"v2\""));
            exchange.getResponseHeaders().put("Date",
                    List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(now)));
            exchange.getResponseHeaders().put("Expires",
                    List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(120))));
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        // Echoes the URI it was called with, as an upstream that builds its links from the
        // request does: with a query-placed apikey, that URI carries the adapter's credential
        upstream.createContext("/v1/echo", exchange -> {
            String self = "http://localhost:" + upstreamPort + exchange.getRequestURI();
            exchange.getResponseHeaders().put("Location", List.of(self));
            exchange.getResponseHeaders().put("Content-Location", List.of(self));
            exchange.getResponseHeaders().put("Link", List.of("<" + self + ">; rel=\"self\""));
            exchange.getResponseHeaders().put("Refresh", List.of("30; url=" + self));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        respond("/v1/shared", 200, Map.of("Cache-Control", List.of("public, max-age=60")));
        upstream.start();

        exposedPort = freePort();
        securedExposedPort = freePort();
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
                        - path: "/secured/{{path}}"
                          forward:
                            targetNamespace: secured
                        - path: "/keyed/{{path}}"
                          forward:
                            targetNamespace: keyed
                        - path: "/queried/{{path}}"
                          forward:
                            targetNamespace: queried
                            trustedResponseHeaders:
                              - Link
                              - Refresh
                        - path: "/linked/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedResponseHeaders:
                              - Link
                              - Refresh
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "private-proxy"
                      authentication:
                        type: apikey
                        key: X-Api-Key
                        value: "caller-key"
                      resources:
                        - path: "/private/{{path}}"
                          forward:
                            targetNamespace: up
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://localhost:%d/v1"
                    - type: "http"
                      namespace: "secured"
                      baseUri: "http://localhost:%d/v1"
                      authentication:
                        type: bearer
                        token: "expired-token"
                    - type: "http"
                      namespace: "keyed"
                      baseUri: "http://localhost:%d/v1"
                      inputParameters:
                        X-API-Key:
                          in: header
                          value: "adapter-secret"
                    - type: "http"
                      namespace: "queried"
                      baseUri: "http://localhost:%d/v1"
                      authentication:
                        type: apikey
                        key: api_key
                        placement: query
                        value: "query-secret"
                """.formatted(IKANOS, exposedPort, securedExposedPort, upstreamPort,
                upstreamPort, upstreamPort, upstreamPort);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        adapters = new Capability(spec).getServerAdapters();
        for (ServerAdapter adapter : adapters) {
            adapter.start();
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (adapters != null) {
            for (ServerAdapter adapter : adapters) {
                adapter.stop();
            }
        }
        if (upstream != null) {
            upstream.stop(0);
        }
        if (otherHost != null) {
            otherHost.stop(0);
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
    public void forwardShouldRelayARedirectInsteadOfFollowingIt() throws Exception {
        HttpResponse<String> moved = get("/proxy/moved");
        HttpResponse<String> outside = get("/proxy/moved-outside");

        assertEquals(302, moved.statusCode(), moved.body());
        assertEquals("/proxy/moved-target", moved.headers().firstValue("Location").orElse(null));
        assertEquals(307, outside.statusCode(), outside.body());
        assertFalse(outside.headers().firstValue("Location").isPresent(),
                "the upstream host must not reach the caller");
        assertEquals(0, redirectTargetHits.get(),
                "#1002: the gateway must not follow the upstream redirect itself");
    }

    @Test
    public void forwardShouldRelayARedirectToAnotherHostWithoutSendingTheAdaptersKeyThere()
            throws Exception {
        HttpResponse<String> response = get("/keyed/cross-host");

        assertEquals(302, response.statusCode(), response.body());
        assertEquals("http://127.0.0.1:" + otherHost.getAddress().getPort() + "/landing",
                response.headers().firstValue("Location").orElse(null),
                "a location on another host is relayed as is");
        assertTrue(otherHostKeys.isEmpty(),
                "#1002: the gateway must not follow the redirect, which sent the adapter's "
                        + "X-API-Key to the other host: " + otherHostKeys);
    }

    @Test
    public void forwardShouldDropARelativeLocationOutsideTheBaseUri() throws Exception {
        assertFalse(get("/proxy/relative-outside").headers().firstValue("Location").isPresent(),
                "a relative location outside the base points into the gateway's namespace");
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
    public void forwardShouldAnswerBadGatewayWhenTheUpstreamRefusesTheAdaptersOwnCredential()
            throws Exception {
        HttpResponse<String> response = get("/secured/locked");

        assertEquals(502, response.statusCode());
        assertFalse(response.headers().firstValue("WWW-Authenticate").isPresent(),
                "the upstream's challenge on Ikanos' own credential must not reach the caller");
        assertTrue(response.body().startsWith("An upstream call failed (reference: "),
                "got body: " + response.body());
    }

    @Test
    public void forwardShouldRelayOtherAnswersWhenTheAdapterHasItsOwnCredential()
            throws Exception {
        HttpResponse<String> response = get("/secured/created");

        assertEquals(201, response.statusCode());
        assertEquals("/secured/items/42", response.headers().firstValue("Location").orElse(null));
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
    public void forwardShouldRelayTheUpstreamDateThatItsExpiresIsRelativeTo() throws Exception {
        HttpResponse<String> response = get("/proxy/dated");

        assertNotNull(upstreamDate);
        assertEquals(upstreamDate, response.headers().firstValue("Date").orElse(null));
        assertEquals("Mon, 02 Jan 2023 10:01:00 GMT",
                response.headers().firstValue("Expires").orElse(null));
    }

    @Test
    public void forwardShouldRelayTheExpiresOfANotModifiedAsMaxAge() throws Exception {
        HttpResponse<String> response = get("/proxy/not-modified");

        assertEquals(304, response.statusCode());
        assertEquals("\"v2\"", response.headers().firstValue("ETag").orElse(null));
        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        assertTrue(cacheControl.matches("must-revalidate, ?max-age=(1[01][0-9]|120)"),
                cacheControl);
    }

    @Test
    public void forwardShouldNotAddCacheControlToANotModifiedWithOnlyExpires() throws Exception {
        HttpResponse<String> response = get("/proxy/not-modified-expires-only");

        assertEquals(304, response.statusCode());
        assertEquals(null, response.headers().firstValue("Cache-Control").orElse(null),
                "the cache would replace a stored no-cache with a synthesized max-age");
    }

    // --- The adapter's query credential, read from the request actually sent upstream ---

    @Test
    public void forwardShouldRewriteAnEchoedLocationWhenTheAdapterAddsNoQueryParameter()
            throws Exception {
        HttpResponse<String> response = get("/linked/echo?page=2");

        assertEquals(201, response.statusCode());
        assertEquals("/linked/echo?page=2",
                response.headers().firstValue("Location").orElse(null),
                "control case: the same echo is relayed when nothing was added to the query");
    }

    @Test
    public void forwardShouldDropAnEchoedLocationThatCarriesTheAdaptersQueryCredential()
            throws Exception {
        HttpResponse<String> response = get("/queried/echo?page=2");

        assertEquals(201, response.statusCode());
        assertFalse(response.headers().firstValue("Location").isPresent(),
                "#1002: the api_key the engine appended must not reach the caller");
        assertFalse(response.headers().firstValue("Content-Location").isPresent(),
                "#1002: the api_key the engine appended must not reach the caller");
    }

    @Test
    public void forwardShouldDropAnEchoedLocationWhenTheCallerSendsTheCredentialsName()
            throws Exception {
        HttpResponse<String> response = get("/queried/echo?api_key=ignored");

        assertEquals(201, response.statusCode());
        assertFalse(response.headers().firstValue("Location").isPresent(),
                "#1002: a caller's ?api_key=ignored must not unprotect the adapter's key");
        assertFalse(response.headers().firstValue("Content-Location").isPresent(),
                "#1002: a caller's ?api_key=ignored must not unprotect the adapter's key");
    }

    @Test
    public void forwardShouldDropListedLinkAndRefreshThatCarryTheAdaptersQueryCredential()
            throws Exception {
        HttpResponse<String> response = get("/queried/echo?page=2");

        assertFalse(response.headers().firstValue("Link").isPresent(),
                "#1002: a listed Link must not hand the caller the adapter's api_key");
        assertFalse(response.headers().firstValue("Refresh").isPresent(),
                "#1002: a listed Refresh must not hand the caller the adapter's api_key");
    }

    @Test
    public void forwardShouldRelayListedLinkAndRefreshWhenTheAdapterAddsNoQueryParameter()
            throws Exception {
        HttpResponse<String> response = get("/linked/echo?page=2");
        String self = "http://localhost:" + upstreamPort + "/v1/echo?page=2";

        assertEquals("<" + self + ">; rel=\"self\"",
                response.headers().firstValue("Link").orElse(null));
        assertEquals("30; url=" + self, response.headers().firstValue("Refresh").orElse(null));
    }

    // --- An exposed adapter that authenticates its callers ---

    @Test
    public void forwardShouldMarkTheAnswerPrivateWhenTheExposedAdapterAuthenticates()
            throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + securedExposedPort
                        + "/private/shared"))
                .header("X-Api-Key", "caller-key").GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        List<String> directives = List.of(
                response.headers().firstValue("Cache-Control").orElse("").split(",\\s*"));
        assertTrue(directives.contains("private"), "got Cache-Control: " + directives);
        assertTrue(directives.contains("max-age=60"), "got Cache-Control: " + directives);
        assertFalse(directives.contains("public"),
                "#1002: a shared cache must not serve an authenticated answer to anyone: "
                        + directives);
    }

    @Test
    public void forwardShouldRelayPublicCacheControlWhenTheExposedAdapterDoesNotAuthenticate()
            throws Exception {
        assertEquals("public, max-age=60",
                get("/linked/shared").headers().firstValue("Cache-Control").orElse(null),
                "control case: the same answer stays public without exposed authentication");
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
