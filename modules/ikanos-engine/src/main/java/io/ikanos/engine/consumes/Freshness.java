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
import java.util.Locale;

/**
 * How long an upstream says its answer stays fresh, read from its HTTP caching headers
 * (RFC 9111).
 *
 * @param ttlMs freshness in milliseconds, zero or more, or {@code null} when the upstream gave
 *        none
 * @param privateOnly whether the upstream marked its answer {@code private}
 */
public record Freshness(Long ttlMs, boolean privateOnly) {

    /** No freshness information. */
    public static final Freshness UNKNOWN = new Freshness(null, false);

    /**
     * Read freshness from response headers. {@code no-store} and {@code no-cache} mean 0;
     * {@code max-age} wins over {@code Expires}, which counts from {@code Date} (or now when
     * {@code Date} is absent). An {@code Expires} that cannot be parsed means already expired,
     * as RFC 9111 §5.3 says.
     *
     * @param cacheControl the {@code Cache-Control} value, or {@code null}
     * @param expires the {@code Expires} value, or {@code null}
     * @param date the {@code Date} value, or {@code null}
     * @param nowMillis the current time, used when {@code Date} is absent
     * @return the freshness; never {@code null}
     */
    public static Freshness parse(String cacheControl, String expires, String date,
            long nowMillis) {
        if (expires == null) {
            return of(cacheControl, null, null, nowMillis);
        }
        Long expiresAt = httpDate(expires);
        // An Expires that cannot be parsed means already expired (RFC 9111 §5.3)
        return of(cacheControl, expiresAt != null ? expiresAt : Long.MIN_VALUE,
                date != null ? httpDate(date) : null, nowMillis);
    }

    /**
     * Freshness from a {@code Cache-Control} value and already-parsed {@code Expires} and
     * {@code Date} times, as an HTTP client library hands them over.
     *
     * @param cacheControl the {@code Cache-Control} value, or {@code null}
     * @param expiresMillis the {@code Expires} time, or {@code null} when absent
     * @param dateMillis the {@code Date} time, or {@code null} when absent
     * @param nowMillis the current time, used when {@code Date} is absent
     * @return the freshness; never {@code null}
     */
    public static Freshness of(String cacheControl, Long expiresMillis, Long dateMillis,
            long nowMillis) {
        Long ttl = null;
        boolean privateOnly = false;
        boolean noCache = false;
        if (cacheControl != null) {
            for (String part : cacheControl.split(",")) {
                String directive = part.trim().toLowerCase(Locale.ROOT);
                if (directive.equals("no-store") || directive.equals("no-cache")
                        || directive.startsWith("no-cache=")) {
                    noCache = true;
                } else if (directive.equals("private") || directive.startsWith("private=")) {
                    privateOnly = true;
                } else if (directive.startsWith("max-age=") && ttl == null) {
                    ttl = seconds(directive.substring("max-age=".length()));
                }
            }
        }
        if (noCache) {
            return new Freshness(0L, privateOnly);
        }
        if (ttl == null && expiresMillis != null) {
            long from = dateMillis != null ? dateMillis : nowMillis;
            ttl = expiresMillis <= from ? 0L : Math.min(expiresMillis - from, Integer.MAX_VALUE);
        }
        return new Freshness(ttl, privateOnly);
    }

    /** {@code delta-seconds} as milliseconds, capped to the int range; {@code null} if invalid. */
    private static Long seconds(String value) {
        String digits = value.replace("\"", "").trim();
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            long millis = Math.multiplyExact(Long.parseLong(digits), 1000L);
            return Math.min(millis, Integer.MAX_VALUE);
        } catch (ArithmeticException | NumberFormatException tooLarge) {
            return (long) Integer.MAX_VALUE;
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
