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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser and formatter for {@code maxBinarySize}-style sized strings.
 *
 * <p>Implements the binary-content blueprint's {@code maxBinarySize} grammar
 * ({@code blueprints/capability-binary-content.md} §4.7 / §5.1). A size is a decimal magnitude
 * with an optional IEC binary unit suffix:</p>
 *
 * <pre>
 *   ^\d+(\.\d+)?(B|KiB|MiB|GiB)?$
 * </pre>
 *
 * <ul>
 *   <li>{@code B}   &mdash; bytes (also the implicit unit when none is given)</li>
 *   <li>{@code KiB} &mdash; 1024 bytes</li>
 *   <li>{@code MiB} &mdash; 1024<sup>2</sup> bytes</li>
 *   <li>{@code GiB} &mdash; 1024<sup>3</sup> bytes</li>
 * </ul>
 *
 * <p>Examples: {@code "10MiB"} &rarr; {@code 10485760}, {@code "512KiB"} &rarr; {@code 524288},
 * {@code "1.5MiB"} &rarr; {@code 1572864}, {@code "4096"} &rarr; {@code 4096}.</p>
 *
 * <p>The engine default cap is {@value #DEFAULT_MAX_BINARY_SIZE_BYTES} bytes (10&nbsp;MiB).</p>
 */
public final class BinarySize {

    /** Engine default {@code maxBinarySize} when none is declared: 10&nbsp;MiB. */
    public static final long DEFAULT_MAX_BINARY_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Pattern PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)(B|KiB|MiB|GiB)?$");

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * 1024 * 1024;

    private BinarySize() {
        // utility class
    }

    /**
     * Parse a sized string into a byte count.
     *
     * @param size the sized string (e.g. {@code "10MiB"}); may be {@code null} or blank
     * @return the number of bytes, or {@link #DEFAULT_MAX_BINARY_SIZE_BYTES} when {@code size} is
     *         {@code null} or blank
     * @throws IllegalArgumentException if {@code size} is non-blank but does not match the grammar
     */
    public static long parseOrDefault(String size) {
        if (size == null || size.isBlank()) {
            return DEFAULT_MAX_BINARY_SIZE_BYTES;
        }
        return parse(size);
    }

    /**
     * Parse a sized string into a byte count.
     *
     * @param size the sized string (e.g. {@code "512KiB"}); must be non-{@code null} and non-blank
     * @return the number of bytes (rounded down for fractional magnitudes)
     * @throws IllegalArgumentException if {@code size} is {@code null}, blank, or malformed
     */
    public static long parse(String size) {
        if (size == null || size.isBlank()) {
            throw new IllegalArgumentException("maxBinarySize must not be null or blank");
        }

        Matcher m = PATTERN.matcher(size.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Invalid maxBinarySize '" + size + "': expected a number optionally suffixed "
                            + "with B, KiB, MiB, or GiB (e.g. '512KiB', '10MiB', '1GiB')");
        }

        double magnitude = Double.parseDouble(m.group(1));
        String unit = m.group(2);

        long multiplier;
        if (unit == null || "B".equals(unit)) {
            multiplier = 1L;
        } else if ("KiB".equals(unit)) {
            multiplier = KIB;
        } else if ("MiB".equals(unit)) {
            multiplier = MIB;
        } else {
            multiplier = GIB;
        }

        return (long) (magnitude * multiplier);
    }
}
