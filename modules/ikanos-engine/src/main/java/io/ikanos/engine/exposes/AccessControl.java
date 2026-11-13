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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Checks the {@code authorization} block of an exposed adapter, resource, operation or tool: what
 * the caller's token, validated by {@link OAuth2AuthenticationRestlet}, must carry to make the call
 * (#999).
 *
 * <p>Shared by the REST and MCP adapters. The nearest level wins ({@link #nearest}). A call that
 * does not meet it is refused with {@code 403} before any upstream request: {@link #enforce} marks
 * the response with a {@link Denial}, and the OAuth2 restlet in front of the adapter turns it into
 * the {@code WWW-Authenticate: Bearer error="insufficient_scope"} challenge (RFC 6750 §3.1), so
 * the MCP adapter's challenge keeps its {@code resource_metadata} parameter.</p>
 */
public final class AccessControl {

    /** Response attribute holding the {@link Denial} of a call refused by {@link #enforce}. */
    public static final String DENIAL_ATTRIBUTE = "io.ikanos.oauth2.accessDenial";

    /**
     * Why a call was refused.
     *
     * @param scopes the scopes the call requires, sent back in the challenge's {@code scope}
     *        parameter so the client can ask for them; empty when only claims are required
     * @param description the challenge's {@code error_description}; it names what is missing,
     *        never a value of the token or the allowed claim values
     */
    public record Denial(List<String> scopes, String description) {
    }

    private AccessControl() {}

    /**
     * Lets the call through when the validated token meets the requirement. Otherwise sets the
     * response to {@code 403}, attaches the {@link Denial} under {@link #DENIAL_ATTRIBUTE} and
     * returns {@code false}: the caller must stop handling the request.
     *
     * @param requirement the requirement that applies, or {@code null} for none
     * @return {@code true} when the call may proceed
     */
    public static boolean enforce(AuthorizationSpec requirement, Request request,
            Response response) {
        if (requirement == null) {
            return true;
        }
        Object claims = request.getAttributes()
                .get(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE);
        Denial denial = claims instanceof JWTClaimsSet validated
                ? check(requirement, validated)
                : new Denial(requirement.getScopes(), "No validated access token");
        if (denial == null) {
            return true;
        }
        response.setStatus(Status.CLIENT_ERROR_FORBIDDEN);
        response.setEntity("Forbidden", MediaType.TEXT_PLAIN);
        response.getAttributes().put(DENIAL_ATTRIBUTE, denial);
        return false;
    }

    /**
     * Checks a requirement against validated claims.
     *
     * @return {@code null} when the claims meet the requirement, else why they don't
     */
    static Denial check(AuthorizationSpec requirement, JWTClaimsSet claims) {
        Set<String> granted = tokenScopes(claims);
        for (String scope : requirement.getScopes()) {
            if (!granted.contains(scope)) {
                return new Denial(requirement.getScopes(),
                        "Required scope '" + scope + "' not present in token");
            }
        }
        for (Map.Entry<String, List<String>> claim : requirement.getClaims().entrySet()) {
            if (!holdsOneOf(claimValue(claims, claim.getKey()), claim.getValue())) {
                return new Denial(requirement.getScopes(),
                        "Token claim '" + claim.getKey() + "' does not hold an allowed value");
            }
        }
        return null;
    }

    /**
     * The scopes granted by a token: its {@code scope} claim (RFC 8693 / RFC 9068, space-separated)
     * and its {@code scp} claim (Azure AD, Okta), each read as a string or an array of strings.
     */
    static Set<String> tokenScopes(JWTClaimsSet claims) {
        Set<String> scopes = new LinkedHashSet<>();
        addScopes(claims.getClaim("scope"), scopes);
        addScopes(claims.getClaim("scp"), scopes);
        return scopes;
    }

    private static void addScopes(Object claim, Set<String> scopes) {
        if (claim instanceof String value) {
            for (String scope : value.trim().split("\\s+")) {
                if (!scope.isEmpty()) {
                    scopes.add(scope);
                }
            }
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                addScopes(value, scopes);
            }
        }
    }

    /**
     * The value of a claim. A name that is a claim of its own (such as the namespaced
     * {@code https://example.com/roles} of Auth0) is read as is; otherwise a dotted name is a path
     * into nested claims, such as Keycloak's {@code realm_access.roles}.
     *
     * @return the value, or {@code null} when the token does not carry it
     */
    static Object claimValue(JWTClaimsSet claims, String name) {
        Object direct = claims.getClaim(name);
        if (direct != null || !name.contains(".")) {
            return direct;
        }
        Object current = claims.getClaims();
        for (String segment : name.split("\\.", -1)) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /**
     * Whether a claim value is one of the allowed values, or, for an array, contains one. Values
     * are compared as exact strings; a number or a boolean is compared by its text.
     */
    static boolean holdsOneOf(Object value, List<String> allowed) {
        if (value instanceof Collection<?> values) {
            for (Object element : values) {
                if (isScalar(element) && allowed.contains(String.valueOf(element))) {
                    return true;
                }
            }
            return false;
        }
        return isScalar(value) && allowed.contains(String.valueOf(value));
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    // ─── Load-time validation ───────────────────────────────────────────────────

    /** Every {@code authorization} declared in a REST adapter, keyed by where it is declared. */
    public static Map<String, AuthorizationSpec> declaredIn(RestServerSpec adapter) {
        Map<String, AuthorizationSpec> declared = new LinkedHashMap<>();
        put(declared, "the adapter", adapter.getAuthorization());
        adapter.getResources().forEach((resourceName, resource) -> {
            put(declared, "resource '" + resourceName + "'", resource.getAuthorization());
            resource.getOperations().forEach((operationName, operation) -> put(declared,
                    "operation '" + resourceName + "." + operationName + "'",
                    operation.getAuthorization()));
        });
        return declared;
    }

    /** Every {@code authorization} declared in an MCP adapter, keyed by where it is declared. */
    public static Map<String, AuthorizationSpec> declaredIn(McpServerSpec adapter) {
        Map<String, AuthorizationSpec> declared = new LinkedHashMap<>();
        put(declared, "the adapter", adapter.getAuthorization());
        adapter.getTools().forEach((toolName, tool) ->
                put(declared, "tool '" + toolName + "'", tool.getAuthorization()));
        return declared;
    }

    private static void put(Map<String, AuthorizationSpec> declared, String where,
            AuthorizationSpec requirement) {
        if (requirement != null) {
            declared.put(where, requirement);
        }
    }

    /**
     * Fails the capability at load time when an {@code authorization} cannot be enforced: the
     * adapter does not validate OAuth 2.1 tokens (other authentication, none, or the MCP stdio transport),
     * a required scope is not one the adapter recognizes ({@code authentication.scopes}), or a
     * claim lists no allowed value. The schema and the ruleset report the same problems at lint
     * time.
     *
     * @param adapter the exposed adapter
     * @param stdio whether the adapter is an MCP adapter on the stdio transport
     * @param declared every {@code authorization} of the adapter, keyed by where it is declared
     * @throws IllegalArgumentException naming the adapter, the location and the problem
     */
    public static void validate(ServerSpec adapter, boolean stdio,
            Map<String, AuthorizationSpec> declared) {
        if (declared.isEmpty()) {
            return;
        }
        List<String> errors = new ArrayList<>();
        AuthenticationSpec authentication = adapter.getAuthentication();
        String where = declared.keySet().iterator().next();
        if (stdio) {
            errors.add(where + " declares 'authorization', which the stdio transport cannot"
                    + " enforce: it carries no access token");
        } else if (!(authentication instanceof OAuth2AuthenticationSpec oauth2)) {
            errors.add(where + " declares 'authorization', which needs 'authentication: oauth2'");
        } else {
            List<String> recognized = oauth2.getScopes() != null ? oauth2.getScopes() : List.of();
            declared.forEach((location, requirement) -> {
                for (String scope : requirement.getScopes()) {
                    if (!recognized.contains(scope)) {
                        errors.add(location + " requires scope '" + scope
                                + "', which is not listed in 'authentication.scopes'");
                    }
                }
            });
        }
        declared.forEach((location, requirement) -> requirement.getClaims().forEach(
                (claim, values) -> {
                    if (claim == null || claim.isBlank()) {
                        errors.add(location + " requires a claim with a blank name");
                    } else if (values.isEmpty()) {
                        errors.add(location + " requires claim '" + claim
                                + "' without listing any allowed value");
                    }
                }));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Exposed adapter '" + adapter.getNamespace()
                    + "': " + String.join("; ", errors));
        }
    }

    /**
     * The requirement of a call to a REST resource: the operation's, else the resource's, else the
     * adapter's. Pass a {@code null} operation for a forwarded call.
     */
    public static AuthorizationSpec effective(RestServerSpec adapter,
            RestServerResourceSpec resource, RestServerOperationSpec operation) {
        return AuthorizationSpec.nearest(adapter.getAuthorization(), resource.getAuthorization(),
                operation != null ? operation.getAuthorization() : null);
    }

    /**
     * The requirement of a {@code tools/call}: the tool's, else the adapter's. Pass a {@code null}
     * tool for any other MCP request, or a call to an unknown tool.
     */
    public static AuthorizationSpec effective(McpServerSpec adapter, McpServerToolSpec tool) {
        return AuthorizationSpec.nearest(adapter.getAuthorization(),
                tool != null ? tool.getAuthorization() : null);
    }

}
