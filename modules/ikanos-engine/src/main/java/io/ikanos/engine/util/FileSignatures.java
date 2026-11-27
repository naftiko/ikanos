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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Whether a file's bytes look like its declared media type, from their first bytes (experimental,
 * files extension). Only a few common types are recognized: PDF, PNG, JPEG, GIF, WebP, ZIP and
 * the formats built on it (OOXML, ODF, EPUB, JAR), gzip, and text ({@code text/*}, JSON, XML,
 * YAML, CSV), which must be UTF-8 without NUL. Any other type is {@link Verdict#UNKNOWN}: its
 * bytes are not checked.
 */
public final class FileSignatures {

    /** What the bytes say about the declared type. */
    public enum Verdict {
        /** The bytes start as the type's format does. */
        MATCHES,
        /** The bytes do not start as the type's format does. */
        MISMATCH,
        /** The type is not one this class recognizes. */
        UNKNOWN
    }

    private static final byte[] PDF = ascii("%PDF-");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = ascii("GIF87a");
    private static final byte[] GIF89 = ascii("GIF89a");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP = ascii("WEBP");
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] EMPTY_ZIP = {'P', 'K', 0x05, 0x06};
    private static final byte[] GZIP = {0x1F, (byte) 0x8B};

    private FileSignatures() {
    }

    /** Checks {@code bytes} against the declared {@code mediaType}. */
    public static Verdict check(String mediaType, byte[] bytes) {
        String type = essence(mediaType);
        switch (type) {
            case "application/pdf":
                return verdict(startsWith(bytes, 0, PDF));
            case "image/png":
                return verdict(startsWith(bytes, 0, PNG));
            case "image/jpeg":
                return verdict(startsWith(bytes, 0, JPEG));
            case "image/gif":
                return verdict(startsWith(bytes, 0, GIF87) || startsWith(bytes, 0, GIF89));
            case "image/webp":
                return verdict(startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP));
            case "application/gzip", "application/x-gzip":
                return verdict(startsWith(bytes, 0, GZIP));
            case "application/json", "application/xml", "application/yaml",
                    "application/x-yaml", "application/x-ndjson":
                return verdict(isText(bytes));
            default:
                break;
        }
        if (type.startsWith("text/") || type.endsWith("+json") || type.endsWith("+xml")) {
            return verdict(isText(bytes));
        }
        if (isZipBased(type)) {
            return verdict(startsWith(bytes, 0, ZIP) || startsWith(bytes, 0, EMPTY_ZIP));
        }
        return Verdict.UNKNOWN;
    }

    private static boolean isZipBased(String type) {
        return "application/zip".equals(type) || "application/x-zip-compressed".equals(type)
                || "application/epub+zip".equals(type) || "application/java-archive".equals(type)
                || type.startsWith("application/vnd.openxmlformats-officedocument.")
                || type.startsWith("application/vnd.oasis.opendocument.");
    }

    /** Whether {@code bytes} are UTF-8 without NUL: text, as far as the bytes can tell. */
    private static boolean isText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Verdict verdict(boolean matches) {
        return matches ? Verdict.MATCHES : Verdict.MISMATCH;
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String essence(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        int semicolon = mediaType.indexOf(';');
        return (semicolon >= 0 ? mediaType.substring(0, semicolon) : mediaType).trim()
                .toLowerCase(Locale.ROOT);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
