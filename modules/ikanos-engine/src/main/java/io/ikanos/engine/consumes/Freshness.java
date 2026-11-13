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
package io.ikanos.engine.consumes;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How long an upstream says its answer stays fresh, read from its HTTP caching headers
 * (RFC 9111).
 *
 * @param ttlMs remaining freshness in milliseconds, zero or more, or {@code null} when the
 *        upstream gave none
 * @param privateOnly whether the upstream marked its answer {@code private}
 */
public record Freshness(Long ttlMs, boolean privateOnly) {

    /** No freshness information. */
    public static final Freshness UNKNOWN = new Freshness(null, false);

    /**
     * Read freshness from raw response header values.
     *
     * <ul>
     * <li>{@code no-store} and {@code no-cache} give 0.</li>
     * <li>The lifetime is {@code max-age}, or {@code s-maxage} when it is shorter (the result may
     * be served by a shared cache, so the more conservative one applies), else {@code Expires}
     * minus {@code Date} (or now when {@code Date} is absent). An {@code Expires} that cannot be
     * parsed, such as {@code 0}, means already expired (§5.3).</li>
     * <li>The current age is subtracted, so an answer keeps only the freshness it has left
     * (§4.2.3): the greater of {@code Age} (time spent in an intermediate cache) and the time
     * elapsed since {@code Date}.</li>
     * <li>Lifetime and age are computed in the {@code long} range; only the remaining TTL is
     * capped to the int range.</li>
     * </ul>
     *
     * @param cacheControl the {@code Cache-Control} value (several headers joined by commas), or
     *        {@code null}
     * @param expires the {@code Expires} value, or {@code null}
     * @param date the {@code Date} value, or {@code null}
     * @param age the {@code Age} value, or {@code null}
     * @param nowMillis the current time, to age the answer from {@code Date}, or to count
     *        {@code Expires} from when {@code Date} is absent
     * @return the freshness; never {@code null}
     */
    public static Freshness parse(String cacheControl, String expires, String date, String age,
            long nowMillis) {
        Long maxAge = null;
        Long sharedMaxAge = null;
        boolean privateOnly = false;
        boolean noCache = false;
        for (String directive : directives(cacheControl)) {
            String name = name(directive);
            switch (name) {
                case "no-store", "no-cache" -> noCache = true;
                case "private" -> privateOnly = true;
                case "max-age" -> maxAge = maxAge != null ? maxAge : seconds(value(directive));
                case "s-maxage" -> sharedMaxAge =
                        sharedMaxAge != null ? sharedMaxAge : seconds(value(directive));
                default -> {
                    // other directives do not change freshness
                }
            }
        }
        if (noCache) {
            return new Freshness(0L, privateOnly);
        }
        Long lifetime = maxAge;
        if (sharedMaxAge != null && (lifetime == null || sharedMaxAge < lifetime)) {
            lifetime = sharedMaxAge;
        }
        Long dateAt = date != null ? httpDate(date) : null;
        if (lifetime == null && expires != null) {
            Long expiresAt = httpDate(expires);
            long from = dateAt != null ? dateAt : nowMillis;
            lifetime = expiresAt == null || expiresAt <= from ? 0L : expiresAt - from;
        }
        if (lifetime == null) {
            return new Freshness(null, privateOnly);
        }
        // §4.2.3: the current age is at least the time since the upstream's Date, even when Age
        // is absent or smaller (a clock ahead of ours gives no negative age)
        Long ageMillis = seconds(age);
        long elapsed = dateAt != null ? Math.max(0L, nowMillis - dateAt) : 0L;
        long currentAge = Math.max(ageMillis != null ? ageMillis : 0L, elapsed);
        long remaining = Math.max(0L, lifetime - currentAge);
        return new Freshness(Math.min(remaining, Integer.MAX_VALUE), privateOnly);
    }

    /** Split a {@code Cache-Control} value on commas that are not inside a quoted string. */
    static List<String> directives(String cacheControl) {
        List<String> result = new ArrayList<>();
        if (cacheControl == null) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < cacheControl.length(); i++) {
            char c = cacheControl.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                add(result, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        add(result, current);
        return result;
    }

    private static void add(List<String> result, StringBuilder directive) {
        String trimmed = directive.toString().trim();
        if (!trimmed.isEmpty()) {
            result.add(trimmed);
        }
    }

    private static String name(String directive) {
        int equals = directive.indexOf('=');
        return (equals < 0 ? directive : directive.substring(0, equals)).trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String value(String directive) {
        int equals = directive.indexOf('=');
        return equals < 0 ? null : directive.substring(equals + 1).trim();
    }

    /**
     * {@code delta-seconds} as milliseconds, {@link Long#MAX_VALUE} when it overflows (callers cap
     * only the final TTL, so a long lifetime minus a long age keeps what it has left);
     * {@code null} if invalid.
     */
    static Long seconds(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replace("\"", "").trim();
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Math.multiplyExact(Long.parseLong(digits), 1000L);
        } catch (ArithmeticException | NumberFormatException tooLarge) {
            return Long.MAX_VALUE;
        }
    }

    private static Long httpDate(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
