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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.util.CallerRoot;
import io.ikanos.engine.util.Resolver;

/** Unit tests for {@link CallerClaims}, the {@code {{auth.claims...}}} template root (#998). */
class CallerClaimsTest {

    private static Map<String, Object> rootOf(JWTClaimsSet claims) {
        Request request = new Request(Method.GET, "http://localhost/records");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE, claims);
        return CallerClaims.of(request);
    }

    @Test
    void ofShouldReturnNullWithoutAValidatedToken() {
        assertNull(CallerClaims.of(new Request(Method.GET, "http://localhost/records")));
    }

    @Test
    void ofShouldKeepScalarClaimsAsText() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder().subject("alice")
                .claim("level", 3).claim("admin", true).build());

        assertEquals("alice", CallerClaims.claim(root, "sub"));
        assertEquals("3", CallerClaims.claim(root, "level"));
        assertEquals("true", CallerClaims.claim(root, "admin"));
    }

    @Test
    void ofShouldRenderDateClaimsAsEpochSeconds() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .expirationTime(new java.util.Date(1_800_000_000_000L)).build());

        assertEquals("1800000000", CallerClaims.claim(root, "exp"));
    }

    @Test
    void putIntoShouldRemoveDottedKeysThatWouldShadowTheRoot() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("auth.claims.sub", "mallory");

        CallerClaims.putInto(parameters, rootOf(new JWTClaimsSet.Builder().subject("a").build()));

        assertFalse(parameters.containsKey("auth.claims.sub"));
    }

    @Test
    void ofShouldJoinArrayClaimsWithCommas() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .claim("roles", List.of("editor", "admin")).build());

        assertEquals("editor,admin", CallerClaims.claim(root, "roles"));
    }

    @Test
    void ofShouldLeaveOutAValueThatCouldSplitAHeader() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .subject("alice\r\nX-Admin: true").claim("name", "Alice Smith").build());

        assertNull(CallerClaims.claim(root, "sub"));
        assertNull(CallerClaims.claim(root, "name"));
    }

    @Test
    void ofShouldLeaveOutAnArrayWithOneUnsafeElement() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .claim("roles", List.of("editor", "admin,root")).build());

        assertNull(CallerClaims.claim(root, "roles"),
                "a comma inside an element would forge another role once joined");
    }

    @Test
    void ofShouldLeaveOutAValueLongerThanTheIdentityLimit() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .subject("a".repeat(IdentityValues.MAX_LENGTH + 1)).build());

        assertNull(CallerClaims.claim(root, "sub"));
    }

    @Test
    void claimShouldReadADottedNameAsAPathIntoNestedClaims() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("editor"))).build());

        assertEquals("editor", CallerClaims.claim(root, "realm_access.roles"));
    }

    @Test
    void claimShouldReadANamespacedClaimAsIs() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder()
                .claim("https://example.com/tenant", "acme").build());

        assertEquals("acme", CallerClaims.claim(root, "https://example.com/tenant"));
    }

    @Test
    void putIntoShouldAddTheRootAfterTheInputs() {
        Map<String, Object> parameters = new HashMap<>(Map.of("id", "42"));
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder().subject("alice").build());
        assertInstanceOf(CallerRoot.class, root);

        CallerClaims.putInto(parameters, root);

        assertSame(root, parameters.get(Resolver.CALLER_ROOT));
    }

    @Test
    void putIntoShouldKeepAnInputNamedAuthAndLeaveTheClaimsUnreadable() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(Resolver.CALLER_ROOT, Map.of("claims", Map.of("sub", "mallory")));

        CallerClaims.putInto(parameters,
                rootOf(new JWTClaimsSet.Builder().subject("alice").build()));

        assertEquals(Map.of("claims", Map.of("sub", "mallory")),
                parameters.get(Resolver.CALLER_ROOT), "an input named auth keeps working");
        assertThrows(IllegalArgumentException.class,
                () -> Resolver.resolveMustacheTemplate("{{auth.claims.sub}}", parameters),
                "#998: a client value must never be read as the caller's claims");
    }

    @Test
    void ofShouldKeepABooleanClaimABoolean() {
        Map<String, Object> parameters = new HashMap<>();
        CallerClaims.putInto(parameters,
                rootOf(new JWTClaimsSet.Builder().claim("admin", false).build()));

        assertEquals(Boolean.FALSE,
                ((Map<?, ?>) ((Map<?, ?>) parameters.get(Resolver.CALLER_ROOT)).get("claims"))
                        .get("admin"));
        assertEquals("false", Resolver.resolveMustacheTemplate("{{auth.claims.admin}}",
                parameters));
    }

    @Test
    void templatesShouldRenderClaimsFromTheRoot() {
        Map<String, Object> parameters = new HashMap<>();
        CallerClaims.putInto(parameters, rootOf(new JWTClaimsSet.Builder().subject("alice")
                .claim("roles", List.of("editor", "admin")).build()));

        assertEquals("user=alice;roles=editor,admin", Resolver.resolveMustacheTemplate(
                "user={{auth.claims.sub}};roles={{auth.claims.roles}}", parameters));
    }

    @Test
    void runAsShouldSetTheThreadRootOnlyWhileTheWorkRuns() {
        Map<String, Object> root = rootOf(new JWTClaimsSet.Builder().subject("alice").build());

        assertSame(root, CallerClaims.runAs(root, CallerClaims::current));
        assertNull(CallerClaims.current());
    }

}
