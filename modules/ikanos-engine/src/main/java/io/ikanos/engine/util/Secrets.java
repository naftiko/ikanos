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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * The one place credentials are rendered and compared, on the exposed side (bearer, apikey,
 * basic and digest) and the consumed side alike. Each side used to keep its own copy, and the
 * copies drifted (#482, #654, #844).
 */
public final class Secrets {

    private Secrets() {}

    /**
     * Renders a credential template, such as {@code "sk-{{API_KEY}}"}, with the given values.
     *
     * @param template the credential template, may be null
     * @param values the bindings, or on the consumed side the call parameters merged with them
     * @return the rendered credential, or {@code null} if {@code template} is null
     * @see Resolver#resolveMustacheTemplate(String, Map)
     */
    public static String resolve(String template, Map<String, Object> values) {
        return Resolver.resolveMustacheTemplate(template, values);
    }

    /**
     * The text of a basic or digest password. The schema doesn't require one, and an omitted
     * password is an empty one (#699): an exposed adapter then accepts an empty password, and a
     * consumed adapter sends one. A declared password that renders blank, even in part, stops an
     * exposed adapter from starting.
     *
     * @param password the password from the spec, may be null
     * @return its text, empty if {@code password} is null
     */
    public static String password(char[] password) {
        return password == null ? "" : new String(password);
    }

    /**
     * Compares a credential in constant time, so the time taken doesn't tell how much of a guess
     * was right.
     *
     * @param expected the credential the adapter expects
     * @param actual the credential the request carries
     * @return {@code true} when both are non-null and equal
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Same as {@link #matches(String, String)}, for a password Restlet hands over as characters.
     */
    public static boolean matches(char[] expected, char[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return matches(new String(expected), new String(actual));
    }
}
