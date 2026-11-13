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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import io.ikanos.engine.util.Resolver;
import io.ikanos.engine.util.Secrets;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;

/**
 * Applies a consumed-side {@link AuthenticationSpec} to an outbound Restlet {@link Request}.
 *
 * <p>Shared by every consumed adapter that speaks HTTP on the wire (the HTTP adapter and the MCP
 * Streamable HTTP adapter), so credential handling has one implementation. Mustache templates in
 * credential fields are resolved against the call parameters plus the capability bindings.</p>
 */
public final class ConsumedAuthentication {

    /** The types {@link #apply} sends, written as the schema writes them. */
    static final Set<String> SUPPORTED_TYPES = Set.of("apikey", "basic", "bearer", "digest");

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
     * a {@code serverRequest} carries a challenge response, that response is forwarded. Only
     * REST forward mode passes a {@code serverRequest}, and only when {@code Authorization} is a
     * trusted header (#1010); every other caller passes {@code null}.
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
        String type = authenticationSpec.getType();
        if (!isSupported(type)) {
            // Never send the call without the credential the capability asked for (#842)
            throw new IllegalArgumentException(unsupported(type));
        }
        switch (type) {
            case "basic":
                BasicAuthenticationSpec basicAuth = (BasicAuthenticationSpec) authenticationSpec;
                challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_BASIC);
                challengeResponse.setIdentifier(
                        render(basicAuth.getUsername(), parameters, "basic username"));
                challengeResponse.setSecret(render(Secrets.password(basicAuth.getPassword()),
                        parameters, "basic password").toCharArray());
                clientRequest.setChallengeResponse(challengeResponse);
                break;

            case "digest":
                DigestAuthenticationSpec digestAuth = (DigestAuthenticationSpec) authenticationSpec;
                challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_DIGEST);
                challengeResponse.setIdentifier(
                        render(digestAuth.getUsername(), parameters, "digest username"));
                challengeResponse.setSecret(render(Secrets.password(digestAuth.getPassword()),
                        parameters, "digest password").toCharArray());
                clientRequest.setChallengeResponse(challengeResponse);
                break;

            case "bearer":
                BearerAuthenticationSpec bearerAuth = (BearerAuthenticationSpec) authenticationSpec;
                challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_OAUTH_BEARER);
                challengeResponse.setRawValue(
                        render(bearerAuth.getToken(), parameters, "bearer token"));
                clientRequest.setChallengeResponse(challengeResponse);
                break;

            case "apikey":
                ApiKeyAuthenticationSpec apiKeyAuth = (ApiKeyAuthenticationSpec) authenticationSpec;
                String key = render(apiKeyAuth.getKey(), parameters, "apikey key");
                String value = render(apiKeyAuth.getValue(), parameters, "apikey value");
                if (key == null || key.isBlank() || value == null || value.isBlank()) {
                    // A last guard: the capability already refuses to load without them (#964)
                    throw new IllegalArgumentException(
                            "An apikey credential needs a key and a value to be sent");
                }

                if (apiKeyAuth.isQueryPlacement()) {
                    // Encoded like every query parameter, so the API reads it unchanged (#841)
                    clientRequest.setResourceRef(
                            Resolver.appendQueryParameter(targetRef, key, value));
                } else if ("Authorization".equalsIgnoreCase(key)) {
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
                    // A header, also when placement is omitted, as the schema says (#840)
                    clientRequest.getHeaders().add(key, value);
                }
                break;

            default:
                throw new IllegalArgumentException(unsupported(type));
        }
    }

    /**
     * Renders a credential template, refusing one that uses a variable with no value: unbound (a
     * typo such as {@code {{TOKN}}}) or bound to a blank value. Rendering alone would turn it into
     * an empty string, or keep the placeholder when there are no parameters, and send that as the
     * credential (#843). The error names the variables, never a value.
     *
     * @param template the credential template, may be null
     * @param parameters template parameters, already merged with the bindings
     * @param credential how the error names the credential, such as "bearer token"
     * @return the rendered credential, or {@code null} if {@code template} is null
     * @throws IllegalArgumentException when a variable of the template has no value
     */
    static String render(String template, Map<String, Object> parameters, String credential) {
        // A caller claim the token does not carry is the caller's, not a configuration error (#998)
        Resolver.requireCallerValues(template, parameters);
        List<String> empty = Resolver.emptyVariables(template, parameters);
        if (!empty.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty template parameters in the "
                    + credential + ": " + empty + ". The credential is not sent.");
        }
        return Secrets.resolve(template, parameters);
    }

    /**
     * The values {@link #apply} renders as credential templates, keyed by where they are, such as
     * {@code "<where> token"}.
     *
     * @param authentication the consumed-side credential, may be null
     * @param where the prefix of each key, such as {@code "crm authentication"}
     * @return the templates in declaration order; empty for no authentication
     */
    public static Map<String, String> credentials(AuthenticationSpec authentication,
            String where) {
        Map<String, String> credentials = new LinkedHashMap<>();
        if (authentication instanceof BasicAuthenticationSpec basic) {
            credentials.put(where + " username", basic.getUsername());
            credentials.put(where + " password", passwordOrNull(basic.getPassword()));
        } else if (authentication instanceof DigestAuthenticationSpec digest) {
            credentials.put(where + " username", digest.getUsername());
            credentials.put(where + " password", passwordOrNull(digest.getPassword()));
        } else if (authentication instanceof BearerAuthenticationSpec bearer) {
            credentials.put(where + " token", bearer.getToken());
        } else if (authentication instanceof ApiKeyAuthenticationSpec apiKey) {
            credentials.put(where + " key", apiKey.getKey());
            credentials.put(where + " value", apiKey.getValue());
        }
        return credentials;
    }

    /**
     * @param authentication the consumed-side credential, may be null
     * @param parameters template parameters, already merged with the bindings
     * @return {@code true} when every variable of the credential has a value, so {@link #apply}
     *         can send it; also {@code true} with no authentication
     */
    public static boolean canRender(AuthenticationSpec authentication,
            Map<String, Object> parameters) {
        return credentials(authentication, "").values().stream()
                .allMatch(template -> Resolver.emptyVariables(template, parameters).isEmpty());
    }

    private static String passwordOrNull(char[] password) {
        return password == null ? null : new String(password);
    }

    /**
     * Lists the consumed adapters whose credential {@link #apply} can never send: a type it doesn't
     * handle, such as {@code oauth2} or a type written with another case (#842), or an
     * {@code apikey} without a {@code key} or a {@code value} (#964). The schema refuses both, but
     * the engine loads a capability without checking the schema, and code can build a spec. The
     * capability refuses to load with one, rather than failing every call to that upstream.
     *
     * @param spec the capability spec, may be null
     * @return one message per such adapter, naming its namespace and what is wrong, never a value
     */
    public static List<String> findUnsupported(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            return errors;
        }
        List<ClientSpec> clients = new ArrayList<>();
        if (spec.getConsumes() != null) {
            clients.addAll(spec.getConsumes());
        }
        if (spec.getCapability() != null && spec.getCapability().getConsumes() != null) {
            clients.addAll(spec.getCapability().getConsumes());
        }
        for (ClientSpec client : clients) {
            AuthenticationSpec authentication = client instanceof HttpClientSpec http
                    ? http.getAuthentication()
                    : client instanceof McpClientSpec mcp ? mcp.getAuthentication() : null;
            if (authentication == null) {
                continue;
            }
            if (!isSupported(authentication.getType())) {
                errors.add("consumes '" + client.getNamespace() + "': "
                        + unsupported(authentication.getType()));
            } else if (authentication instanceof ApiKeyAuthenticationSpec apiKey) {
                String missing = missing(apiKey);
                if (missing != null) {
                    errors.add("consumes '" + client.getNamespace()
                            + "': an apikey credential needs a key and a value, and this one has "
                            + missing);
                }
            }
        }
        return errors;
    }

    /**
     * What an apikey lacks to be sent, such as "no value" or "an empty key", or null when it has
     * both. A blank key or value would send an empty header, or a header with no name.
     */
    private static String missing(ApiKeyAuthenticationSpec apiKey) {
        String key = lacking(apiKey.getKey(), "key");
        String value = lacking(apiKey.getValue(), "value");
        if (key != null && value != null) {
            return key + " and " + value;
        }
        return key != null ? key : value;
    }

    /** "no key" when absent, "an empty key" when blank, or null when it has text. */
    private static String lacking(String text, String what) {
        if (text == null) {
            return "no " + what;
        }
        return text.isBlank() ? "an empty " + what : null;
    }

    /** {@code null} too: a spec built in code may have no type. */
    private static boolean isSupported(String type) {
        return type != null && SUPPORTED_TYPES.contains(type);
    }

    private static String unsupported(String type) {
        return "Unsupported consumed authentication type '" + type
                + "': the engine can send apikey, basic, bearer or digest credentials";
    }
}
