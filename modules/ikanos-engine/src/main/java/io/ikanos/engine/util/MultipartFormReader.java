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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a {@code multipart/form-data} request body (RFC 7578) as the JSON object the REST adapter
 * reads its body inputs from: an operation that takes a file ({@code type: file}) accepts its
 * inputs as form parts.
 *
 * <p>A part without a {@code filename} is a text member, its value decoded as UTF-8. A part with
 * one is a file, read as the file object of a file input: {@code id} (the part name),
 * {@code name} (the file name), {@code mimeType} (the part's {@code Content-Type}, else
 * {@code application/octet-stream}), {@code size} and the bytes in base64 in {@code data}. When
 * two parts have the same name, the last one wins.</p>
 */
public final class MultipartFormReader {

    /** Room for the headers and text parts of a form, beyond its files. */
    static final long FORM_OVERHEAD_BYTES = 64L * 1024;

    private MultipartFormReader() {
    }

    /** Whether {@code entity} is a {@code multipart/form-data} body. */
    public static boolean isMultipartForm(Representation entity) {
        return entity != null && entity.getMediaType() != null
                && MediaType.MULTIPART_FORM_DATA.equals(entity.getMediaType(), true);
    }

    /**
     * Read a {@code multipart/form-data} body.
     *
     * @param entity the request body
     * @param maxFileBytes the largest total size of its files; the whole body may take
     *        {@link #FORM_OVERHEAD_BYTES} more
     * @return the members, keyed by part name
     * @throws IllegalArgumentException when the body is larger than allowed, has no boundary or
     *         is not a well-formed form; the message never echoes the body
     */
    public static ObjectNode read(Representation entity, long maxFileBytes) {
        String boundary = entity.getMediaType() != null
                ? entity.getMediaType().getParameters().getFirstValue("boundary", true)
                : null;
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("The multipart/form-data body has no boundary");
        }
        long limit = maxFileBytes + FORM_OVERHEAD_BYTES;
        byte[] body;
        try (InputStream in = entity.getStream()) {
            body = in != null ? in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1))
                    : new byte[0];
        } catch (IOException e) {
            throw new IllegalArgumentException("The multipart/form-data body cannot be read", e);
        }
        if (body.length > limit) {
            throw new IllegalArgumentException(
                    "The multipart/form-data body is larger than the " + limit + " bytes allowed");
        }
        return parse(body, boundary);
    }

    /** Parse the parts of a body delimited by {@code boundary}. */
    static ObjectNode parse(byte[] body, String boundary) {
        ObjectNode root = IkanosMappers.json().createObjectNode();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int position = indexOf(body, delimiter, 0);
        if (position < 0) {
            throw malformed();
        }
        position += delimiter.length;
        while (true) {
            if (startsWith(body, position, "--")) {
                return root;
            }
            if (!startsWith(body, position, "\r\n")) {
                throw malformed();
            }
            int headersStart = position + 2;
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                    headersStart);
            if (headersEnd < 0) {
                throw malformed();
            }
            Map<String, String> headers = headers(
                    new String(body, headersStart, headersEnd - headersStart,
                            StandardCharsets.UTF_8));
            int contentStart = headersEnd + 4;
            int contentEnd = indexOf(body, nextDelimiter, contentStart);
            if (contentEnd < 0) {
                throw malformed();
            }
            byte[] content = Arrays.copyOfRange(body, contentStart, contentEnd);
            addPart(root, headers, content);
            position = contentEnd + nextDelimiter.length;
        }
    }

    private static void addPart(ObjectNode root, Map<String, String> headers, byte[] content) {
        Map<String, String> disposition = dispositionParameters(
                headers.getOrDefault("content-disposition", ""));
        String name = disposition.get("name");
        if (name == null) {
            throw malformed();
        }
        String filename = disposition.get("filename");
        if (filename == null) {
            root.put(name, new String(content, StandardCharsets.UTF_8));
            return;
        }
        String contentType = headers.get("content-type");
        ObjectNode file = root.putObject(name);
        file.put("id", name);
        file.put("name", filename);
        file.put("mimeType", contentType != null && !contentType.isBlank() ? contentType.trim()
                : "application/octet-stream");
        file.put("size", content.length);
        file.put("data", Base64.getEncoder().encodeToString(content));
    }

    /** The headers of a part, keyed by lower-case name. */
    private static Map<String, String> headers(String block) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : block.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /**
     * The parameters of a {@code Content-Disposition: form-data} header, such as {@code name} and
     * {@code filename}, unquoted. Browsers percent-encode {@code "}, CR and LF in a quoted value
     * (WHATWG HTML) and those are decoded; a backslash escapes the next character.
     */
    static Map<String, String> dispositionParameters(String header) {
        Map<String, String> parameters = new LinkedHashMap<>();
        int i = header.indexOf(';');
        while (i >= 0 && i < header.length()) {
            i++;
            while (i < header.length() && header.charAt(i) == ' ') {
                i++;
            }
            int equals = header.indexOf('=', i);
            if (equals < 0) {
                break;
            }
            String key = header.substring(i, equals).trim().toLowerCase(Locale.ROOT);
            int j = equals + 1;
            StringBuilder value = new StringBuilder();
            if (j < header.length() && header.charAt(j) == '"') {
                j++;
                while (j < header.length() && header.charAt(j) != '"') {
                    char c = header.charAt(j);
                    if (c == '\\' && j + 1 < header.length()) {
                        j++;
                        c = header.charAt(j);
                    }
                    value.append(c);
                    j++;
                }
                j++;
                parameters.put(key, value.toString().replace("%22", "\"")
                        .replace("%0D", "\r").replace("%0A", "\n"));
            } else {
                int end = header.indexOf(';', j);
                parameters.put(key, header.substring(j, end < 0 ? header.length() : end).trim());
                j = end < 0 ? header.length() : end;
            }
            i = header.indexOf(';', j);
        }
        return parameters;
    }

    private static boolean startsWith(byte[] body, int position, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        if (position + bytes.length > body.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (body[position + i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("The multipart/form-data body is not well formed");
    }
}
