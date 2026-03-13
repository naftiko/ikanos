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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import io.naftiko.engine.Resolver;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.AuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;

/**
 * Restlet responsible for API server-side authentication for bearer and apikey schemes.
 */
public class RestServerAuthenticationRestlet extends Restlet {

    private static final Pattern ENV_MUSTACHE =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_\\-]+)\\s*\\}\\}");

    private final AuthenticationSpec authentication;
    private final Restlet next;
    private final Set<String> allowedVariables;

    public RestServerAuthenticationRestlet(AuthenticationSpec authentication, Restlet next) {
        this(authentication, next, null);
    }

    public RestServerAuthenticationRestlet(AuthenticationSpec authentication, Restlet next,
            Set<String> allowedVariables) {
        this.authentication = authentication;
        this.next = next;
        this.allowedVariables = allowedVariables != null ? allowedVariables : Set.of();
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
                response.setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
                response.setEntity("Unsupported REST server authentication type: " + type,
                        MediaType.TEXT_PLAIN);
                return;
        }

        if (!authorized) {
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

        if (key == null || key.isBlank() || value == null) {
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

    private String resolveSecretTemplate(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        Map<String, Object> variables = new HashMap<>();

        Matcher matcher = ENV_MUSTACHE.matcher(rawValue);

        while (matcher.find()) {
            String variableName = matcher.group(1);
            
            // Only resolve variables that are explicitly declared in externalRefs
            if (!allowedVariables.isEmpty() && !allowedVariables.contains(variableName)) {
                continue;
            }
            
            String envValue = System.getenv(variableName);

            if (envValue != null) {
                variables.put(variableName, envValue);
            }
        }

        return Resolver.resolveMustacheTemplate(rawValue, variables);
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
