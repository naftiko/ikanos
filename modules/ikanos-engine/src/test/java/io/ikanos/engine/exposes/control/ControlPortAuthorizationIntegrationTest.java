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
package io.ikanos.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
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
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;

/**
 * End to end: a control port with {@code authentication: oauth2} has no {@code authorization}
 * block, so its management endpoints keep requiring every scope listed in
 * {@code authentication.scopes} (#999). A valid token without them gets {@code 403
 * insufficient_scope}; one that carries them all reaches the endpoint.
 */
class ControlPortAuthorizationIntegrationTest {

    private static RSAKey signingKey;
    private static HttpServer authorizationServer;
    private static String issuer;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ServerAdapter adapter;
    private int port;

    @BeforeAll
    static void startAuthorizationServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("control-authorization").generate();
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
    static void stopAuthorizationServer() {
        authorizationServer.stop(0);
    }

    @BeforeEach
    void startControlPort() throws Exception {
        TelemetryBootstrap.reset();
        port = TestUtils.freePorts(1)[0];
        String yaml = """
                ikanos: "%s"
                info:
                  title: "Control Port Authorization Test"
                capability:
                  exposes:
                    - type: "control"
                      address: "127.0.0.1"
                      port: %d
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "%s"
                        resource: "http://127.0.0.1:%d"
                        scopes: ["control.read", "control.admin"]
                      management:
                        health: true
                        info: true
                """.formatted(VersionHelper.getSchemaVersion(), port, issuer, port);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        adapter = new Capability(spec).getServerAdapters().get(0);
        adapter.start();
    }

    @AfterEach
    void stopControlPort() throws Exception {
        try {
            if (adapter != null) {
                adapter.stop();
            }
        } finally {
            TelemetryBootstrap.reset();
        }
    }

    @Test
    void managementEndpointShouldRefuseTokenWithoutEveryListedScope() throws Exception {
        HttpResponse<String> response = get("/status",
                token(new JWTClaimsSet.Builder().claim("scope", "control.read")));

        assertEquals(403, response.statusCode(), response.body());
        String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
        assertTrue(challenge.startsWith("Bearer"), challenge);
        assertTrue(challenge.contains("error=\"insufficient_scope\""), challenge);
        assertTrue(challenge.contains("scope=\"control.read control.admin\""), challenge);
    }

    @Test
    void managementEndpointShouldRefuseTokenWithoutAnyScope() throws Exception {
        HttpResponse<String> response = get("/health/live",
                token(new JWTClaimsSet.Builder().subject("operator")));

        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void managementEndpointShouldAnswerTokenCarryingEveryListedScope() throws Exception {
        HttpResponse<String> response = get("/status",
                token(new JWTClaimsSet.Builder().claim("scope", "control.admin control.read")));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("Control Port Authorization Test"), response.body());
    }

    @Test
    void requestWithoutTokenShouldStillBeUnauthorized() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    private String token(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.issuer(issuer)
                        .audience("http://127.0.0.1:" + port)
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
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
}
