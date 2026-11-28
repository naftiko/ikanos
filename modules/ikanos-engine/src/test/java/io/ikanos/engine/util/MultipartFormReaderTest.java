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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.restlet.data.MediaType;
import org.restlet.data.Parameter;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.util.Series;
import tools.jackson.databind.JsonNode;

class MultipartFormReaderTest {

    private static final String BOUNDARY = "test-boundary-42";
    private static final byte[] PDF = {'%', 'P', 'D', 'F', 0, (byte) 0xFF, '\r', '\n', 1};

    private static byte[] form(String fileName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                + "Q3 report\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"report\"; filename=\"" + fileName
                + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(PDF);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static ByteArrayRepresentation entity(byte[] body, String boundary) {
        Series<Parameter> params = new Series<>(Parameter.class);
        if (boundary != null) {
            params.add("boundary", boundary);
        }
        return new ByteArrayRepresentation(body,
                new MediaType(MediaType.MULTIPART_FORM_DATA, params));
    }

    @Test
    void readShouldGiveATextPartAsAString() {
        JsonNode root = MultipartFormReader.read(entity(form("q3.pdf"), BOUNDARY), 1024);

        assertEquals("Q3 report", root.path("label").asString());
    }

    @Test
    void readShouldGiveAFilePartAsAFileObjectWithItsBytesInBase64() {
        JsonNode report = MultipartFormReader.read(entity(form("q3.pdf"), BOUNDARY), 1024)
                .path("report");

        assertEquals("report", report.path("id").asString());
        assertEquals("q3.pdf", report.path("name").asString());
        assertEquals("application/pdf", report.path("mimeType").asString());
        assertEquals(PDF.length, report.path("size").asInt());
        assertArrayEquals(PDF, Base64.getDecoder().decode(report.path("data").asString()));
    }

    @Test
    void readShouldDecodeAPercentEncodedQuoteInTheFileName() {
        JsonNode report = MultipartFormReader.read(entity(form("q3 %22final%22.pdf"), BOUNDARY),
                1024).path("report");

        assertEquals("q3 \"final\".pdf", report.path("name").asString());
    }

    @Test
    void readShouldRefuseABodyLargerThanTheLimit() {
        byte[] big = new byte[(int) MultipartFormReader.FORM_OVERHEAD_BYTES + 200];

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> MultipartFormReader.read(entity(big, BOUNDARY), 100));

        assertTrue(refused.getMessage().contains("larger"), refused.getMessage());
    }

    @Test
    void readShouldRefuseABodyWithoutBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> MultipartFormReader.read(entity(form("q3.pdf"), null), 1024));
    }

    @Test
    void readShouldRefuseABodyThatIsNotWellFormed() {
        byte[] truncated = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"a\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> MultipartFormReader.read(entity(truncated, BOUNDARY), 1024));
    }

    @Test
    void isMultipartFormShouldBeFalseForJson() {
        assertFalse(MultipartFormReader.isMultipartForm(
                new ByteArrayRepresentation(new byte[0], MediaType.APPLICATION_JSON)));
        assertTrue(MultipartFormReader.isMultipartForm(entity(new byte[0], BOUNDARY)));
    }
}
