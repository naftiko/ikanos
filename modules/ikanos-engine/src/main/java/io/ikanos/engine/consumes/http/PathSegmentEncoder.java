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
package io.ikanos.engine.consumes.http;

import java.nio.charset.StandardCharsets;

/**
 * Percent-encodes the values substituted into a consumed URI template, so that a caller value
 * cannot change the request's target (GHSA-rvg4-2rxw-mc64). Without it, a {@code /}, {@code ..},
 * {@code ?} or {@code #} in a value would change the endpoint, add a query string or start a
 * fragment, and a {@code &} after a literal {@code ?} would add a query parameter.
 *
 * <p>In the path, characters allowed in a segment by RFC 3986 ({@code pchar}: unreserved,
 * sub-delims, {@code :} and {@code @}) are kept as they are; every other byte of the UTF-8
 * encoding is written as {@code %XX}. A {@code %} is always encoded, so an already-encoded
 * {@code %2F} cannot be decoded into a {@code /} by the upstream. Only substituted values are
 * encoded: the literal text of {@code baseUri} and of the resource {@code path} is the capability
 * author's and is sent as declared.</p>
 *
 * <p>A path segment made only of substituted values must not be empty, {@code .} or {@code ..}:
 * the upstream would resolve such a segment to another endpoint ({@code /users/../posts} is
 * {@code /posts}), and encoding the dots is not enough since {@code %2E} may be decoded back.
 * Such a URI is rejected.</p>
 */
final class PathSegmentEncoder {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** Marks the start and end of a substituted value in a rendered URI template. */
    static final char VALUE_START = '\u0002';
    static final char VALUE_END = '\u0003';

    private PathSegmentEncoder() {
    }

    /**
     * Wraps a rendered value in markers, to be passed as the {@code valueEncoder} of
     * {@code Resolver.resolveMustacheTemplate}. Markers already present in the value are dropped,
     * so a caller cannot forge one.
     */
    static String mark(String value) {
        String clean = value.replace(String.valueOf(VALUE_START), "")
                .replace(String.valueOf(VALUE_END), "");
        return VALUE_START + clean + VALUE_END;
    }

    /**
     * Encodes every marked value of a rendered URI by position: a value before the first literal
     * {@code ?} is encoded as a path segment, a value after it as a query component (which also
     * encodes {@code &}, {@code =}, {@code +} and {@code ;}, so it cannot add a parameter).
     * Literal template text is kept as is.
     *
     * @throws IllegalArgumentException when a path segment made only of substituted values is
     *         empty, {@code .} or {@code ..}
     */
    static String encodeMarkedValues(String rendered) {
        if (rendered == null || rendered.indexOf(VALUE_START) < 0) {
            return rendered;
        }
        StringBuilder out = new StringBuilder(rendered.length() + 16);
        boolean inQuery = false;
        int segmentStart = 0;
        boolean segmentHasLiteral = false;
        boolean segmentHasValue = false;
        int i = 0;
        while (i < rendered.length()) {
            char c = rendered.charAt(i);
            if (c == VALUE_START) {
                int end = rendered.indexOf(VALUE_END, i + 1);
                if (end < 0) {
                    end = rendered.length();
                }
                String value = rendered.substring(i + 1, end);
                out.append(inQuery ? encodeQueryComponent(value) : encode(value));
                segmentHasValue = true;
                i = end + 1;
            } else {
                if (!inQuery && (c == '/' || c == '?')) {
                    requireSafeSegment(out, segmentStart, segmentHasLiteral, segmentHasValue);
                    segmentStart = out.length() + 1;
                    segmentHasLiteral = false;
                    segmentHasValue = false;
                } else if (c != VALUE_END) {
                    segmentHasLiteral = true;
                }
                if (c == '?') {
                    inQuery = true;
                }
                if (c != VALUE_END) {
                    out.append(c);
                }
                i++;
            }
        }
        if (!inQuery) {
            requireSafeSegment(out, segmentStart, segmentHasLiteral, segmentHasValue);
        }
        return out.toString();
    }

    /**
     * Rejects a path segment that substituted values alone turned into a dot-segment or an empty
     * segment. A segment with literal text is the capability author's and is left alone.
     */
    private static void requireSafeSegment(StringBuilder out, int segmentStart,
            boolean segmentHasLiteral, boolean segmentHasValue) {
        if (!segmentHasValue || segmentHasLiteral) {
            return;
        }
        String segment = out.substring(segmentStart);
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException(
                    "A path parameter value must not be empty, '.' or '..'");
        }
    }

    static String encode(String value) {
        return percentEncode(value, false);
    }

    static String encodeQueryComponent(String value) {
        return percentEncode(value, true);
    }

    private static String percentEncode(String value, boolean query) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder out = null;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (query ? isQueryValueChar(b) : isPathChar(b)) {
                if (out != null) {
                    out.append((char) b);
                }
            } else {
                if (out == null) {
                    out = new StringBuilder(bytes.length + 16);
                    out.append(value, 0, i);
                }
                out.append('%').append(HEX[b >> 4]).append(HEX[b & 0x0F]);
            }
        }
        return out == null ? value : out.toString();
    }

    /** RFC 3986 {@code pchar} minus {@code pct-encoded}: unreserved, sub-delims, ':' and '@'. */
    static boolean isPathChar(int b) {
        return isUnreserved(b) || "!$&'()*+,;=:@".indexOf(b) >= 0;
    }

    /** A query value character that cannot end the value or start another parameter. */
    static boolean isQueryValueChar(int b) {
        return isUnreserved(b) || "!$'()*,:@/?".indexOf(b) >= 0;
    }

    private static boolean isUnreserved(int b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                || b == '-' || b == '.' || b == '_' || b == '~';
    }
}
