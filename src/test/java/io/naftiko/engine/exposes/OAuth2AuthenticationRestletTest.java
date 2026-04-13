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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.naftiko.spec.consumes.OAuth2AuthenticationSpec;

/**
 * Unit tests for the shared {@link OAuth2AuthenticationRestlet}. Validates JWT signature
 * verification, claims validation, and WWW-Authenticate header generation.
 */
class OAuth2AuthenticationRestletTest {

    private static RSAKey rsaJWK;
    private static RSAKey rsaPublicJWK;
    private static JWKSet jwkSet;

    @BeforeAll
    static void generateKeys() throws Exception {
        rsaJWK = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
        rsaPublicJWK = rsaJWK.toPublicJWK();
        jwkSet = new JWKSet(rsaPublicJWK);
    }

    @Test
    void handleShouldRejectRequestWithoutBearerToken() {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        Request request = new Request(Method.POST, "http://localhost/mcp");
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        assertFalse(response.getChallengeRequests().isEmpty(),
                "Should include a Bearer challenge");
        assertEquals("Bearer",
                response.getChallengeRequests().get(0).getScheme().getTechnicalName());
    }

    @Test
    void handleShouldAcceptValidJwt() throws Exception {
        TrackingRestlet tracker = new TrackingRestlet();
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec(), tracker);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertTrue(tracker.wasCalled(), "Valid JWT should delegate to next restlet");
    }

    @Test
    void handleShouldRejectExpiredJwt() throws Exception {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertTrue(rawValue.contains("invalid_token"));
        assertTrue(rawValue.contains("Token expired"));
    }

    @Test
    void handleShouldRejectInvalidIssuer() throws Exception {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://wrong-issuer.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertTrue(rawValue.contains("Invalid issuer"));
    }

    @Test
    void handleShouldRejectInvalidAudience() throws Exception {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://wrong-audience.example.com")
                .expirationTime(futureDate())
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertTrue(rawValue.contains("Invalid audience"));
    }

    @Test
    void handleShouldReturnForbiddenForInsufficientScope() throws Exception {
        OAuth2AuthenticationSpec spec = minimalSpec();
        spec.setScopes(List.of("tools:read", "tools:execute"));
        OAuth2AuthenticationRestlet restlet = buildRestlet(spec);

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
        assertTrue(rawValue.contains("insufficient_scope"));
        assertTrue(rawValue.contains("tools:execute"));
    }

    @Test
    void handleShouldAcceptJwtWithAllRequiredScopes() throws Exception {
        OAuth2AuthenticationSpec spec = minimalSpec();
        spec.setScopes(List.of("tools:read", "tools:execute"));
        TrackingRestlet tracker = new TrackingRestlet();
        OAuth2AuthenticationRestlet restlet = buildRestlet(spec, tracker);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .claim("scope", "tools:read tools:execute admin")
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertTrue(tracker.wasCalled(), "JWT with all required scopes should pass");
    }

    @Test
    void handleShouldRejectJwtWithInvalidSignature() throws Exception {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("other-key").build(),
                new JWTClaimsSet.Builder()
                        .issuer("https://auth.example.com")
                        .audience("https://mcp.example.com/mcp")
                        .expirationTime(futureDate())
                        .build());
        jwt.sign(new RSASSASigner(otherKey));
        String token = jwt.serialize();

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void handleShouldUseAudienceFieldWhenConfigured() throws Exception {
        OAuth2AuthenticationSpec spec = minimalSpec();
        spec.setAudience("custom-audience");
        TrackingRestlet tracker = new TrackingRestlet();
        OAuth2AuthenticationRestlet restlet = buildRestlet(spec, tracker);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("custom-audience")
                .expirationTime(futureDate())
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertTrue(tracker.wasCalled(),
                "JWT with matching custom audience should be accepted");
    }

    @Test
    void handleShouldRejectMalformedJwt() {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        Request request = bearerRequest("not.a.valid.jwt");
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void validateClaimsShouldPassWhenNoScopesConfigured() throws Exception {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .build();

        assertNull(restlet.validateClaims(claims),
                "No scopes configured should not trigger scope validation");
    }

    @Test
    void findKeyShouldReturnFirstKeyWhenNoKid() {
        JWKSet keys = new JWKSet(rsaPublicJWK);
        assertNotNull(OAuth2AuthenticationRestlet.findKey(keys, null));
    }

    @Test
    void findKeyShouldReturnNullForUnknownKid() {
        JWKSet keys = new JWKSet(rsaPublicJWK);
        assertNull(OAuth2AuthenticationRestlet.findKey(keys, "unknown-kid"));
    }

    @Test
    void buildBearerChallengeParamsShouldReturnEmptyWhenNoParams() {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());
        assertEquals("", restlet.buildBearerChallengeParams(null, null, null));
    }

    @Test
    void buildBearerChallengeParamsShouldIncludeErrorAndDescription() {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());
        String params = restlet.buildBearerChallengeParams("invalid_token", "Token expired", null);
        assertTrue(params.contains("error=\"invalid_token\""));
        assertTrue(params.contains("error_description=\"Token expired\""));
    }

    @Test
    void extractBearerTokenShouldReturnNullWithoutAuthorizationHeader() {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());
        Request request = new Request(Method.POST, "http://localhost/mcp");
        assertNull(restlet.extractBearerToken(request));
    }

    @Test
    void extractBearerTokenShouldExtractFromAuthorizationHeader() {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());
        Request request = bearerRequest("my-token-123");
        assertEquals("my-token-123", restlet.extractBearerToken(request));
    }

    @Test
    void handleShouldReturnForbiddenWhenTokenHasNoScopeClaimButScopesRequired() throws Exception {
        OAuth2AuthenticationSpec spec = minimalSpec();
        spec.setScopes(List.of("tools:read"));
        OAuth2AuthenticationRestlet restlet = buildRestlet(spec);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
    }

    @Test
    void handleShouldRejectJwtWithNotBeforeInFuture() throws Exception {
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec());

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .notBeforeTime(new Date(System.currentTimeMillis() + 300_000))
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertTrue(rawValue.contains("invalid_token"));
        assertTrue(rawValue.contains("Token not yet valid"));
    }

    @Test
    void handleShouldAcceptJwtWithNotBeforeInPast() throws Exception {
        TrackingRestlet tracker = new TrackingRestlet();
        OAuth2AuthenticationRestlet restlet = buildRestlet(minimalSpec(), tracker);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .audience("https://mcp.example.com/mcp")
                .expirationTime(futureDate())
                .notBeforeTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertTrue(tracker.wasCalled(), "JWT with nbf in the past should be accepted");
    }

    @Test
    void handleShouldRejectIntrospectionMode() throws Exception {
        OAuth2AuthenticationSpec spec = minimalSpec();
        spec.setTokenValidation("introspection");
        OAuth2AuthenticationRestlet restlet = buildRestlet(spec);

        String token = signedJwt(new JWTClaimsSet.Builder()
                .issuer("https://auth.example.com")
                .expirationTime(futureDate())
                .build());

        Request request = bearerRequest(token);
        Response response = new Response(request);

        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
        String rawValue = response.getChallengeRequests().get(0).getRawValue();
        assertTrue(rawValue.contains("introspection is not yet supported"));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static OAuth2AuthenticationSpec minimalSpec() {
        OAuth2AuthenticationSpec spec = new OAuth2AuthenticationSpec();
        spec.setAuthorizationServerUri("https://auth.example.com");
        spec.setResource("https://mcp.example.com/mcp");
        return spec;
    }

    private OAuth2AuthenticationRestlet buildRestlet(OAuth2AuthenticationSpec spec) {
        return new OAuth2AuthenticationRestlet(spec, new NoOpRestlet(), jwkSet);
    }

    private OAuth2AuthenticationRestlet buildRestlet(OAuth2AuthenticationSpec spec,
            Restlet next) {
        return new OAuth2AuthenticationRestlet(spec, next, jwkSet);
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
