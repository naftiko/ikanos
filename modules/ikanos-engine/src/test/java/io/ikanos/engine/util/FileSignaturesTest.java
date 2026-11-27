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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.util.FileSignatures.Verdict;

class FileSignaturesTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void pdfShouldMatchItsSignatureOnly() {
        assertEquals(Verdict.MATCHES, FileSignatures.check("application/pdf", ascii("%PDF-1.7")));
        assertEquals(Verdict.MISMATCH, FileSignatures.check("application/pdf",
                ascii("<html>not a pdf</html>")));
        assertEquals(Verdict.MISMATCH, FileSignatures.check("application/pdf", new byte[0]));
    }

    @Test
    void imagesShouldMatchTheirSignatures() {
        assertEquals(Verdict.MATCHES, FileSignatures.check("image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0}));
        assertEquals(Verdict.MATCHES, FileSignatures.check("image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals(Verdict.MATCHES, FileSignatures.check("image/gif", ascii("GIF89a...")));
        assertEquals(Verdict.MATCHES, FileSignatures.check("image/webp",
                ascii("RIFF\0\0\0\0WEBPVP8 ")));
        assertEquals(Verdict.MISMATCH, FileSignatures.check("image/png", ascii("GIF89a")));
    }

    @Test
    void zipBasedFormatsShouldMatchTheZipSignature() {
        byte[] zip = {'P', 'K', 3, 4, 0};
        assertEquals(Verdict.MATCHES, FileSignatures.check(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip));
        assertEquals(Verdict.MATCHES, FileSignatures.check("application/zip", zip));
        assertEquals(Verdict.MISMATCH, FileSignatures.check("application/zip", ascii("%PDF-")));
    }

    @Test
    void textShouldBeUtf8WithoutNul() {
        assertEquals(Verdict.MATCHES, FileSignatures.check("text/csv; charset=utf-8",
                "id,name\n1,Zo\u00eb\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Verdict.MATCHES, FileSignatures.check("application/json",
                ascii("{\"a\":1}")));
        assertEquals(Verdict.MISMATCH, FileSignatures.check("text/plain",
                new byte[] {'a', 0, 'b'}));
        assertEquals(Verdict.MISMATCH, FileSignatures.check("text/plain",
                new byte[] {(byte) 0xC3, (byte) 0x28}));
    }

    @Test
    void otherTypesShouldBeUnknown() {
        assertEquals(Verdict.UNKNOWN, FileSignatures.check("image/tiff", ascii("II*\0")));
        assertEquals(Verdict.UNKNOWN, FileSignatures.check("application/octet-stream",
                new byte[] {1, 2, 3}));
        assertEquals(Verdict.UNKNOWN, FileSignatures.check(null, new byte[0]));
    }
}
