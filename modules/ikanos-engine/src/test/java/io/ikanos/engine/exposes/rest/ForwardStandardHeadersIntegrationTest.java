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

    private static final String TEMPLATED = "/tpl?token=abc&fmt=application/xml"
            + "&since=Tue,%2010%20Nov%202026%2008:00:00%20GMT";

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
                        items:
                          path: "/items"
                          operations:
                            update-item:
                              method: PUT
                              inputParameters:
                                If-Match:
                                  in: header
                                  value: "{{etag}}"
                        prefs:
                          path: "/prefs"
                          operations:
                            get-prefs:
                              method: GET
                              inputParameters:
                                Cookie:
                                  in: header
                                  value: "lang=en"
                    - namespace: plain
                      type: http
                      baseUri: "http://localhost:%d/v1"
                    - namespace: templated
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      inputParameters:
                        Authorization:
                          in: header
                          value: "Bearer {{token}}"
                        Accept:
                          in: header
                          value: "{{fmt}}"
                        If-Modified-Since:
                          in: header
                          value: "{{since}}"
                      resources:
                        tpl:
                          path: "/tpl"
                          operations:
                            get-tpl:
                              method: GET
                    - namespace: guarded
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      inputParameters:
                        If-Match:
                          in: header
                          value: "{{etag}}"
                    - namespace: keyed
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      authentication:
                        type: apikey
                        key: X-Api-Key
                        value: "service-key"
                    - namespace: secured
                      type: http
                      baseUri: "http://localhost:%d/v1"
                      inputParameters:
                        Authorization:
                          in: header
                          value: "Bearer adapter-input"
                      authentication:
                        type: bearer
                        token: "service-token"
                      resources:
                        account:
                          path: "/account"
                          operations:
                            get-account:
                              method: GET
                        account-op:
                          path: "/account-op"
                          operations:
                            get-account-op:
                              method: GET
                              inputParameters:
                                Authorization:
                                  in: header
                                  value: "Bearer operation-input"
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
                        items:
                          path: "/items"
                          operations:
                            put:
                              method: PUT
                              inputParameters:
                                etag:
                                  in: query
                                  type: string
                                  description: "Entity tag the update is conditional on"
                              call: up.update-item
                        prefs:
                          path: "/prefs"
                          operations:
                            get:
                              method: GET
                              call: up.get-prefs
                        account:
                          path: "/account"
                          operations:
                            get:
                              method: GET
                              call: secured.get-account
                        account-op:
                          path: "/account-op"
                          operations:
                            get:
                              method: GET
                              call: secured.get-account-op
                        tpl:
                          path: "/tpl"
                          operations:
                            get:
                              method: GET
                              inputParameters:
                                token:
                                  in: query
                                  type: string
                                  description: "Token sent in the adapter Authorization header"
                                fmt:
                                  in: query
                                  type: string
                                  description: "Media type sent in the adapter Accept header"
                                since:
                                  in: query
                                  type: string
                                  description: "Date sent in the adapter If-Modified-Since"
                              call: templated.get-tpl
                        tplfwd:
                          path: "/tplfwd/{{path}}"
                          forward:
                            targetNamespace: templated
                        guarded:
                          path: "/guarded/{{path}}"
                          forward:
                            targetNamespace: guarded
                        guarded-trusted:
                          path: "/guarded-trusted/{{path}}"
                          forward:
                            targetNamespace: guarded
                            trustedHeaders:
                              - If-Match
                        keyed:
                          path: "/keyed/{{path}}"
                          forward:
                            targetNamespace: keyed
                            trustedHeaders:
                              - Authorization
                        secure:
                          path: "/secure/{{path}}"
                          forward:
                            targetNamespace: secured
                            trustedHeaders:
                              - Authorization
                        cookies:
                          path: "/cookies/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedHeaders:
                              - Cookie
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
                              - If-Range
                              - Max-Forwards
                              - From
                              - X-Request-Id
                """.formatted(IKANOS, upstream.getAddress().getPort(),
                upstream.getAddress().getPort(), upstream.getAddress().getPort(),
                upstream.getAddress().getPort(), upstream.getAddress().getPort(),
                upstream.getAddress().getPort(), restPort),
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
    void forwardShouldAnswerBadRequestWhenPutHasUnquotedIfMatch() throws Exception {
        HttpResponse<String> response = put("/proxy/files/1", "If-Match", "v1");

        assertEquals(400, response.statusCode(), response.body());
        assertFalse(response.body().contains("v1"), "the error must not echo the value");
        assertTrue(received.isEmpty(),
                "a write whose If-Match cannot be parsed must not reach the upstream "
                        + "unconditionally: " + received);
    }

    @Test
    void forwardShouldAnswerBadRequestWhenPutHasInvalidIfUnmodifiedSince() throws Exception {
        HttpResponse<String> response = put("/proxy/files/1", "If-Unmodified-Since",
                "last tuesday");

        assertEquals(400, response.statusCode(), response.body());
        assertFalse(response.body().contains("last tuesday"), "the error must not echo the value");
        assertTrue(received.isEmpty(), received.toString());
    }

    @Test
    void forwardShouldSendGetWithoutConditionWhenIfNoneMatchCannotBeParsed() throws Exception {
        HttpResponse<String> response = get("/proxy/files/1", "If-None-Match", "v1");

        assertEquals(200, response.statusCode(), response.body());
        assertNull(received.get("if-none-match"),
                "a safe request goes out without the invalid condition");
    }

    @Test
    void forwardShouldNotSendRangeWhenIfRangeCannotBeParsed() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/proxy/files/1"))
                .header("If-Range", "v1")
                .header("Range", "bytes=0-9")
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        assertNull(received.get("range"),
                "without its If-Range, the Range could return part of a changed representation");
    }

    @Test
    void declaredOperationShouldSendIfMatchInputWhenTagIsQuoted() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/items?etag=%22v1%22"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(ETAG, received.get("if-match"));
    }

    @Test
    void declaredOperationShouldAnswerBadRequestWhenPutIfMatchInputCannotBeParsed()
            throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/items?etag=v1"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(), response.body());
        assertFalse(response.body().contains("v1"), "the error must not echo the value");
        assertTrue(received.isEmpty(),
                "#1001: a declared write whose If-Match cannot be parsed must not reach the "
                        + "upstream unconditionally: " + received);
    }

    @Test
    void declaredOperationShouldAnswerBadRequestWhenPutIfMatchInputHasNoValue()
            throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/items"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(received.isEmpty(),
                "#1001: a declared write whose templated If-Match has no value must not reach "
                        + "the upstream unconditionally: " + received);
    }

    @Test
    void forwardShouldAnswerBadRequestWhenAdapterIfMatchTemplateHasNoValueOnAPut()
            throws Exception {
        HttpResponse<String> response = put("/guarded/items/1", "X-Unused", "x");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(received.isEmpty(),
                "#1001: a forwarded write whose adapter If-Match cannot be resolved must not "
                        + "reach the upstream unconditionally: " + received);
    }

    @Test
    void forwardShouldSendTheCallersTrustedIfMatchWhenTheAdapterIfMatchHasNoValue()
            throws Exception {
        HttpResponse<String> response = put("/guarded-trusted/items/1", "If-Match", ETAG);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(ETAG, received.get("if-match"),
                "#1001: the caller's own If-Match already guards the write, so the adapter's "
                        + "unresolved one must not refuse it");
    }

    @Test
    void forwardShouldLeaveOutAnUnresolvedAdapterIfMatchOnAGet() throws Exception {
        HttpResponse<String> response = get("/guarded/items/1", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertNull(received.get("if-match"));
    }

    @Test
    void forwardShouldNotSendATrustedAuthorizationBesideAnApiKeyInAnotherHeader()
            throws Exception {
        HttpResponse<String> response = get("/keyed/account", "Authorization",
                "Bearer caller-token");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("service-key", received.get("x-api-key"));
        assertNull(received.get("authorization"),
                "#1001: the declared authentication owns Authorization");
    }

    @Test
    void forwardShouldSendEveryLineWhenClientRepeatsCacheControl() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + "/proxy/files/1"))
                .header("Cache-Control", "no-cache")
                .header("Cache-Control", "max-age=0")
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        String sent = received.get("cache-control");
        assertTrue(sent != null && sent.contains("no-cache") && sent.contains("max-age=0"),
                "both Cache-Control lines must be forwarded: " + sent);
    }

    @Test
    void forwardShouldSendAuthenticationCredentialWhenAuthorizationIsAlsoTrusted()
            throws Exception {
        HttpResponse<String> response = get("/secure/account", "Authorization",
                "Bearer caller-token");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Bearer service-token", received.get("authorization"),
                "the target adapter's authentication wins over a trusted Authorization");
    }

    @Test
    void forwardShouldMergeCallerCookieWithApiKeyCookie() throws Exception {
        HttpResponse<String> response = get("/cookies/docs", "Cookie", "lang=fr");

        assertEquals(200, response.statusCode(), response.body());
        String sent = received.get("cookie");
        assertTrue(sent != null && sent.contains("session=abc") && sent.contains("lang=fr"),
                "the caller's cookie and the apikey cookie must both reach the upstream: "
                        + sent);
    }

    @Test
    void declaredOperationShouldSendAuthenticationWhenAdapterDeclaresAuthorizationInput()
            throws Exception {
        HttpResponse<String> response = get("/account", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Bearer service-token", received.get("authorization"),
                "authentication wins over an adapter-level Authorization header input");
    }

    @Test
    void declaredOperationShouldSendAuthenticationWhenOperationDeclaresAuthorizationInput()
            throws Exception {
        HttpResponse<String> response = get("/account-op", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Bearer service-token", received.get("authorization"),
                "authentication wins over an operation-level Authorization header input");
    }

    @Test
    void declaredOperationShouldMergeCookieInputWithApiKeyCookie() throws Exception {
        HttpResponse<String> response = get("/prefs", "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        String sent = received.get("cookie");
        assertTrue(sent != null && sent.contains("session=abc") && sent.contains("lang=en"),
                "a Cookie header input and an apikey cookie must both reach the upstream: "
                        + sent);
    }

    @Test
    void declaredOperationShouldSendResolvedAdapterAuthorizationTemplate() throws Exception {
        HttpResponse<String> response = get(TEMPLATED, "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Bearer abc", received.get("authorization"),
                "#1001: the resolved adapter-level template must reach the upstream, not be "
                        + "replaced by its raw text after authentication");
    }

    @Test
    void declaredOperationShouldSendResolvedAdapterAcceptTemplate() throws Exception {
        HttpResponse<String> response = get(TEMPLATED, "X-Unused", "x");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("application/xml", received.get("accept"));
    }

    @Test
    void declaredOperationShouldSendResolvedAdapterIfModifiedSinceTemplate() throws Exception {
        HttpResponse<String> response = get(TEMPLATED, "X-Unused", "x");

        assertEquals(200, response.statusCode(),
                "#1001: a templated adapter-level date must not fail every GET: "
                        + response.body());
        assertEquals("Tue, 10 Nov 2026 08:00:00 GMT", received.get("if-modified-since"));
    }

    @Test
    void forwardShouldNotFailWhenAdapterHeaderIsTemplated() throws Exception {
        HttpResponse<String> response = get("/tplfwd/docs", "X-Unused", "x");

        assertEquals(200, response.statusCode(),
                "#1001: a templated adapter-level header has no value in forward mode and must "
                        + "be left out, not sent as its raw text or fail the call: "
                        + response.body());
        assertNull(received.get("if-modified-since"));
        assertFalse(String.valueOf(received.get("authorization")).contains("{{"),
                String.valueOf(received.get("authorization")));
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

    private HttpResponse<String> put(String path, String header, String value) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + restPort + path))
                .header(header, value)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"plan\"}"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
