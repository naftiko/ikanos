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

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.data.MediaType;
import org.restlet.representation.ByteArrayRepresentation;

/** The {@code type: binary} request body: a base64 value, such as a file input's, as bytes. */
class HttpClientAdapterBinaryBodyTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', 0, (byte) 0xFF};

    private static Map<String, Object> report() {
        return Map.of("report", Map.of("data", Base64.getEncoder().encodeToString(PDF),
                "mimeType", "application/pdf"));
    }

    @Test
    void binaryEntityShouldSendTheDecodedBytesWithTheRenderedContentType() throws Exception {
        ByteArrayRepresentation entity = HttpClientAdapter.binaryEntity(Map.of("type", "binary",
                "data", "{{report.data}}", "contentType", "{{report.mimeType}}"), report(),
                "upload");

        assertArrayEquals(PDF, entity.getStream().readAllBytes());
        assertTrue(MediaType.APPLICATION_PDF.equals(entity.getMediaType(), true),
                String.valueOf(entity.getMediaType()));
    }

    @Test
    void binaryEntityShouldDefaultToOctetStream() {
        ByteArrayRepresentation entity = HttpClientAdapter.binaryEntity(
                Map.of("type", "binary", "data", "{{report.data}}"), report(), "upload");

        assertEquals(MediaType.APPLICATION_OCTET_STREAM, entity.getMediaType());
    }

    @Test
    void binaryEntityShouldRefuseAValueThatIsNotBase64WithoutEchoingIt() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> HttpClientAdapter.binaryEntity(Map.of("type", "binary", "data", "{{v}}"),
                        Map.of("v", "secret-value!"), "upload"));

        assertTrue(refused.getMessage().contains("base64"), refused.getMessage());
        assertFalse(refused.getMessage().contains("secret-value"), refused.getMessage());
    }

    @Test
    void binaryEntityShouldRefuseALineBreakInTheContentType() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpClientAdapter.binaryEntity(Map.of("type", "binary",
                        "data", "{{report.data}}", "contentType", "{{ct}}"),
                        Map.of("report", report().get("report"),
                                "ct", "text/plain\r\nX-Injected: 1"), "upload"));
    }

    @Test
    void isBinaryBodyShouldOnlyMatchTheBinaryType() {
        assertTrue(HttpClientAdapter.isBinaryBody(Map.of("type", "binary", "data", "x")));
        assertFalse(HttpClientAdapter.isBinaryBody(Map.of("type", "json", "data", "x")));
        assertFalse(HttpClientAdapter.isBinaryBody("{{body}}"));
    }
}
