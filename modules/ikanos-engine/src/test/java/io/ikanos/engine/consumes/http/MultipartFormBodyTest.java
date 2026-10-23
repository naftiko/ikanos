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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MultipartFormBodyTest {

    @Test
    public void buildShouldWriteOneFormDataPartPerItemWithRenderedValues() {
        MultipartFormBody body = MultipartFormBody.build(List.of(
                part("description", "{{note}}", null, null, null),
                part("file", "{{content}}", "a.txt", "text/plain", null)),
                params("note", "My file", "content", "hello"));

        String text = new String(body.bytes(), StandardCharsets.UTF_8);
        String b = body.boundary();
        assertEquals("--" + b + "\r\n"
                + "Content-Disposition: form-data; name=\"description\"\r\n\r\n"
                + "My file\r\n"
                + "--" + b + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "hello\r\n"
                + "--" + b + "--\r\n", text);
    }

    @Test
    public void buildShouldDecodeBase64FilePartIntoRawBytes() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF};
        MultipartFormBody body = MultipartFormBody.build(List.of(
                part("file", "{{data}}", "x.png", "image/png", "base64")),
                params("data", java.util.Base64.getEncoder().encodeToString(png)));

        byte[] bytes = body.bytes();
        byte[] marker = "Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int start = indexOf(bytes, marker) + marker.length;
        byte[] content = java.util.Arrays.copyOfRange(bytes, start, start + png.length);
        assertArrayEquals(png, content, "A base64 part must be sent as its decoded bytes");
    }

    @Test
    public void buildShouldRejectInvalidBase64Value() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MultipartFormBody.build(
                        List.of(part("file", "{{data}}", "x.bin", null, "base64")),
                        params("data", "not base64!")));
        assertTrue(ex.getMessage().contains("'file'"), ex.getMessage());
    }

    @Test
    public void buildShouldEscapeQuotesAndLineBreaksInHeaderValues() {
        MultipartFormBody body = MultipartFormBody.build(List.of(
                part("file", "x", "{{name}}", "text/plain", null)),
                params("name", "a\"; name=\"admin\r\nX-Injected: 1.txt"));

        String text = new String(body.bytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("filename=\"a%22; name=%22admin%0D%0AX-Injected: 1.txt\""),
                "A caller value must not close the quoted filename or add a header: " + text);
        assertFalse(text.contains("\r\nX-Injected"), text);
    }

    @Test
    public void buildShouldPickBoundaryThatDoesNotOccurInContent() {
        String content = "--" + MultipartFormBody.build(
                List.of(part("a", "x", null, null, null)), params("k", "v")).boundary();

        MultipartFormBody body = MultipartFormBody.build(
                List.of(part("a", "{{v}}", null, null, null)), params("v", content));

        assertFalse(content.contains(body.boundary()),
                "The boundary must not appear inside a part's content");
    }

    @Test
    public void buildShouldSendCallerTextContainingBracesAsData() {
        MultipartFormBody body = MultipartFormBody.build(List.of(
                part("note", "{{text}}", null, null, null)),
                params("text", "Use {{name}} here"));

        assertTrue(new String(body.bytes(), StandardCharsets.UTF_8).contains("\r\nUse {{name}} here\r\n"));
    }

    @Test
    public void buildShouldRejectTemplateWhenNoParametersAreGiven() {
        assertThrows(IllegalArgumentException.class, () -> MultipartFormBody.build(
                List.of(part("note", "{{text}}", null, null, null)), Map.of()));
    }

    @Test
    public void mediaTypeShouldCarryTheBoundaryParameter() {
        MultipartFormBody body = MultipartFormBody.build(List.of(part("a", "x", null, null, null)),
                params("k", "v"));

        assertEquals("multipart/form-data; boundary=" + body.boundary(),
                body.mediaType().toString());
    }

    private static Map<String, Object> part(String name, String value, String filename,
            String contentType, String encoding) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("name", name);
        part.put("value", value);
        if (filename != null) {
            part.put("filename", filename);
        }
        if (contentType != null) {
            part.put("contentType", contentType);
        }
        if (encoding != null) {
            part.put("encoding", encoding);
        }
        return part;
    }

    private static Map<String, Object> params(String... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
