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
package io.ikanos.spec.consumes.http;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OAuth 2.1 Resource Server Authentication Specification Element.
 *
 * <p>The server validates bearer tokens issued by an external authorization server.
 * Supports JWKS-based JWT validation (default) and token introspection (RFC 7662).</p>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code scopes} list is stored
 * as an immutable snapshot. This satisfies SonarQube rule {@code java:S3077}.
 */
public class OAuth2AuthenticationSpec extends AuthenticationSpec {

    private final AtomicReference<String> authorizationServerUri = new AtomicReference<>();
    private final AtomicReference<String> resource = new AtomicReference<>();
    private final AtomicReference<List<String>> scopes = new AtomicReference<>();
    private final AtomicReference<String> audience = new AtomicReference<>();
    private final AtomicReference<String> tokenEndpoint = new AtomicReference<>();
    private final AtomicReference<String> tokenValidation = new AtomicReference<>();

    public OAuth2AuthenticationSpec() {
        super("oauth2");
    }

    public String getAuthorizationServerUri() {
        return authorizationServerUri.get();
    }

    public void setAuthorizationServerUri(String authorizationServerUri) {
        this.authorizationServerUri.set(authorizationServerUri);
    }

    public String getResource() {
        return resource.get();
    }

    public void setResource(String resource) {
        this.resource.set(resource);
    }

    public List<String> getScopes() {
        return scopes.get();
    }

    public void setScopes(List<String> scopes) {
        this.scopes.set(scopes != null ? List.copyOf(scopes) : null);
    }

    public String getAudience() {
        return audience.get();
    }

    public void setAudience(String audience) {
        this.audience.set(audience);
    }

    public String getTokenValidation() {
        return tokenValidation.get();
    }

    public void setTokenValidation(String tokenValidation) {
        this.tokenValidation.set(tokenValidation);
    }

    public String getTokenEndpoint() {
        return tokenEndpoint.get();
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint.set(tokenEndpoint);
    }

}
