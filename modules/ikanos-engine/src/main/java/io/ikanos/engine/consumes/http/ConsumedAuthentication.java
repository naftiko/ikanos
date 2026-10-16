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
package io.ikanos.engine.consumes.http;

import java.util.HashMap;
import java.util.Map;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;

/**
 * Applies a consumed-side {@link AuthenticationSpec} to an outbound Restlet {@link Request}.
 *
 * <p>Shared by every consumed adapter that speaks HTTP on the wire (the HTTP adapter and the MCP
 * Streamable HTTP adapter), so credential handling has one implementation. Mustache templates in
 * credential fields are resolved against the call parameters plus the capability bindings.</p>
 */
public final class ConsumedAuthentication {

    private ConsumedAuthentication() {
    }

    /**
     * Merge call parameters with capability bindings; bindings win, as before.
     *
     * @param parameters call parameters, may be {@code null}
     * @param bindings   capability bindings, may be {@code null}
     * @return a new map
     */
    public static Map<String, Object> withBindings(Map<String, Object> parameters,
            Map<String, Object> bindings) {
        Map<String, Object> extended = new HashMap<>();
        if (parameters != null) {
            extended.putAll(parameters);
        }
        if (bindings != null) {
            extended.putAll(bindings);
        }
        return extended;
    }

    /**
     * Apply {@code authenticationSpec} to {@code clientRequest}. When the spec is {@code null} and
     * a {@code serverRequest} carries a challenge response, that response is forwarded (REST
     * forward mode only; the MCP adapter always passes {@code null}).
     *
     * @param authenticationSpec the consumed-side credential, or {@code null}
     * @param serverRequest      the inbound request (forward mode), or {@code null}
     * @param clientRequest      the outbound request to decorate
     * @param targetRef          the outbound target, used for query-placed API keys
     * @param parameters         template parameters (already merged with bindings)
     */
    public static void apply(AuthenticationSpec authenticationSpec, Request serverRequest,
            Request clientRequest, String targetRef, Map<String, Object> parameters) {
        if (authenticationSpec == null) {
            if (serverRequest != null && serverRequest.getChallengeResponse() != null) {
                clientRequest.setChallengeResponse(serverRequest.getChallengeResponse());
            }
            return;
        }

        ChallengeResponse challengeResponse;
        switch (authenticationSpec.getType()) {
            case "basic":
                BasicAuthenticationSpec basicAuth = (BasicAuthenticationSpec) authenticationSpec;
                challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_BASIC);
                challengeResponse.setIdentifier(
                        Resolver.resolveMustacheTemplate(basicAuth.getUsername(), parameters));
                challengeResponse.setSecret(Resolver
                        .resolveMustacheTemplate(passwordOrEmpty(basicAuth.getPassword()),
                                parameters)
                        .toCharArray());
                clientRequest.setChallengeResponse(challengeResponse);
                break;

            case "digest":
                DigestAuthenticationSpec digestAuth = (DigestAuthenticationSpec) authenticationSpec;
                challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_DIGEST);
                challengeResponse.setIdentifier(
                        Resolver.resolveMustacheTemplate(digestAuth.getUsername(), parameters));
                challengeResponse.setSecret(Resolver.resolveMustacheTemplate(
                        passwordOrEmpty(digestAuth.getPassword()), parameters).toCharArray());
                clientRequest.setChallengeResponse(challengeResponse);
                break;

            case "bearer":
                BearerAuthenticationSpec bearerAuth = (BearerAuthenticationSpec) authenticationSpec;
                challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_OAUTH_BEARER);
                challengeResponse.setRawValue(
                        Resolver.resolveMustacheTemplate(bearerAuth.getToken(), parameters));
                clientRequest.setChallengeResponse(challengeResponse);
                break;

            case "apikey":
                ApiKeyAuthenticationSpec apiKeyAuth = (ApiKeyAuthenticationSpec) authenticationSpec;
                String key = Resolver.resolveMustacheTemplate(apiKeyAuth.getKey(), parameters);
                String value = Resolver.resolveMustacheTemplate(apiKeyAuth.getValue(), parameters);
                String placement = apiKeyAuth.getPlacement();

                if (placement == null) {
                    throw new IllegalArgumentException(
                            "Placement is required for apikey authentication (expected: header or query)");
                }

                if (placement.equals("header")) {
                    if ("Authorization".equalsIgnoreCase(key)) {
                        ApiKeyAuthorizationHeaderHelper.ensureRegistered();
                        ChallengeResponse rawChallenge =
                                new ChallengeResponse(ApiKeyAuthorizationHeaderHelper.SCHEME);
                        // setIdentifier (not setRawValue): formatResponse() short-circuits to
                        // the raw value when it is set and never calls the registered helper,
                        // which is what strips the technicalName + separator Restlet would
                        // otherwise inject (see ApiKeyAuthorizationHeaderHelper's javadoc).
                        rawChallenge.setIdentifier(value);
                        clientRequest.setChallengeResponse(rawChallenge);
                    } else {
                        clientRequest.getHeaders().add(key, value);
                    }
                } else if (placement.equals("query")) {
                    String separator = targetRef.contains("?") ? "&" : "?";
                    clientRequest.setResourceRef(targetRef + separator + key + "=" + value);
                }
                break;

            default:
                break;
        }
    }

    private static String passwordOrEmpty(char[] password) {
        return password == null ? "" : new String(password);
    }
}
