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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.restlet.data.MediaType;
import org.restlet.data.Parameter;
import org.restlet.util.Series;
import io.ikanos.engine.util.Resolver;

/**
 * Builds {@code multipart/form-data} request bodies ({@code type: multipartForm}, #857).
 *
 * <p>Each item of {@code data} becomes one part. Its {@code value}, {@code filename} and
 * {@code contentType} are Mustache-rendered; a substituted value is caller data and is never
 * interpreted, even when it contains braces. A part with {@code encoding: base64} is sent as the
 * decoded bytes of its rendered value, so a binary file can be passed as a string argument.</p>
 *
 * <p>Header values are quoted, and a caller value cannot close the quotes or add a header line:
 * {@code "}, CR and LF are percent-encoded, as browsers do for {@code multipart/form-data}
 * (WHATWG HTML). The boundary is random and chosen so that it does not occur in any part.</p>
 */
public final class MultipartFormBody {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] CRLF = {'\r', '\n'};

    private final String boundary;
    private final byte[] bytes;

    private MultipartFormBody(String boundary, byte[] bytes) {
        this.boundary = boundary;
        this.bytes = bytes;
    }

    /**
     * Render and encode the parts.
     *
     * @param data the {@code data} list of the body spec: one map per part
     * @param parameters the request parameters
     * @throws IllegalArgumentException when a part is malformed, a base64 value is invalid, or a
     *         template cannot be resolved because no parameters are given
     */
    public static MultipartFormBody build(List<?> data, Map<String, Object> parameters) {
        boolean noParameters = parameters == null || parameters.isEmpty();
        List<Part> parts = new ArrayList<>();
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> spec)) {
                throw new IllegalArgumentException("A multipartForm part must be an object");
            }
            Object rawName = spec.get("name");
            if (rawName == null) {
                throw new IllegalArgumentException("A multipartForm part requires a name");
            }
            String name = String.valueOf(rawName);
            String value = render(name, spec.get("value"), parameters, noParameters);
            String filename = render(name, spec.get("filename"), parameters, noParameters);
            String contentType = render(name, spec.get("contentType"), parameters, noParameters);
            if (contentType != null && (contentType.indexOf('\r') >= 0
                    || contentType.indexOf('\n') >= 0)) {
                // Content-Type is not a quoted value, so quotes are kept as they are (a quoted
                // charset is valid); only a line break could start a new header.
                throw new IllegalArgumentException(
                        "Line break in the contentType of multipartForm part '" + name + "'");
            }
            byte[] content;
            if ("base64".equalsIgnoreCase(String.valueOf(spec.get("encoding")))) {
                try {
                    // Strip whitespace so line-wrapped input decodes; reject anything else.
                    content = Base64.getDecoder()
                            .decode(value == null ? "" : value.replaceAll("\\s", ""));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid base64 value in multipartForm part '" + name + "'", e);
                }
            } else {
                content = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            }
            parts.add(new Part(name, filename, contentType, content));
        }

        String boundary = newBoundary(parts);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
            ascii(out, "--" + boundary + "\r\n");
            StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"")
                    .append(escapeHeaderValue(part.name)).append('"');
            if (part.filename != null) {
                disposition.append("; filename=\"").append(escapeHeaderValue(part.filename))
                        .append('"');
            }
            utf8(out, disposition.append("\r\n").toString());
            if (part.contentType != null && !part.contentType.isBlank()) {
                utf8(out, "Content-Type: " + part.contentType + "\r\n");
            }
            out.writeBytes(CRLF);
            out.writeBytes(part.content);
            out.writeBytes(CRLF);
        }
        ascii(out, "--" + boundary + "--\r\n");
        return new MultipartFormBody(boundary, out.toByteArray());
    }

    public String boundary() {
        return boundary;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    /** {@code multipart/form-data} with this body's {@code boundary} parameter. */
    public MediaType mediaType() {
        Series<Parameter> params = new Series<>(Parameter.class);
        params.add("boundary", boundary);
        return new MediaType(MediaType.MULTIPART_FORM_DATA, params);
    }

    private static String render(String partName, Object raw, Map<String, Object> parameters,
            boolean noParameters) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw);
        if (noParameters) {
            // Same check as the other body types (#858): it reads the template, so optional
            // sections are allowed, and names the missing variables without echoing any value.
            List<String> missing = Resolver.missingVariables(text, parameters);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Unresolved template parameters in "
                        + "multipartForm part '" + partName + "': " + missing);
            }
        }
        return Resolver.resolveMustacheTemplate(text, parameters);
    }

    /**
     * Percent-encodes {@code "}, CR and LF, as browsers do in the quoted {@code name} and
     * {@code filename} values of a multipart header.
     */
    static String escapeHeaderValue(String value) {
        return value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static String newBoundary(List<Part> parts) {
        while (true) {
            byte[] random = new byte[18];
            RANDOM.nextBytes(random);
            String candidate = "ikanos-"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            byte[] marker = candidate.getBytes(StandardCharsets.US_ASCII);
            boolean clash = false;
            for (Part part : parts) {
                if (contains(part.content, marker)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return candidate;
            }
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void utf8(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private record Part(String name, String filename, String contentType, byte[] content) {
    }
}
