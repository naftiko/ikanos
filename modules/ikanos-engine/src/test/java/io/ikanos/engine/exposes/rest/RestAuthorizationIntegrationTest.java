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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * End to end: a REST adapter with {@code authentication: oauth2} requires scopes and claims per
 * resource and operation, forwarded calls included, and refuses a token that falls short with
 * {@code 403 insufficient_scope} before any upstream call (#999).
 */
public class RestAuthorizationIntegrationTest {

    private static RSAKey signingKey;
    private static HttpServer authorizationServer;
    private static String issuer;

    private final List<String> upstreamCalls = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer upstream;
    private ServerAdapter adapter;
    private int port;

    @BeforeAll
    public static void startAuthorizationServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("rest-authorization").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        authorizationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + authorizationServer.getAddress().getPort();
        authorizationServer.createContext("/.well-known/oauth-authorization-server",
                exchange -> respond(exchange, "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\""
                        + issuer + "/jwks\"}"));
        authorizationServer.createContext("/jwks", exchange -> respond(exchange, jwks));
        authorizationServer.start();
    }

    @AfterAll
    public static void stopAuthorizationServer() {
        authorizationServer.stop(0);
    }

    @BeforeEach
    public void startCapability() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            upstreamCalls.add(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, "{\"ok\":true}");
        });
        upstream.start();

        port = freePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "127.0.0.1"
                      port: %d
                      namespace: "records-api"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "%s"
                        resource: "http://127.0.0.1:%d/api"
                        scopes: ["records.read", "records.write"]
                      resources:
                        records:
                          path: "/records"
                          authorization:
                            scopes: ["records.read"]
                          operations:
                            get-record:
                              method: "GET"
                              call: "up.get-record"
                            update-record:
                              method: "PUT"
                              authorization:
                                scopes: ["records.write"]
                                claims:
                                  roles: ["editor", "admin"]
                              call: "up.update-record"
                        files:
                          path: "/files/{{path}}"
                          authorization:
                            claims:
                              realm_access.groups: ["staff"]
                          forward:
                            targetNamespace: "up"
                        health:
                          path: "/health"
                          operations:
                            ping:
                              method: "GET"
                              outputParameters:
                                - type: "string"
                                  value: "ok"
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://127.0.0.1:%d"
                      resources:
                        record:
                          path: "/records/42"
                          operations:
                            get-record:
                              method: "GET"
                            update-record:
                              method: "PUT"
                """.formatted(VersionHelper.getSchemaVersion(), port, issuer, port,
                upstream.getAddress().getPort());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        adapter = new Capability(spec).getServerAdapters().get(0);
        adapter.start();
    }

    @AfterEach
    public void stopCapability() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
        upstream.stop(0);
    }

    @Test
    public void operationShouldRunWhenTokenCarriesItsResourceScope() throws Exception {
        HttpResponse<String> response = send("GET", "/records",
                token(new JWTClaimsSet.Builder().claim("scope", "records.read")));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("GET /records/42"), upstreamCalls);
    }

    @Test
    public void operationShouldBeRefusedBeforeUpstreamWhenTokenLacksItsResourceScope()
            throws Exception {
        HttpResponse<String> response = send("GET", "/records",
                token(new JWTClaimsSet.Builder().claim("scope", "records.write")));

        assertEquals(403, response.statusCode(), response.body());
        String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
        assertTrue(challenge.startsWith("Bearer"), challenge);
        assertTrue(challenge.contains("error=\"insufficient_scope\""), challenge);
        assertTrue(challenge.contains("scope=\"records.read\""), challenge);
        assertTrue(upstreamCalls.isEmpty(), "A refused call must not reach the upstream");
    }

    @Test
    public void operationAuthorizationShouldReplaceItsResourceAuthorization() throws Exception {
        HttpResponse<String> response = send("PUT", "/records", token(new JWTClaimsSet.Builder()
                .claim("scope", "records.write").claim("roles", List.of("editor"))));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("PUT /records/42"), upstreamCalls);
    }

    @Test
    public void operationShouldBeRefusedWhenTokenHoldsNoAllowedRole() throws Exception {
        HttpResponse<String> response = send("PUT", "/records", token(new JWTClaimsSet.Builder()
                .claim("scope", "records.read records.write").claim("roles", List.of("viewer"))));

        assertEquals(403, response.statusCode(), response.body());
        String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
        assertTrue(challenge.contains("insufficient_scope"), challenge);
        assertFalse(challenge.contains("editor"), "The allowed roles must not be disclosed");
        assertTrue(upstreamCalls.isEmpty(), "A refused call must not reach the upstream");
    }

    @Test
    public void forwardedCallShouldBeRefusedWhenTokenLacksTheResourceClaim() throws Exception {
        HttpResponse<String> response = send("GET", "/files/report.pdf", token(
                new JWTClaimsSet.Builder().claim("realm_access",
                        java.util.Map.of("groups", List.of("guests")))));

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(upstreamCalls.isEmpty(), "A refused call must not reach the upstream");
    }

    @Test
    public void forwardedCallShouldPassWhenTokenHoldsTheResourceClaim() throws Exception {
        HttpResponse<String> response = send("GET", "/files/report.pdf", token(
                new JWTClaimsSet.Builder().claim("realm_access",
                        java.util.Map.of("groups", List.of("staff")))));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("GET /report.pdf"), upstreamCalls);
    }

    @Test
    public void resourceWithoutAuthorizationShouldAcceptAnyValidToken() throws Exception {
        HttpResponse<String> response = send("GET", "/health",
                token(new JWTClaimsSet.Builder().subject("alice")));

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    public void requestWithoutTokenShouldStillBeUnauthorized() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + "/records")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    private String token(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.issuer(issuer)
                        .audience("http://127.0.0.1:" + port + "/api")
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, "PUT".equals(method)
                        ? HttpRequest.BodyPublishers.ofString("{}")
                        : HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void respond(HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
