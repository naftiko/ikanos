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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/** Unit tests for {@link AccessControl}, the {@code authorization} check of exposed adapters (#999). */
class AccessControlTest {

    private static AuthorizationSpec scopes(String... scopes) {
        return new AuthorizationSpec(List.of(scopes), null);
    }

    private static AuthorizationSpec claim(String name, String... values) {
        return new AuthorizationSpec(null, Map.of(name, List.of(values)));
    }

    // ─── Scopes ─────────────────────────────────────────────────────────────────

    @Test
    void checkShouldAcceptTokenCarryingEveryRequiredScope() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("scope", "records.read records.write admin").build();

        assertNull(AccessControl.check(scopes("records.read", "records.write"), claims));
    }

    @Test
    void checkShouldRefuseTokenMissingOneRequiredScope() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("scope", "records.read").build();

        AccessControl.Denial denial =
                AccessControl.check(scopes("records.read", "records.write"), claims);

        assertNotNull(denial);
        assertEquals(List.of("records.read", "records.write"), denial.scopes());
        assertTrue(denial.description().contains("records.write"));
    }

    @Test
    void checkShouldRefuseTokenWithoutAnyScopeClaim() {
        assertNotNull(AccessControl.check(scopes("records.read"),
                new JWTClaimsSet.Builder().subject("alice").build()));
    }

    @Test
    void tokenScopesShouldReadScpClaimAsAnArray() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("scp", List.of("records.read", "records.write")).build();

        assertEquals(Set.of("records.read", "records.write"), AccessControl.tokenScopes(claims));
    }

    @Test
    void tokenScopesShouldMergeScopeAndScpClaims() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("scope", "  records.read  ").claim("scp", "records.write").build();

        assertEquals(Set.of("records.read", "records.write"), AccessControl.tokenScopes(claims));
    }

    @Test
    void checkShouldNotTreatAScopeAsASubstringMatch() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("scope", "records.readonly").build();

        assertNotNull(AccessControl.check(scopes("records.read"), claims));
    }

    // ─── Claims ─────────────────────────────────────────────────────────────────

    @Test
    void checkShouldAcceptArrayClaimHoldingOneAllowedValue() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("roles", List.of("viewer", "editor")).build();

        assertNull(AccessControl.check(claim("roles", "editor", "admin"), claims));
    }

    @Test
    void checkShouldRefuseArrayClaimHoldingNoAllowedValue() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("roles", List.of("viewer")).build();

        AccessControl.Denial denial = AccessControl.check(claim("roles", "editor", "admin"), claims);

        assertNotNull(denial);
        assertTrue(denial.scopes().isEmpty());
        assertTrue(denial.description().contains("'roles'"));
        assertFalse(denial.description().contains("editor"),
                "The challenge must not list the allowed values");
    }

    @Test
    void checkShouldAcceptStringClaimEqualToAnAllowedValue() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("department", "finance").build();

        assertNull(AccessControl.check(claim("department", "finance"), claims));
    }

    @Test
    void checkShouldRefuseMissingClaim() {
        assertNotNull(AccessControl.check(claim("roles", "editor"),
                new JWTClaimsSet.Builder().subject("alice").build()));
    }

    @Test
    void checkShouldReadDottedNameAsPathIntoNestedClaims() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("editor"))).build();

        assertNull(AccessControl.check(claim("realm_access.roles", "editor"), claims));
    }

    @Test
    void checkShouldReadDottedNameAsIsWhenTheTokenHasThatClaim() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("https://example.com/roles", List.of("editor")).build();

        assertNull(AccessControl.check(claim("https://example.com/roles", "editor"), claims));
    }

    @Test
    void checkShouldNotMatchAnObjectClaimByItsText() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("roles", Map.of("editor", true)).build();

        assertNotNull(AccessControl.check(claim("roles", "{editor=true}"), claims));
    }

    @Test
    void checkShouldRequireEveryListedClaimAndScope() {
        AuthorizationSpec requirement = new AuthorizationSpec(List.of("records.write"),
                Map.of("roles", List.of("editor"), "tenant", List.of("acme")));
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("scope", "records.write")
                .claim("roles", List.of("editor")).claim("tenant", "other").build();

        AccessControl.Denial denial = AccessControl.check(requirement, claims);

        assertNotNull(denial);
        assertTrue(denial.description().contains("'tenant'"));
    }

    // ─── enforce ────────────────────────────────────────────────────────────────

    @Test
    void enforceShouldLetCallThroughWhenNoRequirementApplies() {
        Request request = new Request(Method.GET, "http://localhost/records");
        Response response = new Response(request);

        assertTrue(AccessControl.enforce(null, request, response));
        assertNull(response.getAttributes().get(AccessControl.DENIAL_ATTRIBUTE));
    }

    @Test
    void enforceShouldMarkResponseForbiddenWhenTokenFallsShort() {
        Request request = new Request(Method.GET, "http://localhost/records");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().claim("scope", "records.read").build());
        Response response = new Response(request);

        assertFalse(AccessControl.enforce(scopes("records.write"), request, response));
        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
        assertInstanceOf(AccessControl.Denial.class,
                response.getAttributes().get(AccessControl.DENIAL_ATTRIBUTE));
    }

    @Test
    void enforceShouldRefuseRequestWithoutValidatedClaims() {
        Request request = new Request(Method.GET, "http://localhost/records");
        Response response = new Response(request);

        assertFalse(AccessControl.enforce(claim("roles", "editor"), request, response));
        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
    }

    // ─── Inheritance ────────────────────────────────────────────────────────────

    @Test
    void effectiveShouldPreferOperationThenResourceThenAdapter() {
        RestServerSpec adapter = new RestServerSpec();
        RestServerResourceSpec resource = new RestServerResourceSpec("/records");
        RestServerOperationSpec operation = new RestServerOperationSpec();
        adapter.setAuthorization(scopes("a"));

        assertEquals(List.of("a"),
                AccessControl.effective(adapter, resource, operation).getScopes());
        resource.setAuthorization(scopes("r"));
        assertEquals(List.of("r"), AccessControl.effective(adapter, resource, null).getScopes());
        operation.setAuthorization(scopes("o"));
        assertEquals(List.of("o"),
                AccessControl.effective(adapter, resource, operation).getScopes());
    }

    @Test
    void effectiveShouldPreferToolOverMcpAdapter() {
        McpServerSpec adapter = new McpServerSpec();
        McpServerToolSpec tool = new McpServerToolSpec("update", null, null);
        adapter.setAuthorization(scopes("a"));

        assertEquals(List.of("a"), AccessControl.effective(adapter, tool).getScopes());
        tool.setAuthorization(scopes("t"));
        assertEquals(List.of("t"), AccessControl.effective(adapter, tool).getScopes());
        assertEquals(List.of("a"), AccessControl.effective(adapter, null).getScopes());
    }

    // ─── Load-time validation ───────────────────────────────────────────────────

    private static RestServerSpec restAdapter(AuthorizationSpec operationAuthorization) {
        RestServerSpec adapter = new RestServerSpec("localhost", 8080, "records");
        RestServerResourceSpec resource = new RestServerResourceSpec("/records");
        RestServerOperationSpec operation = new RestServerOperationSpec();
        operation.setMethod("PUT");
        operation.setAuthorization(operationAuthorization);
        resource.setOperations(Map.of("update", operation));
        adapter.getResources().put("records", resource);
        return adapter;
    }

    private static OAuth2AuthenticationSpec oauth2(String... scopes) {
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setAuthorizationServerUri("https://idp.example.org");
        oauth2.setResource("https://app.example.org/api");
        oauth2.setScopes(List.of(scopes));
        return oauth2;
    }

    @Test
    void validateShouldAcceptRequiredScopesListedByOAuth2() {
        RestServerSpec adapter = restAdapter(scopes("records.write"));
        adapter.setAuthentication(oauth2("records.read", "records.write"));

        assertDoesNotThrow(() -> AccessControl.validate(adapter, false,
                AccessControl.declaredIn(adapter)));
    }

    @Test
    void validateShouldRefuseAuthorizationWithoutOAuth2() {
        RestServerSpec adapter = restAdapter(scopes("records.write"));
        adapter.setAuthentication(new BearerAuthenticationSpec());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AccessControl.validate(adapter, false, AccessControl.declaredIn(adapter)));
        assertTrue(error.getMessage().contains("operation 'records.update'"), error.getMessage());
        assertTrue(error.getMessage().contains("oauth2"), error.getMessage());
    }

    @Test
    void validateShouldRefuseAuthorizationWithoutAuthentication() {
        RestServerSpec adapter = restAdapter(claim("roles", "editor"));

        assertThrows(IllegalArgumentException.class,
                () -> AccessControl.validate(adapter, false, AccessControl.declaredIn(adapter)));
    }

    @Test
    void validateShouldRefuseScopeNotListedByOAuth2() {
        RestServerSpec adapter = restAdapter(scopes("records.delete"));
        adapter.setAuthentication(oauth2("records.read"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AccessControl.validate(adapter, false, AccessControl.declaredIn(adapter)));
        assertTrue(error.getMessage().contains("'records.delete'"), error.getMessage());
    }

    @Test
    void validateShouldRefuseClaimWithoutAllowedValue() {
        RestServerSpec adapter = restAdapter(
                new AuthorizationSpec(null, Map.of("roles", List.of())));
        adapter.setAuthentication(oauth2("records.read"));

        assertThrows(IllegalArgumentException.class,
                () -> AccessControl.validate(adapter, false, AccessControl.declaredIn(adapter)));
    }

    @Test
    void validateShouldRefuseAuthorizationOnTheStdioTransport() {
        McpServerSpec adapter = new McpServerSpec();
        McpServerToolSpec tool = new McpServerToolSpec("update", null, null);
        tool.setAuthorization(claim("roles", "editor"));
        adapter.getTools().put("update", tool);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AccessControl.validate(adapter, true, AccessControl.declaredIn(adapter)));
        assertTrue(error.getMessage().contains("stdio"), error.getMessage());
    }

    @Test
    void validateShouldIgnoreAdapterWithoutAuthorization() {
        RestServerSpec adapter = restAdapter(null);
        adapter.setAuthentication(new BearerAuthenticationSpec());

        assertDoesNotThrow(() -> AccessControl.validate(adapter, false,
                AccessControl.declaredIn(adapter)));
    }
}
