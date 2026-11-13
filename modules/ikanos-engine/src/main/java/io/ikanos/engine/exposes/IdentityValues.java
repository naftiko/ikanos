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

import java.util.regex.Pattern;

/**
 * What a caller identity value may look like, wherever the engine records it or passes it on: the
 * MCP agent identity (#483) and the validated claims sent to consumed APIs (#998). A value outside
 * these rules is ignored rather than cleaned up, so it can never split a log line, a header or a
 * comma-separated list.
 */
public final class IdentityValues {

    /** Longest value accepted; longer values are dropped rather than truncated. */
    public static final int MAX_LENGTH = 256;

    /**
     * Characters a value may contain: enough for ids, emails, SPIFFE URIs and provider-prefixed
     * subjects such as Auth0's {@code google-oauth2|123}, and nothing that can split a log line, a
     * header or a list (no whitespace, quotes, commas or semicolons).
     */
    public static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9._~:@/+=|-]+");

    private IdentityValues() {}

    /**
     * @return the trimmed value, or {@code null} when blank, too long, or containing a character
     *         outside {@link #PATTERN} (control characters included).
     */
    public static String sanitize(String value) {
        if (value == null || value.trim().isEmpty() || rejectionOf(value) != null) {
            return null;
        }
        return value.trim();
    }

    /**
     * @return why {@code value} cannot be used, or {@code null} when it can, or when it is absent
     *         or blank (nothing was sent, so there is nothing to report).
     */
    public static String rejectionOf(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return "longer than " + MAX_LENGTH + " characters";
        }
        if (!trimmed.isEmpty() && !PATTERN.matcher(trimmed).matches()) {
            return "contains characters other than letters, digits and . _ ~ : @ / + = | -";
        }
        return null;
    }
}
