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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * End to end: a REST adapter with {@code authentication: oauth2} passes the caller's validated
 * claims to consumed APIs, through {@code {{auth.claims...}}} templates and
 * {@code forward.identityHeaders}, and never a value the client sent (#998).
 */
public class RestCallerIdentityIntegrationTest {

    private static RSAKey signingKey;
    private static HttpServer authorizationServer;
    private static String issuer;

    /** Headers of each request the upstream received, names case-insensitive. */
    private final List<Map<String, String>> upstreamRequests = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer upstream;
    private ServerAdapter adapter;
    private int port;

    @BeforeAll
    public static void startAuthorizationServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("caller-identity").generate();
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
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach(
                    (name, values) -> headers.put(name, String.join(" | ", values)));
            upstreamRequests.add(headers);
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
                      resources:
                        records:
                          path: "/records"
                          operations:
                            get-record:
                              method: "GET"
                              call: "domain.get-record"
                        summary:
                          path: "/summary"
                          operations:
                            get-summary:
                              method: "GET"
                              steps:
                                fetch:
                                  type: call
                                  call: "domain.get-record"
                        profile:
                          path: "/profile"
                          operations:
                            get-profile:
                              method: "GET"
                              with:
                                contact: "{{auth.claims.email}}"
                              call: "domain.get-record"
                        files:
                          path: "/files/{{path}}"
                          forward:
                            targetNamespace: "files"
                            trustedHeaders: ["X-User-Id", "Accept"]
                            identityHeaders:
                              X-User-Id: "sub"
                              X-User-Groups: "realm_access.groups"
                              X-User-Email:
                                claim: "email"
                                ifMissing: "omit"
                  consumes:
                    - type: "http"
                      namespace: "domain"
                      baseUri: "http://127.0.0.1:%d"
                      inputParameters:
                        X-User-Id:
                          in: "header"
                          value: "{{auth.claims.sub}}"
                      resources:
                        record:
                          path: "/records/42"
                          operations:
                            get-record:
                              method: "GET"
                              inputParameters:
                                X-User-Roles:
                                  in: "header"
                                  value: "{{auth.claims.roles}}"
                    - type: "http"
                      namespace: "files"
                      baseUri: "http://127.0.0.1:%d/files"
                """.formatted(VersionHelper.getSchemaVersion(), port, issuer, port,
                upstream.getAddress().getPort(), upstream.getAddress().getPort());
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
    public void operationShouldSendTheCallerClaimsToTheConsumedApi() throws Exception {
        HttpResponse<String> response = get("/records", token(new JWTClaimsSet.Builder()
                .subject("alice").claim("roles", List.of("editor", "admin"))));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, upstreamRequests.size());
        assertEquals("alice", upstreamRequests.get(0).get("X-User-Id"));
        assertEquals("editor,admin", upstreamRequests.get(0).get("X-User-Roles"));
    }

    @Test
    public void operationShouldRefuseTheCallBeforeTheUpstreamWhenAClaimIsMissing()
            throws Exception {
        HttpResponse<String> response = get("/records",
                token(new JWTClaimsSet.Builder().subject("alice")));

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("auth.claims.roles"), response.body());
        assertTrue(upstreamRequests.isEmpty(), "#998: no empty identity may reach the upstream");
    }

    @Test
    public void stepsShouldRefuseTheCallBeforeTheUpstreamWhenAClaimIsMissing()
            throws Exception {
        HttpResponse<String> response = get("/summary",
                token(new JWTClaimsSet.Builder().subject("alice")));

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(upstreamRequests.isEmpty(), "#998: no empty identity may reach the upstream");
    }

    @Test
    public void operationWithShouldRefuseTheCallWhenTheClaimItReadsIsMissing() throws Exception {
        HttpResponse<String> response = get("/profile", token(new JWTClaimsSet.Builder()
                .subject("alice").claim("roles", List.of("editor"))));

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("auth.claims.email"), response.body());
        assertTrue(upstreamRequests.isEmpty());
    }

    @Test
    public void forwardShouldSetIdentityHeadersAndDropTheClientCopy() throws Exception {
        HttpResponse<String> response = get("/files/report.pdf", token(new JWTClaimsSet.Builder()
                .subject("alice").claim("realm_access", Map.of("groups", List.of("staff")))),
                "x-user-id", "mallory");

        assertEquals(200, response.statusCode(), response.body());
        Map<String, String> headers = upstreamRequests.get(0);
        assertEquals("alice", headers.get("X-User-Id"),
                "#998: the engine's value replaces the client's, even for a trusted header");
        assertEquals("staff", headers.get("X-User-Groups"));
        assertFalse(headers.containsKey("X-User-Email"), "an omitted header is not sent");
    }

    @Test
    public void forwardShouldRefuseTheCallWhenARequiredIdentityClaimIsMissing() throws Exception {
        HttpResponse<String> response = get("/files/report.pdf",
                token(new JWTClaimsSet.Builder().subject("alice")));

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("realm_access.groups"), response.body());
        assertTrue(upstreamRequests.isEmpty(), "#998: no empty identity may reach the upstream");
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

    private HttpResponse<String> get(String path, String token, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
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
