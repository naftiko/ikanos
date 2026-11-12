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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * End to end, on the wire: the standard HTTP headers a capability sends upstream reach it (#1001).
 * Restlet 3 refuses a standard header added as a raw header and only logs a warning, so these
 * headers used to be dropped silently, in forward mode ({@code trustedHeaders}), for consumed
 * {@code in: header} inputs and for an {@code apikey} sent in a standard header.
 */
class ForwardStandardHeadersIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String ETAG = "\"v1\"";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, String> received = new ConcurrentHashMap<>();
    private HttpServer upstream;
    private Capability capability;
    private int restPort;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) -> received
                    .put(name.toLowerCase(), String.join(",", values)));
            if (ETAG.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.getResponseHeaders().set("ETag", ETAG);
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("ETag", ETAG);
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
                      inputParameters:
                        User-Agent:
                          in: header
                          value: "ikanos-adapter/1.0"
                      authentication:
                        type: apikey
                        key: Cookie
                        value: "session=abc"
                      resources:
                        docs:
                          path: "/docs"
                          operations:
                            list:
                              method: GET
                              inputParameters:
                                Accept:
                                  in: header
                                  value: "application/xml"
                    - namespace: plain
                      type: http
                      baseUri: "http://localhost:%d/v1"
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: %d
                      namespace: demo
                      resources:
                        docs:
                          path: "/docs"
                          operations:
                            list:
                              method: GET
                              call: up.list
                        proxy:
                          path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: plain
                            trustedHeaders:
                              - If-None-Match
                              - If-Match
                              - If-Modified-Since
                              - If-Unmodified-Since
                              - Accept
                              - Accept-Language
                              - Range
                              - Cache-Control
                              - User-Agent
                              - Referer
                              - Cookie
                              - Authorization
                              - If-Range
                              - Max-Forwards
                              - From
                              - X-Request-Id
                """.formatted(IKANOS, upstream.getAddress().getPort(),
                upstream.getAddress().getPort(), restPort), IkanosSpec.class));
        capability.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        upstream.stop(0);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "If-None-Match       | \"v2\"",
        "If-Match            | \"v1\"",
        "If-Modified-Since   | Tue, 10 Nov 2026 08:00:00 GMT",
        "If-Unmodified-Since | Tue, 10 Nov 2026 08:00:00 GMT",
        "Accept              | application/json",
        "Accept-Language     | fr-CA",
        "Range               | bytes=0-99",
        "Cache-Control       | no-cache",
        "User-Agent          | browser/2.0",
        "Referer             | http://app.example.org/page",
        "Cookie              | a=1",
        "Authorization       | Bearer abc",
        "If-Range            | \"v1\"",
        "Max-Forwards        | 3",
        "From                | ops@example.org",
        "X-Request-Id        | req-42"})
    void forwardShouldSendTrustedHeaderUpstreamWhenClientSendsIt(String name, String value)
            throws Exception {
        HttpResponse<String> response = get("/proxy/files/1", name, value);

        // Not always 200: Restlet's range service may answer a forwarded Range with 206
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        assertEquals(value, received.get(name.toLowerCase()),
                "#1001: " + name + " is listed in trustedHeaders, so forward mode must send it "
                        + "upstream; Restlet 3 drops a standard header added as a raw header");
    }

    @Test
    void forwardShouldReturnNotModifiedWhenUpstreamMatchesIfNoneMatch() throws Exception {
        HttpResponse<String> response = get("/proxy/files/1", "If-None-Match", ETAG);

        assertEquals(304, response.statusCode(),
                "#1001: the upstream answers 304 to a matching If-None-Match, so the caller must "
                        + "get 304 back, not the full 200 the upstream sends without the header");
    }

    @Test
    void forwardShouldNotSendStandardHeaderThatIsNotTrusted() throws Exception {
        HttpResponse<String> response = get("/proxy/files/1", "Accept-Charset", "utf-8");

        assertEquals(200, response.statusCode(), response.body());
        assertNull(received.get("accept-charset"),
                "Accept-Charset is not in trustedHeaders, so it must not reach the upstream");
    }

    @Test
    void declaredOperationShouldSendAcceptHeaderInputUpstream() throws Exception {
        HttpResponse<String> response = get("/docs", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("application/xml", received.get("accept"),
                "#1001: an 'in: header' input named Accept must reach the upstream");
    }

    @Test
    void declaredOperationShouldSendUserAgentAdapterHeaderUpstream() throws Exception {
        HttpResponse<String> response = get("/docs", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("ikanos-adapter/1.0", received.get("user-agent"),
                "#1001: an adapter-level 'in: header' input named User-Agent must reach the "
                        + "upstream");
    }

    @Test
    void declaredOperationShouldSendApiKeyPlacedInCookieHeaderUpstream() throws Exception {
        HttpResponse<String> response = get("/docs", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("session=abc", received.get("cookie"),
                "#1001: an apikey whose key is the standard Cookie header must reach the upstream");
    }

    private HttpResponse<String> get(String path, String header, String value) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + path))
                .header(header, value)
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
