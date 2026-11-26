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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.spec.IkanosSpec;
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
     * @param scopes the scopes sent back in the challenge's {@code scope} parameter so the client
     *        can ask for them: those the call requires, then, for MCP, the ones the token already
     *        holds that the adapter still requires elsewhere; empty when the token holds every
     *        required scope and only a claim falls short
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
        return enforce(requirement, Set.of(), request, response);
    }

    /**
     * Same as {@link #enforce(AuthorizationSpec, Request, Response)}, except that a challenge
     * for missing scopes also asks for the scopes the token already holds and that are in
     * {@code stillRequired}, as MCP's Scope Challenge Handling (2025-11-25) asks, so a client that
     * steps up its authorization does not lose the scopes its other calls need.
     *
     * @param stillRequired every scope the adapter requires somewhere ({@link #requiredScopes})
     */
    public static boolean enforce(AuthorizationSpec requirement, Collection<String> stillRequired,
            Request request, Response response) {
        if (requirement == null) {
            return true;
        }
        Object claims = request.getAttributes()
                .get(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE);
        Denial denial = claims instanceof JWTClaimsSet validated
                ? check(requirement, validated, stillRequired)
                : noToken(requirement);
        if (denial == null) {
            return true;
        }
        response.setStatus(Status.CLIENT_ERROR_FORBIDDEN);
        response.setEntity("Forbidden", MediaType.TEXT_PLAIN);
        response.getAttributes().put(DENIAL_ATTRIBUTE, denial);
        return false;
    }

    /**
     * Checks a requirement against claims an application that embeds the engine has validated
     * itself, for a call it makes directly to a handler with no exposed server in between (#1126).
     * The rules are those of {@link #enforce}: scopes from {@code scope} and {@code scp}, dotted
     * claim paths, one of the allowed values.
     *
     * <p>The engine does not validate these claims: passing them asserts that the caller holds
     * them.</p>
     *
     * <p>This applies {@code authorization} only and never stands in for {@code authentication}:
     * a {@code null} requirement passes whatever the claims, even none. When the adapter declares
     * {@code authentication}, the embedding application must itself refuse a caller without
     * validated claims, as the authentication chain refuses a request without a token over
     * HTTP.</p>
     *
     * @param requirement the requirement that applies, or {@code null} for none: for an MCP tool,
     *        {@link io.ikanos.engine.exposes.mcp.McpServerAdapter#requirementFor}, which finds the
     *        tool by the name {@code handleToolCall} receives
     * @param claims the validated claims, by claim name, or {@code null} when the caller has none
     * @return {@code null} when the call may proceed, always so for a {@code null} requirement,
     *         else why it may not: when a requirement applies, missing claims get the denial of a
     *         request without a validated token
     */
    public static Denial check(AuthorizationSpec requirement, Map<String, Object> claims) {
        if (requirement == null) {
            return null;
        }
        if (claims == null) {
            return noToken(requirement);
        }
        JWTClaimsSet.Builder validated = new JWTClaimsSet.Builder();
        claims.forEach(validated::claim);
        return check(requirement, validated.build(), Set.of());
    }

    /** The denial of a call whose caller holds no validated token. */
    private static Denial noToken(AuthorizationSpec requirement) {
        return new Denial(requirement.getScopes(), "No validated access token");
    }

    /**
     * Checks a requirement against validated claims.
     *
     * @return {@code null} when the claims meet the requirement, else why they don't
     */
    static Denial check(AuthorizationSpec requirement, JWTClaimsSet claims) {
        return check(requirement, claims, Set.of());
    }

    /**
     * Checks a requirement against validated claims. A denial for a missing scope asks for the
     * required scopes, then for the granted ones in {@code stillRequired}; a denial for a claim
     * asks for no scope, since the token already holds every required one.
     *
     * @return {@code null} when the claims meet the requirement, else why they don't
     */
    static Denial check(AuthorizationSpec requirement, JWTClaimsSet claims,
            Collection<String> stillRequired) {
        Set<String> granted = tokenScopes(claims);
        for (String scope : requirement.getScopes()) {
            if (!granted.contains(scope)) {
                Set<String> challenged = new LinkedHashSet<>(requirement.getScopes());
                for (String held : granted) {
                    if (stillRequired.contains(held)) {
                        challenged.add(held);
                    }
                }
                return new Denial(List.copyOf(challenged),
                        "Required scope '" + scope + "' not present in token");
            }
        }
        for (Map.Entry<String, List<String>> claim : requirement.getClaims().entrySet()) {
            if (!holdsOneOf(claimValue(claims, claim.getKey()), claim.getValue())) {
                return new Denial(List.of(),
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
        // A claim of that exact name wins, even when its value is null: a dotted name falls back to
        // a nested path only when the token has no claim of that name at all.
        if (claims.getClaims().containsKey(name) || !name.contains(".")) {
            return claims.getClaim(name);
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

    /** Every scope required by at least one of the {@code declared} requirements. */
    public static Set<String> requiredScopes(Map<String, AuthorizationSpec> declared) {
        Set<String> scopes = new LinkedHashSet<>();
        declared.values().forEach(requirement -> scopes.addAll(requirement.getScopes()));
        return scopes;
    }

    /**
     * The warning for an adapter whose {@code authentication.scopes} were required on every call
     * up to 1.0.0-beta9 and are no longer required by anything (#999): it lists scopes, but
     * declares no {@code authorization} at any level, so any valid token gets through.
     *
     * @return the warning, or {@code null} when the adapter lists no scope or requires one
     */
    public static String unenforcedScopesWarning(ServerSpec adapter,
            Map<String, AuthorizationSpec> declared) {
        if (!declared.isEmpty()
                || !(adapter.getAuthentication() instanceof OAuth2AuthenticationSpec oauth2)
                || oauth2.getScopes() == null || oauth2.getScopes().isEmpty()) {
            return null;
        }
        return adapter.getType().toUpperCase(Locale.ROOT) + " adapter '"
                + adapter.getNamespace() + "' lists 'authentication.scopes' "
                + oauth2.getScopes() + " but declares no 'authorization': since 1.0.0-beta10"
                + " these scopes are no longer required, so any valid token is accepted. Declare"
                + " 'authorization: {scopes: [...]}' on the adapter to keep requiring them.";
    }

    /**
     * {@link #unenforcedScopesWarning} for every REST and MCP adapter of a document, for
     * {@code ikanos validate}. An MCP adapter on stdio is left out: it validates no token.
     */
    public static List<String> findWarnings(IkanosSpec spec) {
        List<String> warnings = new ArrayList<>();
        for (ServerSpec adapter : exposesOf(spec)) {
            String warning = null;
            if (adapter instanceof RestServerSpec rest) {
                warning = unenforcedScopesWarning(rest, declaredIn(rest));
            } else if (adapter instanceof McpServerSpec mcp && !mcp.isStdio()) {
                warning = unenforcedScopesWarning(mcp, declaredIn(mcp));
            }
            if (warning != null) {
                warnings.add(warning);
            }
        }
        return warnings;
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
     * a required scope is not one the adapter recognizes ({@code authentication.scopes}), a
     * requirement lists neither scopes nor claims, or a claim lists no allowed value. The schema and the ruleset report the same problems at lint
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
        declared.forEach((location, requirement) -> {
            if (requirement.getScopes().isEmpty() && requirement.getClaims().isEmpty()) {
                // An empty block would replace a broader requirement and let any token through.
                errors.add(location + " declares an 'authorization' that requires nothing:"
                        + " list 'scopes' or 'claims'");
            }
        });
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
     * Checks an adapter as it starts: {@link #validate} refuses an {@code authorization} that
     * cannot be enforced, and {@link #unenforcedScopesWarning} is logged as a warning, since a
     * capability written for 1.0.0-beta9 otherwise starts accepting tokens it used to refuse
     * without a word.
     *
     * @throws IllegalArgumentException as {@link #validate} does
     */
    public static void checkAtStartup(ServerSpec adapter, boolean stdio,
            Map<String, AuthorizationSpec> declared) {
        validate(adapter, stdio, declared);
        String warning = stdio ? null : unenforcedScopesWarning(adapter, declared);
        if (warning != null) {
            Context.getCurrentLogger().warning(warning);
        }
    }

    /**
     * Runs {@link #validate} on every REST and MCP adapter of a document, for
     * {@code ikanos validate}, which must refuse what loading the capability refuses (#999).
     *
     * @return the error of each adapter whose {@code authorization} blocks are invalid, empty
     *         when all are valid
     */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        for (ServerSpec adapter : exposesOf(spec)) {
            try {
                if (adapter instanceof RestServerSpec rest) {
                    validate(rest, false, declaredIn(rest));
                } else if (adapter instanceof McpServerSpec mcp) {
                    validate(mcp, mcp.isStdio(), declaredIn(mcp));
                }
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        return errors;
    }

    /** The exposed adapters of a document: its capability's, then its own. */
    private static List<ServerSpec> exposesOf(IkanosSpec spec) {
        List<ServerSpec> exposes = new ArrayList<>();
        if (spec != null && spec.getCapability() != null
                && spec.getCapability().getExposes() != null) {
            exposes.addAll(spec.getCapability().getExposes());
        }
        if (spec != null && spec.getExposes() != null) {
            exposes.addAll(spec.getExposes());
        }
        return exposes;
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
