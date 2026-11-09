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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #844: the helper both sides now render and compare credentials with.
 */
class SecretsTest {

    @Test
    void resolveShouldRenderBoundVariables() {
        assertEquals("sk-s3cr3t", Secrets.resolve("sk-{{API_KEY}}", Map.of("API_KEY", "s3cr3t")));
    }

    @Test
    void resolveShouldKeepANullTemplateNull() {
        assertNull(Secrets.resolve(null, Map.of("API_KEY", "s3cr3t")));
    }

    @Test
    void resolveShouldRenderLikeTheResolver() {
        // The behaviour each caller had before, including a missing variable and no bindings
        for (Map<String, Object> values : List.<Map<String, Object>>of(Map.of(),
                Map.of("OTHER", "x"), Map.of("API_KEY", ""))) {
            assertEquals(Resolver.resolveMustacheTemplate("sk-{{API_KEY}}", values),
                    Secrets.resolve("sk-{{API_KEY}}", values), values.toString());
        }
        assertEquals(Resolver.resolveMustacheTemplate("sk-{{API_KEY}}", null),
                Secrets.resolve("sk-{{API_KEY}}", null));
    }

    @Test
    void passwordShouldTreatAnOmittedPasswordAsEmpty() {
        assertEquals("", Secrets.password(null));
        assertEquals("", Secrets.password(new char[0]));
        assertEquals("pa55word", Secrets.password("pa55word".toCharArray()));
    }

    @ParameterizedTest(name = "''{0}'' vs ''{1}'' -> {2}")
    @CsvSource(delimiter = '|', value = {
            "s3cr3t | s3cr3t | true",
            "s3cr3t | s3cr3 | false",
            "s3cr3t | s3cr3t! | false",
            "s3cr3t | S3CR3T | false",
            "jéton-密钥 | jéton-密钥 | true",
            "jéton-密钥 | jeton-密钥 | false",
            "'' | '' | true",
            "'' | x | false"})
    void matchesShouldCompareExactly(String expected, String actual, boolean equal) {
        assertEquals(equal, Secrets.matches(expected, actual));
        assertEquals(equal, Secrets.matches(expected.toCharArray(), actual.toCharArray()));
    }

    @Test
    void matchesShouldRejectAMissingSide() {
        assertFalse(Secrets.matches((String) null, "s3cr3t"));
        assertFalse(Secrets.matches("s3cr3t", (String) null));
        assertFalse(Secrets.matches((String) null, (String) null));
        assertFalse(Secrets.matches((char[]) null, "s3cr3t".toCharArray()));
        assertFalse(Secrets.matches("s3cr3t".toCharArray(), (char[]) null));
        assertTrue(Secrets.matches(new char[0], new char[0]));
    }

    @Test
    void onlySecretsShouldCompareCredentials() throws Exception {
        // A private copy is how the two sides drifted before; the engine has one comparison
        Path main = Path.of("src/main/java/io/ikanos");
        List<String> found;
        try (Stream<Path> files = Files.walk(main)) {
            found = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> read(file).contains("MessageDigest.isEqual"))
                    .map(file -> main.relativize(file).toString().replace('\\', '/'))
                    .toList();
        }

        assertEquals(List.of("engine/util/Secrets.java"), found);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
