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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 4 of the Sonar bug remediation blueprint — verifies that the credential fields
 * on {@link BasicAuthenticationSpec} and {@link DigestAuthenticationSpec} are migrated
 * away from {@code volatile} (Sonar {@code java:S3077}) and that the {@code char[]}
 * password is defensively copied on both set and get to prevent silent in-place mutation
 * of stored credentials by callers.
 *
 * <p>The defensive-copy assertions are not just a hygiene concern: without them, a caller
 * doing {@code spec.getPassword()[0] = 'x'} silently rewrites the stored credential —
 * which is exactly what the blueprint's "atomic password storage" pattern guards against.
 */
class AuthenticationSpecThreadSafetyTest {

    private static Stream<Class<?>> phase4AuthClasses() {
        return Stream.of(BasicAuthenticationSpec.class, DigestAuthenticationSpec.class);
    }

    @ParameterizedTest(name = "{0} should declare no volatile fields")
    @MethodSource("phase4AuthClasses")
    @DisplayName("Phase 4 authentication specs must not use volatile (S3077)")
    void authSpecShouldNotDeclareVolatileFields(Class<?> authClass) {
        List<String> volatileFields = new ArrayList<>();
        for (Field field : authClass.getDeclaredFields()) {
            if (Modifier.isVolatile(field.getModifiers())) {
                volatileFields.add(field.getName() + " : " + field.getType().getSimpleName());
            }
        }
        assertEquals(
                List.of(),
                volatileFields,
                () -> authClass.getSimpleName()
                        + " still declares volatile fields (S3077). "
                        + "Migrate each one to AtomicReference<T>; the char[] password must "
                        + "be defensively cloned on set and get. "
                        + "See sonar-bug-remediation.md, Phase 4.");
    }

    @Test
    @DisplayName("BasicAuth setPassword should defensively clone the input array")
    void basicAuthSetPasswordShouldDefensivelyCloneInput() {
        BasicAuthenticationSpec spec = new BasicAuthenticationSpec();
        char[] caller = {'s', 'e', 'c', 'r', 'e', 't'};
        spec.setPassword(caller);
        caller[0] = 'X';
        assertArrayEquals(new char[]{'s', 'e', 'c', 'r', 'e', 't'}, spec.getPassword(),
                "setPassword must clone — caller mutation must not affect the stored value");
    }

    @Test
    @DisplayName("BasicAuth getPassword should defensively clone the stored array")
    void basicAuthGetPasswordShouldDefensivelyCloneOutput() {
        BasicAuthenticationSpec spec = new BasicAuthenticationSpec();
        spec.setPassword(new char[]{'s', 'e', 'c', 'r', 'e', 't'});
        char[] firstRead = spec.getPassword();
        char[] secondRead = spec.getPassword();
        assertNotSame(firstRead, secondRead,
                "getPassword must return a fresh copy each call");
        firstRead[0] = 'X';
        assertArrayEquals(new char[]{'s', 'e', 'c', 'r', 'e', 't'}, spec.getPassword(),
                "getPassword must clone — caller mutation must not affect the stored value");
    }

    @Test
    @DisplayName("BasicAuth setPassword(null) should be supported and read back as null")
    void basicAuthSetPasswordShouldAcceptNull() {
        BasicAuthenticationSpec spec = new BasicAuthenticationSpec("user",
                new char[]{'p', 'w'});
        spec.setPassword(null);
        assertNull(spec.getPassword());
    }

    @Test
    @DisplayName("BasicAuth setUsername should round-trip the value")
    void basicAuthSetUsernameShouldRoundTrip() {
        BasicAuthenticationSpec spec = new BasicAuthenticationSpec();
        spec.setUsername("alice");
        assertEquals("alice", spec.getUsername());
    }

    @Test
    @DisplayName("DigestAuth setPassword should defensively clone the input array")
    void digestAuthSetPasswordShouldDefensivelyCloneInput() {
        DigestAuthenticationSpec spec = new DigestAuthenticationSpec();
        char[] caller = {'s', 'e', 'c', 'r', 'e', 't'};
        spec.setPassword(caller);
        caller[0] = 'X';
        assertArrayEquals(new char[]{'s', 'e', 'c', 'r', 'e', 't'}, spec.getPassword(),
                "setPassword must clone — caller mutation must not affect the stored value");
    }

    @Test
    @DisplayName("DigestAuth getPassword should defensively clone the stored array")
    void digestAuthGetPasswordShouldDefensivelyCloneOutput() {
        DigestAuthenticationSpec spec = new DigestAuthenticationSpec();
        spec.setPassword(new char[]{'s', 'e', 'c', 'r', 'e', 't'});
        char[] firstRead = spec.getPassword();
        char[] secondRead = spec.getPassword();
        assertNotSame(firstRead, secondRead,
                "getPassword must return a fresh copy each call");
        firstRead[0] = 'X';
        assertArrayEquals(new char[]{'s', 'e', 'c', 'r', 'e', 't'}, spec.getPassword(),
                "getPassword must clone — caller mutation must not affect the stored value");
    }

    @Test
    @DisplayName("DigestAuth setPassword(null) should be supported and read back as null")
    void digestAuthSetPasswordShouldAcceptNull() {
        DigestAuthenticationSpec spec = new DigestAuthenticationSpec("user",
                new char[]{'p', 'w'});
        spec.setPassword(null);
        assertNull(spec.getPassword());
    }
}
