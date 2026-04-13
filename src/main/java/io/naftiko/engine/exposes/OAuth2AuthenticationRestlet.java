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

import java.io.IOException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.naftiko.spec.consumes.OAuth2AuthenticationSpec;

/**
 * Shared Restlet that implements OAuth 2.1 resource server authentication. Validates bearer tokens
 * (JWTs) issued by an external authorization server using JWKS-based signature verification.
 *
 * <p>Used directly by REST and Skill adapters. Extended by {@code McpOAuth2Restlet} for
 * MCP-specific protocol behavior (Protected Resource Metadata, {@code resource_metadata} in
 * {@code WWW-Authenticate} headers).</p>
 */
public class OAuth2AuthenticationRestlet extends Restlet {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ChallengeScheme BEARER_SCHEME =
            new ChallengeScheme("HTTP_Bearer", "Bearer");

    static final long JWKS_CACHE_TTL_MS = 5 * 60 * 1000L;
    static final long JWKS_MIN_REFRESH_MS = 30 * 1000L;

    private final OAuth2AuthenticationSpec spec;
    private final Restlet next;
    private final Client httpClient;

    private volatile JWKSet cachedJwkSet;
    private volatile long jwkSetTimestamp;
    private volatile String discoveredJwksUri;
    private volatile boolean initialized;

    private final Object initLock = new Object();
    private final Object jwkRefreshLock = new Object();

    public OAuth2AuthenticationRestlet(OAuth2AuthenticationSpec spec, Restlet next) {
        this(spec, next, null);
    }

    /**
     * Test constructor — allows injecting a pre-loaded JWK set to bypass AS metadata discovery.
     */
    protected OAuth2AuthenticationRestlet(OAuth2AuthenticationSpec spec, Restlet next, JWKSet jwkSet) {
        this.spec = spec;
        this.next = next;
        this.httpClient = new Client(Protocol.HTTP, Protocol.HTTPS);
        try {
            this.httpClient.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start HTTP client", e);
        }

        if(jwkSet != null) {
            this.cachedJwkSet = jwkSet;
            this.jwkSetTimestamp = System.currentTimeMillis();
            this.initialized = true;
        }
    }

    @Override
    public void handle(Request request, Response response) {
        String token = extractBearerToken(request);
        if (token == null) {
            unauthorized(response, null, null);
            return;
        }

        try {
            ensureInitialized();
            validateAndDispatch(token, request, response);
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.WARNING, "Token validation error", e);
            unauthorized(response, "invalid_token", "Token validation failed");
        }
    }

    void validateAndDispatch(String token, Request request, Response response)
            throws ParseException, JOSEException {
        String validationMode =
                spec.getTokenValidation() != null ? spec.getTokenValidation() : "jwks";

        if ("introspection".equals(validationMode)) {
            unauthorized(response, "invalid_request",
                    "Token introspection is not yet supported — use tokenValidation: jwks");
            return;
        }

        SignedJWT jwt = SignedJWT.parse(token);

        if (!verifySignature(jwt)) {
            unauthorized(response, "invalid_token", "Signature verification failed");
            return;
        }

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        String error = validateClaims(claims);
        if (error != null) {
            if (error.startsWith("insufficient_scope:")) {
                String requiredScope = error.substring("insufficient_scope:".length()).trim();
                forbidden(response, requiredScope);
            } else {
                unauthorized(response, "invalid_token", error);
            }
            return;
        }

        next.handle(request, response);
    }

    String extractBearerToken(Request request) {
        if (request.getChallengeResponse() != null
                && request.getChallengeResponse().getRawValue() != null) {
            return request.getChallengeResponse().getRawValue();
        }

        String authorization = request.getHeaders().getFirstValue("Authorization", true);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }

        return null;
    }

    boolean verifySignature(SignedJWT jwt) throws JOSEException {
        String kid = jwt.getHeader().getKeyID();

        JWKSet keys = getCachedOrFetchJwkSet();
        if (keys == null) {
            return false;
        }

        JWK key = findKey(keys, kid);
        if (key == null) {
            keys = refreshJwkSet();
            if (keys != null) {
                key = findKey(keys, kid);
            }
        }

        if (key == null) {
            return false;
        }

        JWSVerifier verifier = buildVerifier(key);
        return verifier != null && jwt.verify(verifier);
    }

    String validateClaims(JWTClaimsSet claims) {
        if (claims.getExpirationTime() != null
                && claims.getExpirationTime().before(new Date())) {
            return "Token expired";
        }

        String expectedIssuer = spec.getAuthorizationServerUri();
        if (expectedIssuer != null && claims.getIssuer() != null
                && !expectedIssuer.equals(claims.getIssuer())) {
            return "Invalid issuer";
        }

        String expectedAudience =
                spec.getAudience() != null ? spec.getAudience() : spec.getResource();
        if (expectedAudience != null) {
            List<String> audiences = claims.getAudience();
            if (audiences != null && !audiences.isEmpty()
                    && !audiences.contains(expectedAudience)) {
                return "Invalid audience";
            }
        }

        if (spec.getScopes() != null && !spec.getScopes().isEmpty()) {
            Object scopeClaim = claims.getClaim("scope");
            if (scopeClaim == null) {
                return "insufficient_scope: " + spec.getScopes().get(0);
            }
            Set<String> tokenScopes =
                    new HashSet<>(Arrays.asList(scopeClaim.toString().split("\\s+")));
            for (String required : spec.getScopes()) {
                if (!tokenScopes.contains(required)) {
                    return "insufficient_scope: " + required;
                }
            }
        }

        return null;
    }

    protected void unauthorized(Response response, String error, String description) {
        response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
        addBearerChallenge(response,
                buildBearerChallengeParams(error, description, null));
        response.setEntity("Unauthorized", MediaType.TEXT_PLAIN);
    }

    protected void forbidden(Response response, String requiredScope) {
        response.setStatus(Status.CLIENT_ERROR_FORBIDDEN);
        String scopeValue = spec.getScopes() != null ? String.join(" ", spec.getScopes()) : null;
        addBearerChallenge(response,
                buildBearerChallengeParams("insufficient_scope",
                        "Required scope '" + requiredScope + "' not present in token",
                        scopeValue));
        response.setEntity("Forbidden", MediaType.TEXT_PLAIN);
    }

    private void addBearerChallenge(Response response, String params) {
        ChallengeRequest cr = new ChallengeRequest(BEARER_SCHEME);
        if (params != null && !params.isEmpty()) {
            cr.setRawValue(params);
        }
        response.getChallengeRequests().add(cr);
    }

    protected String buildBearerChallengeParams(String error, String description, String scope) {
        StringBuilder sb = new StringBuilder();
        boolean hasParams = false;

        if (error != null) {
            sb.append("error=\"").append(error).append("\"");
            hasParams = true;
        }
        if (description != null) {
            sb.append(hasParams ? ", " : "");
            sb.append("error_description=\"").append(description).append("\"");
            hasParams = true;
        }
        if (scope != null) {
            sb.append(hasParams ? ", " : "");
            sb.append("scope=\"").append(scope).append("\"");
        }

        return sb.toString();
    }

    protected OAuth2AuthenticationSpec getSpec() {
        return spec;
    }

    protected Restlet getNext() {
        return next;
    }

    // ─── AS Metadata & JWKS Discovery ───────────────────────────────────────────

    void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            try {
                discoverAsMetadata();
                if (discoveredJwksUri != null) {
                    fetchAndCacheJwkSet(discoveredJwksUri);
                }
                initialized = true;
            } catch (Exception e) {
                Context.getCurrentLogger().log(Level.WARNING, "AS metadata discovery failed", e);
                initialized = true;
            }
        }
    }

    void discoverAsMetadata() throws IOException {
        String baseUri = spec.getAuthorizationServerUri();
        if (baseUri == null) {
            return;
        }

        String asMetadataUri = stripTrailingSlash(baseUri) + "/.well-known/oauth-authorization-server";
        String body = fetchUri(asMetadataUri);

        if (body == null) {
            String oidcUri = stripTrailingSlash(baseUri) + "/.well-known/openid-configuration";
            body = fetchUri(oidcUri);
        }

        if (body != null) {
            try {
                JsonNode metadata = JSON.readTree(body);
                if (metadata.has("jwks_uri")) {
                    discoveredJwksUri = metadata.get("jwks_uri").asText();
                }
            } catch (Exception e) {
                Context.getCurrentLogger().log(Level.WARNING, "Failed to parse AS metadata", e);
            }
        }
    }

    JWKSet getCachedOrFetchJwkSet() {
        if (cachedJwkSet != null) {
            long elapsed = System.currentTimeMillis() - jwkSetTimestamp;
            if (elapsed < JWKS_CACHE_TTL_MS) {
                return cachedJwkSet;
            }
            return refreshJwkSet();
        }

        if (discoveredJwksUri != null) {
            return refreshJwkSet();
        }

        return null;
    }

    JWKSet refreshJwkSet() {
        if (discoveredJwksUri == null) {
            return cachedJwkSet;
        }

        long now = System.currentTimeMillis();
        if (cachedJwkSet != null && (now - jwkSetTimestamp) < JWKS_MIN_REFRESH_MS) {
            return cachedJwkSet;
        }

        synchronized (jwkRefreshLock) {
            if (cachedJwkSet != null && (System.currentTimeMillis() - jwkSetTimestamp) < JWKS_MIN_REFRESH_MS) {
                return cachedJwkSet;
            }
            try {
                fetchAndCacheJwkSet(discoveredJwksUri);
            } catch (Exception e) {
                Context.getCurrentLogger().log(Level.WARNING, "JWKS refresh failed", e);
            }
            return cachedJwkSet;
        }
    }

    void fetchAndCacheJwkSet(String jwksUri) throws IOException {
        String body = fetchUri(jwksUri);
        if (body != null) {
            try {
                cachedJwkSet = JWKSet.parse(body);
                jwkSetTimestamp = System.currentTimeMillis();
            } catch (ParseException e) {
                throw new IOException("Failed to parse JWKS", e);
            }
        }
    }

    /**
     * HTTP GET a URI and return the response body, or null on failure. Protected for test
     * overriding.
     */
    protected String fetchUri(String uri) throws IOException {
        try {
            Request req = new Request(Method.GET, new Reference(uri));
            Response resp = httpClient.handle(req);

            if (resp.getStatus().equals(Status.SUCCESS_OK)
                    && resp.getEntity() != null) {
                return resp.getEntity().getText();
            }

            Context.getCurrentLogger().log(Level.WARNING,
                    "HTTP {0} from {1}", new Object[] {resp.getStatus().getCode(), uri});
            return null;
        } catch (Exception e) {
            throw new IOException("Failed to fetch " + uri, e);
        }
    }

    Client getHttpClient() {
        return httpClient;
    }

    // ─── JWK Helpers ────────────────────────────────────────────────────────────

    static JWK findKey(JWKSet jwkSet, String kid) {
        if (kid != null) {
            return jwkSet.getKeyByKeyId(kid);
        }
        List<JWK> keys = jwkSet.getKeys();
        return keys.isEmpty() ? null : keys.get(0);
    }

    static JWSVerifier buildVerifier(JWK key) throws JOSEException {
        if (key instanceof RSAKey rsaKey) {
            return new RSASSAVerifier(rsaKey);
        }
        if (key instanceof ECKey ecKey) {
            return new ECDSAVerifier(ecKey);
        }
        return null;
    }

    private static String stripTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

}
