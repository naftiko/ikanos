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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;

/**
 * Unit tests for {@link McpOAuth2Restlet} — MCP-specific OAuth 2.1 behavior including
 * Protected Resource Metadata and {@code resource_metadata} in WWW-Authenticate.
 */
class McpOAuth2RestletTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static RSAKey rsaJWK;
    private static RSAKey rsaPublicJWK;
    private static JWKSet jwkSet;

    @BeforeAll
    static void generateKeys() throws Exception {
        rsaJWK = new RSAKeyGenerator(2048).keyID("mcp-key-1").generate();
        rsaPublicJWK = rsaJWK.toPublicJWK();
        jwkSet = new JWKSet(rsaPublicJWK);
    }

    @Test
    void handleShouldServeProtectedResourceMetadataOnGetWellKnown() throws Exception {
        OAuth2AuthenticationSpec spec = specWithScopes();
        spec.setResource("https://mcp.example.com/");
        McpOAuth2Restlet restlet = buildRestlet(spec);

        Request request = new Request(Method.GET,
                "http://localhost/.well-known/oauth-protected-resource");
        request.setResourceRef(
                new Reference("http://localhost/.well-known/oauth-protected-resource"));
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertNotNull(response.getEntity());

        String body = response.getEntity().getText();
        JsonNode metadata = JSON.readTree(body);

        assertEquals("https://mcp.example.com/", metadata.get("resource").asText());
        assertEquals("https://auth.example.com",
                metadata.get("authorization_servers").get(0).asText());
        assertTrue(metadata.has("scopes_supported"));
        assertEquals(2, metadata.get("scopes_supported").size());
        assertEquals("header", metadata.get("bearer_methods_supported").get(0).asText());
    }

    @Test
    void handleShouldServeMetadataAtPathDerivedFromResource() {
        OAuth2AuthenticationSpec spec = new OAuth2AuthenticationSpec();
        spec.setAuthorizationServerUri("https://auth.example.com");
        spec.setResource("https://mcp.example.com/api/v1");

        McpOAuth2Restlet restlet = new McpOAuth2Restlet(spec, new NoOpRestlet(), jwkSet);

        assertEquals("/.well-known/oauth-protected-resource/api/v1",
                restlet.getMetadataPath());
    }

    @Test
    void handleShouldServeMetadataAtRootPathForRootResource() {
        OAuth2AuthenticationSpec spec = new OAuth2AuthenticationSpec();
        spec.setAuthorizationServerUri("https://auth.example.com");
        spec.setResource("https://mcp.example.com/");

        McpOAuth2Restlet restlet = new McpOAuth2Restlet(spec, new NoOpRestlet(), jwkSet);

        assertEquals("/.well-known/oauth-protected-resource",
                restlet.getMetadataPath());
    }

    @Test
    void unauthorizedShouldIncludeResourceMetadataInWwwAuthenticate() {
        McpOAuth2Restlet restlet = buildRestlet(specWithScopes());

        Request request = new Request(Method.POST, "http://localhost/mcp");
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        assertFalse(response.getChallengeRequests().isEmpty());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertNotNull(rawValue);
        assertTrue(rawValue.contains("resource_metadata="),
                "WWW-Authenticate should contain resource_metadata");
        assertTrue(rawValue.contains("/.well-known/oauth-protected-resource"),
                "resource_metadata should point to well-known path");
    }

    @Test
    void forbiddenShouldIncludeResourceMetadataInWwwAuthenticate() throws Exception {
        OAuth2AuthenticationSpec spec = specWithScopes();
        McpOAuth2Restlet restlet = buildRestlet(spec);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .claim("scope", "tools:read")
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertTrue(rawValue.contains("resource_metadata="));
        assertTrue(rawValue.contains("insufficient_scope"));
    }

    @Test
    void handleShouldDelegateToParentForValidJwt() throws Exception {
        TrackingRestlet tracker = new TrackingRestlet();
        McpOAuth2Restlet restlet = new McpOAuth2Restlet(specWithScopes(), tracker, jwkSet);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .claim("scope", "tools:read tools:execute")
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertTrue(tracker.wasCalled(), "Valid JWT should pass through to next restlet");
    }

    @Test
    void handleShouldNotServeMetadataForNonGetRequests() {
        McpOAuth2Restlet restlet = buildRestlet(specWithScopes());

        Request request = new Request(Method.POST,
                "http://localhost/.well-known/oauth-protected-resource");
        request.setResourceRef(
                new Reference("http://localhost/.well-known/oauth-protected-resource"));
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus(),
                "POST to metadata path without token should be rejected, not serve metadata");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static OAuth2AuthenticationSpec specWithScopes() {
        OAuth2AuthenticationSpec spec = new OAuth2AuthenticationSpec();
        spec.setAuthorizationServerUri("https://auth.example.com");
        spec.setResource("https://mcp.example.com/mcp");
        spec.setScopes(List.of("tools:read", "tools:execute"));
        return spec;
    }

    private McpOAuth2Restlet buildRestlet(OAuth2AuthenticationSpec spec) {
        return new McpOAuth2Restlet(spec, new NoOpRestlet(), jwkSet);
    }

    private static String signedJwt(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJWK.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJWK));
        return jwt.serialize();
    }

    private static Request bearerRequest(String token) {
        Request request = new Request(Method.POST, "http://localhost/mcp");
        request.getHeaders().set("Authorization", "Bearer " + token);
        return request;
    }

    private static Date futureDate() {
        return new Date(System.currentTimeMillis() + 300_000);
    }

    private static class NoOpRestlet extends Restlet {

        @Override
        public void handle(Request request, Response response) {
            response.setStatus(Status.SUCCESS_OK);
        }
    }

    private static class TrackingRestlet extends Restlet {

        private boolean called;

        @Override
        public void handle(Request request, Response response) {
            called = true;
            response.setStatus(Status.SUCCESS_OK);
        }

        boolean wasCalled() {
            return called;
        }
    }

}
