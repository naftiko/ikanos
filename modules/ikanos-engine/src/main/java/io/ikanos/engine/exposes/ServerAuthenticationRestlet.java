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
package io.ikanos.engine.exposes;

import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Restlet responsible for API server-side authentication for bearer and apikey schemes.
 */
public class ServerAuthenticationRestlet extends Restlet {

    private final AuthenticationSpec authentication;
    private final Restlet next;
    private final Map<String, Object> bindings;

    public ServerAuthenticationRestlet(AuthenticationSpec authentication, Restlet next) {
        this(authentication, next, null);
    }

    public ServerAuthenticationRestlet(AuthenticationSpec authentication, Restlet next,
                                       Map<String, Object> bindings) {
        this.authentication = authentication;
        this.next = next;
        this.bindings = bindings != null ? bindings : Map.of();
    }

    @Override
    public void handle(Request request, Response response) {
        if (authentication == null || authentication.getType() == null) {
            next.handle(request, response);
            return;
        }

        String type = authentication.getType();
        boolean authorized = false;

        switch (type) {
            case "bearer":
                authorized = authenticateBearer(request);
                break;
            case "apikey":
                authorized = authenticateApiKey(request);
                break;
            default:
                // A configuration error that fails every request, so the body is not discarded
                response.setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
                response.setEntity("Unsupported REST server authentication type: " + type,
                        MediaType.TEXT_PLAIN);
                return;
        }

        if (!authorized) {
            RejectedRequestBodies.discard(request);
            response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
            response.setEntity("Unauthorized", MediaType.TEXT_PLAIN);
            return;
        }

        next.handle(request, response);
    }

    private boolean authenticateBearer(Request request) {
        if (!(authentication instanceof BearerAuthenticationSpec bearerAuth)) {
            return false;
        }

        String expectedToken = resolveSecretTemplate(bearerAuth.getToken());

        if (expectedToken == null || expectedToken.isBlank()) {
            return false;
        }

        String actualToken = null;

        if (request.getChallengeResponse() != null
                && request.getChallengeResponse().getRawValue() != null) {
            actualToken = request.getChallengeResponse().getRawValue();
        }

        if (actualToken == null || actualToken.isBlank()) {
            String authorization = request.getHeaders().getFirstValue("Authorization", true);

            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                actualToken = authorization.substring(7).trim();
            }
        }

        return secureEquals(expectedToken, actualToken);
    }

    private boolean authenticateApiKey(Request request) {
        if (!(authentication instanceof ApiKeyAuthenticationSpec apiKeyAuth)) {
            return false;
        }

        String key = resolveSecretTemplate(apiKeyAuth.getKey());
        String value = resolveSecretTemplate(apiKeyAuth.getValue());
        String placement = apiKeyAuth.getPlacement() != null ? apiKeyAuth.getPlacement() : "header";

        // A blank expected value would match a blank header or query value (#856)
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return false;
        }

        String actualValue = null;

        if ("query".equalsIgnoreCase(placement)) {
            if (request.getResourceRef() != null
                    && request.getResourceRef().getQueryAsForm() != null) {
                actualValue = request.getResourceRef().getQueryAsForm().getFirstValue(key);
            }
        } else {
            actualValue = request.getHeaders().getFirstValue(key, true);
        }

        return secureEquals(value, actualValue);
    }

    /**
     * Checks, when the adapter is built, that the credentials a bearer or apikey authentication
     * expects don't resolve to blank: an empty expected credential would accept an empty one
     * (#856). Someone who wants no credential removes the authentication block instead.
     *
     * @param authentication the adapter's authentication, may be null
     * @param bindings the capability's bindings, may be null
     * @param adapter how the error names the adapter, such as "the rest adapter 'shop' on port 8080"
     * @throws IllegalArgumentException naming the credential and its variables, never its value
     */
    static void requireCredentials(AuthenticationSpec authentication, Map<String, Object> bindings,
            String adapter) {
        if (authentication instanceof BearerAuthenticationSpec bearer) {
            requireNotBlank("bearer token", bearer.getToken(), bindings, adapter);
        } else if (authentication instanceof ApiKeyAuthenticationSpec apiKey) {
            requireNotBlank("apikey key", apiKey.getKey(), bindings, adapter);
            requireNotBlank("apikey value", apiKey.getValue(), bindings, adapter);
        }
    }

    private static void requireNotBlank(String credential, String template,
            Map<String, Object> bindings, String adapter) {
        String resolved = template != null
                ? Resolver.resolveMustacheTemplate(template, bindings != null ? bindings : Map.of())
                : null;
        if (resolved != null && !resolved.isBlank()) {
            return;
        }
        // With no parameters, every variable of the template counts as missing: all are named
        List<String> variables = Resolver.missingVariables(template, null);
        throw new IllegalArgumentException("The " + credential + " of " + adapter + " is empty"
                + (variables.isEmpty() ? ""
                        : ": bind " + String.join(", ", variables) + " to a non-empty value")
                + ". To expose it without a credential, remove its authentication block.");
    }

    private String resolveSecretTemplate(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        return Resolver.resolveMustacheTemplate(rawValue, bindings);
    }

    private boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

}
