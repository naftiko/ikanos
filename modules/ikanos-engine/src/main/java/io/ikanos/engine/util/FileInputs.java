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

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;

/**
 * File inputs ({@code type: file}, experimental): the checks a file argument passes before the
 * tool or flow that takes it runs, the same for the MCP and the REST adapters.
 *
 * <p>A file argument is an object shaped like the {@code FileInput} of the draft MCP files
 * extension: {@code id}, {@code name}, {@code mimeType}, {@code size}, an optional {@code digest}
 * ({@code sha-256}, base64url without padding) and the bytes in base64 in {@code data}. Only
 * inline bytes are accepted: a non-empty file without {@code data} is refused, since out-of-band
 * uploads are not supported yet. The decoded size must equal {@code size} and stay within the
 * input's {@code maxSize} and, for all the files of one call, the adapter's limit; the media type
 * (or the name, for a dot-prefixed extension) must match {@code accept}; a {@code digest} is
 * checked against the bytes.</p>
 *
 * <p>Templates and steps then see the file as an object with {@code id}, {@code name},
 * {@code mimeType}, {@code size} and {@code data}: {@code {{report.data}}} gives its bytes in
 * base64, which a {@code multipartForm} part with {@code encoding: base64} or a {@code binary}
 * body sends as bytes. No message ever echoes the file's content.</p>
 */
public final class FileInputs {

    /** The longest {@code id}, as the draft sets it. */
    static final int MAX_ID_LENGTH = 128;

    private FileInputs() {
    }

    /** A file argument that is refused: the call fails without running the tool or flow. */
    public static final class InvalidFileException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        InvalidFileException(String message) {
            super(message);
        }
    }

    /** Whether any of {@code inputs} takes a file. */
    public static boolean takesFiles(Collection<InputParameterSpec> inputs) {
        if (inputs != null) {
            for (InputParameterSpec input : inputs) {
                if (input != null && input.isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check the file arguments of a call and give each one its checked form.
     *
     * @param inputs the declared inputs of the tool or flow, may be null
     * @param arguments the call arguments, may be null
     * @param maxTotalBytes the largest decoded size of all the files of the call together
     * @return {@code arguments} itself when no input takes a file, otherwise a new map where each
     *         file argument is its checked form
     * @throws InvalidFileException when a file argument is refused; the message names the input
     *         but never its content
     */
    public static Map<String, Object> check(Collection<InputParameterSpec> inputs,
            Map<String, Object> arguments, long maxTotalBytes) {
        if (arguments == null || !takesFiles(inputs)) {
            return arguments;
        }
        Map<String, Object> checked = new HashMap<>(arguments);
        Set<String> ids = new HashSet<>();
        long total = 0;
        for (InputParameterSpec input : inputs) {
            if (input == null || !input.isFile() || checked.get(input.getName()) == null) {
                continue;
            }
            CheckedFile file = checkOne(input, checked.get(input.getName()),
                    maxTotalBytes - total);
            if (!ids.add(file.id())) {
                throw refused(input, "has the same 'id' as another file of the call");
            }
            total += file.size();
            checked.put(input.getName(), file.asArgument());
        }
        return checked;
    }

    /**
     * Check one file argument.
     *
     * @param remaining the bytes the call may still take, for all its files
     */
    static CheckedFile checkOne(InputParameterSpec input, Object value, long remaining) {
        Map<?, ?> file = asObject(input, value);
        String id = text(file.get("id"));
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            throw refused(input, "needs an 'id' of 1 to " + MAX_ID_LENGTH + " characters");
        }
        String mimeType = text(file.get("mimeType"));
        if (mimeType == null || mimeType.isBlank()) {
            throw refused(input, "needs a 'mimeType'");
        }
        long size = size(input, file.get("size"));
        Object rawName = file.get("name");
        if (rawName != null && !(rawName instanceof String)) {
            throw refused(input, "has a 'name' that is not a string");
        }
        String name = (String) rawName;

        long maxSize = input.getMaxSize() != null ? BinarySize.parse(input.getMaxSize())
                : Long.MAX_VALUE;
        if (size > maxSize) {
            throw refused(input, "is larger than its maxSize of " + maxSize + " bytes");
        }
        if (size > remaining) {
            throw refused(input, "goes over the limit of the bytes one call may send");
        }
        if (!accepts(input, mimeType, name)) {
            throw refused(input, "is of a type it does not accept");
        }

        Object rawData = file.get("data");
        if (rawData != null && !(rawData instanceof String)) {
            throw refused(input, "has a 'data' that is not a base64 string");
        }
        String data = (String) rawData;
        byte[] bytes;
        if (data == null || data.isEmpty()) {
            if (size > 0) {
                throw refused(input, "has no inline 'data': out-of-band uploads are not "
                        + "supported, so the file must be sent inline");
            }
            bytes = new byte[0];
        } else {
            // Checked before decoding, so an oversized value is never decoded
            if ((long) data.length() / 4 * 3 > size + 2) {
                throw refused(input, "has more bytes in 'data' than its 'size'");
            }
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                throw refused(input, "has a 'data' that is not valid base64");
            }
            if (bytes.length != size) {
                throw refused(input, "has a 'size' that is not the size of its 'data'");
            }
        }
        Map<String, Object> digest = checkDigest(input, file.get("digest"), bytes);
        return new CheckedFile(id, name, mimeType, size, digest,
                Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * Whether a file of {@code mimeType} named {@code name} matches the input's {@code accept}:
     * a media type ({@code application/pdf}), a range such as {@code image/*}, or a dot-prefixed
     * extension of the name ({@code .csv}). Parameters and case are ignored. An input without
     * {@code accept} takes any file.
     */
    static boolean accepts(InputParameterSpec input, String mimeType, String name) {
        if (input.getAccept().isEmpty()) {
            return true;
        }
        String type = essence(mimeType);
        String lowerName = name != null ? name.toLowerCase(Locale.ROOT) : null;
        for (String accepted : input.getAccept()) {
            String pattern = accepted.trim().toLowerCase(Locale.ROOT);
            if (pattern.startsWith(".")) {
                if (lowerName != null && lowerName.endsWith(pattern)) {
                    return true;
                }
            } else if ("*/*".equals(pattern)) {
                return true;
            } else if (pattern.endsWith("/*")) {
                if (type.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (essence(pattern).equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static String essence(String mediaType) {
        int semicolon = mediaType.indexOf(';');
        return (semicolon >= 0 ? mediaType.substring(0, semicolon) : mediaType).trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Check a {@code digest}, when the file has one: a SHA-256 over the bytes, in base64url
     * without padding. A file without one is accepted.
     *
     * @return the digest to keep, or {@code null}
     */
    private static Map<String, Object> checkDigest(InputParameterSpec input, Object raw,
            byte[] bytes) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> digest)) {
            throw refused(input, "has a 'digest' that is not an object");
        }
        if (!"sha-256".equals(digest.get("algorithm"))) {
            throw refused(input, "has a 'digest' whose algorithm is not sha-256");
        }
        String value = text(digest.get("value"));
        byte[] expected;
        try {
            expected = value != null ? Base64.getUrlDecoder().decode(value) : null;
        } catch (IllegalArgumentException e) {
            expected = null;
        }
        if (expected == null || !Arrays.equals(expected, sha256(bytes))) {
            throw refused(input, "does not match its 'digest'");
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("algorithm", "sha-256");
        kept.put("value", value);
        return kept;
    }

    /** The {@code digest} value of {@code bytes}: SHA-256, base64url without padding. */
    public static String digestOf(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(bytes));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Map<?, ?> asObject(InputParameterSpec input, Object value) {
        Object plain = value instanceof JsonNode node
                ? IkanosMappers.json().convertValue(node, Object.class) : value;
        if (!(plain instanceof Map<?, ?> map)) {
            throw refused(input, "must be a file object with 'id', 'mimeType' and 'size'");
        }
        return map;
    }

    private static long size(InputParameterSpec input, Object raw) {
        if (raw instanceof Number number) {
            try {
                long size = new BigDecimal(number.toString()).longValueExact();
                if (size >= 0) {
                    return size;
                }
            } catch (ArithmeticException | NumberFormatException e) {
                // refused below
            }
        }
        throw refused(input, "needs a 'size' that is a non-negative integer");
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private static InvalidFileException refused(InputParameterSpec input, String reason) {
        return new InvalidFileException("File input '" + input.getName() + "' " + reason);
    }

    /** A file argument that passed the checks, its bytes in base64 in {@code data}. */
    record CheckedFile(String id, String name, String mimeType, long size,
            Map<String, Object> digest, String data) {

        /** The file as templates and steps see it. */
        Map<String, Object> asArgument() {
            Map<String, Object> argument = new LinkedHashMap<>();
            argument.put("id", id);
            if (name != null) {
                argument.put("name", name);
            }
            argument.put("mimeType", mimeType);
            argument.put("size", size);
            if (digest != null) {
                argument.put("digest", digest);
            }
            argument.put("data", data);
            return argument;
        }
    }
}
