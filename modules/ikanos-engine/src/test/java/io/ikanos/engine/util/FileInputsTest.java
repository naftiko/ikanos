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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.InputParameterSpec;

class FileInputsTest {

    private static final byte[] CSV = "id,name\n1,Ada\n".getBytes(StandardCharsets.UTF_8);
    private static final long LIMIT = 1024 * 1024;

    private static InputParameterSpec fileInput(String name, String maxSize, String... accept) {
        InputParameterSpec input = new InputParameterSpec();
        input.setName(name);
        input.setType("file");
        input.setMaxSize(maxSize);
        input.getAccept().addAll(List.of(accept));
        return input;
    }

    private static Map<String, Object> file(String id, String name, String mimeType,
            byte[] bytes) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", id);
        file.put("name", name);
        file.put("mimeType", mimeType);
        file.put("size", bytes.length);
        file.put("data", Base64.getEncoder().encodeToString(bytes));
        return file;
    }

    private static Map<String, Object> args(String name, Object value) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(name, value);
        return arguments;
    }

    private static String refusal(InputParameterSpec input, Object value, long limit) {
        return assertThrows(FileInputs.InvalidFileException.class,
                () -> FileInputs.check(List.of(input), args(input.getName(), value), limit))
                .getMessage();
    }

    @Test
    void checkShouldKeepAValidFileWithItsBase64Data() {
        Map<String, Object> checked = FileInputs.check(List.of(fileInput("report", null)),
                args("report", file("f1", "q3.csv", "text/csv", CSV)), LIMIT);

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) checked.get("report");
        assertEquals("f1", report.get("id"));
        assertEquals("q3.csv", report.get("name"));
        assertEquals("text/csv", report.get("mimeType"));
        assertEquals((long) CSV.length, report.get("size"));
        assertEquals(Base64.getEncoder().encodeToString(CSV), report.get("data"));
    }

    @Test
    void checkShouldReturnTheArgumentsUnchangedWhenNoInputTakesAFile() {
        InputParameterSpec text = new InputParameterSpec();
        text.setName("label");
        text.setType("string");
        Map<String, Object> arguments = args("label", "Q3");

        assertSame(arguments, FileInputs.check(List.of(text), arguments, LIMIT));
    }

    @Test
    void checkShouldRefuseAFileLargerThanItsMaxSize() {
        String message = refusal(fileInput("report", "8B"), file("f1", "q3.csv", "text/csv", CSV),
                LIMIT);

        assertTrue(message.contains("maxSize"), message);
    }

    @Test
    void checkShouldRefuseFilesOverTheLimitOfTheCall() {
        String message = refusal(fileInput("report", null), file("f1", "q3.csv", "text/csv", CSV),
                CSV.length - 1);

        assertTrue(message.contains("limit"), message);
    }

    @Test
    void checkShouldRefuseAFileOfATypeItDoesNotAccept() {
        String message = refusal(fileInput("report", null, "application/pdf"),
                file("f1", "q3.csv", "text/csv", CSV), LIMIT);

        assertTrue(message.contains("type"), message);
    }

    @Test
    void checkShouldRefuseAFileWithoutDataUnlessItIsEmpty() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.remove("data");

        String message = refusal(fileInput("report", null), file, LIMIT);

        assertTrue(message.contains("out of band"), message);
    }

    @Test
    void checkShouldAcceptAFileConfirmedOutOfBandWithItsUploadId() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.remove("data");
        file.put("digest", Map.of("algorithm", "sha-256", "value", "not-checked"));

        Map<String, Object> checked = FileInputs.withUploads(
                Map.of("f1", new FileInputs.Uploaded("obj-1", CSV.length)),
                () -> FileInputs.check(List.of(fileInput("report", null)), args("report", file),
                        0));

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) checked.get("report");
        assertEquals("obj-1", report.get(FileInputs.UPLOAD_ID));
        assertFalse(report.containsKey("data"));
        assertFalse(report.containsKey("digest"), "An unchecked digest is not passed on");
    }

    @Test
    void checkShouldRefuseAConfirmedUploadOfAnotherSize() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.remove("data");

        assertThrows(FileInputs.InvalidFileException.class, () -> FileInputs.withUploads(
                Map.of("f1", new FileInputs.Uploaded("obj-1", CSV.length + 1)),
                () -> FileInputs.check(List.of(fileInput("report", null)), args("report", file),
                        LIMIT)));
    }

    @Test
    void checkShouldIgnoreAnUploadIdTheClientSent() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.remove("data");
        file.put(FileInputs.UPLOAD_ID, "chosen-by-client");

        assertThrows(FileInputs.InvalidFileException.class, () -> FileInputs.check(
                List.of(fileInput("report", null)), args("report", file), LIMIT));
    }

    @Test
    void pendingShouldListTheFilesWithoutData() {
        Map<String, Object> outOfBand = file("f1", "big.csv", "text/csv", CSV);
        outOfBand.remove("data");
        Map<String, Object> arguments = args("report", outOfBand);
        arguments.put("inline", file("f2", "small.csv", "text/csv", CSV));

        List<FileInputs.PendingFile> pending = FileInputs.pending(
                List.of(fileInput("report", null), fileInput("inline", null)), arguments, LIMIT);

        assertEquals(List.of(new FileInputs.PendingFile("report", "f1", "big.csv", "text/csv",
                CSV.length)), pending);
    }

    @Test
    void pendingShouldRefuseAFileOverMaxSizeWithoutItsBytes() {
        Map<String, Object> file = file("f1", "big.csv", "text/csv", CSV);
        file.remove("data");

        assertThrows(FileInputs.InvalidFileException.class, () -> FileInputs.pending(
                List.of(fileInput("report", "4B")), args("report", file), LIMIT));
    }

    @Test
    void pendingShouldRefuseAFileOfATypeTheInputDoesNotAccept() {
        Map<String, Object> file = file("f1", "logo.png", "image/png", CSV);
        file.remove("data");

        assertThrows(FileInputs.InvalidFileException.class, () -> FileInputs.pending(
                List.of(fileInput("report", null, "text/csv")), args("report", file), LIMIT));
    }

    @Test
    void pendingShouldBeEmptyWhenEveryFileIsInline() {
        assertTrue(FileInputs.pending(List.of(fileInput("report", null)),
                args("report", file("f1", "q3.csv", "text/csv", CSV)), LIMIT).isEmpty());
    }

    @Test
    void checkShouldAcceptAnEmptyFileWithoutData() {
        Map<String, Object> file = file("f1", "empty.csv", "text/csv", new byte[0]);
        file.remove("data");

        Map<String, Object> checked = FileInputs.check(List.of(fileInput("report", null)),
                args("report", file), LIMIT);

        assertEquals("", ((Map<?, ?>) checked.get("report")).get("data"));
    }

    @Test
    void checkShouldRefuseASizeThatIsNotTheSizeOfTheData() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.put("size", CSV.length + 1);

        String message = refusal(fileInput("report", null), file, LIMIT);

        assertTrue(message.contains("size"), message);
    }

    @Test
    void checkShouldRefuseDataThatIsNotBase64WithoutEchoingIt() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.put("data", "secret-payload!");
        file.put("size", 11);

        String message = refusal(fileInput("report", null), file, LIMIT);

        assertTrue(message.contains("base64"), message);
        assertFalse(message.contains("secret-payload"), message);
    }

    @Test
    void checkShouldRefuseAFileThatIsNotAnObject() {
        String message = refusal(fileInput("report", null), "aWQsbmFtZQ==", LIMIT);

        assertTrue(message.contains("file object"), message);
    }

    @Test
    void checkShouldAcceptAMatchingDigest() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.put("digest", Map.of("algorithm", "sha-256", "value", FileInputs.digestOf(CSV)));

        Map<String, Object> checked = FileInputs.check(List.of(fileInput("report", null)),
                args("report", file), LIMIT);

        assertEquals(FileInputs.digestOf(CSV),
                ((Map<?, ?>) ((Map<?, ?>) checked.get("report")).get("digest")).get("value"));
    }

    @Test
    void checkShouldRefuseADigestThatDoesNotMatch() {
        Map<String, Object> file = file("f1", "q3.csv", "text/csv", CSV);
        file.put("digest", Map.of("algorithm", "sha-256",
                "value", FileInputs.digestOf("other".getBytes(StandardCharsets.UTF_8))));

        String message = refusal(fileInput("report", null), file, LIMIT);

        assertTrue(message.contains("digest"), message);
    }

    @Test
    void checkShouldRefuseTwoFilesWithTheSameId() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("first", file("same", "a.csv", "text/csv", CSV));
        arguments.put("second", file("same", "b.csv", "text/csv", CSV));

        FileInputs.InvalidFileException refused = assertThrows(
                FileInputs.InvalidFileException.class,
                () -> FileInputs.check(List.of(fileInput("first", null),
                        fileInput("second", null)), arguments, LIMIT));

        assertTrue(refused.getMessage().contains("same 'id'"), refused.getMessage());
    }

    @Test
    void checkShouldRefuseAnIdLongerThan128Characters() {
        String message = refusal(fileInput("report", null),
                file("x".repeat(129), "q3.csv", "text/csv", CSV), LIMIT);

        assertTrue(message.contains("'id'"), message);
    }

    @Test
    void acceptsShouldMatchAMediaTypeRangeIgnoringParameters() {
        assertTrue(FileInputs.accepts(fileInput("photo", null, "image/*"),
                "image/png; q=1", "logo.png"));
        assertFalse(FileInputs.accepts(fileInput("photo", null, "image/*"),
                "application/pdf", "logo.png"));
    }

    @Test
    void acceptsShouldMatchADotPrefixedExtensionOfTheName() {
        assertTrue(FileInputs.accepts(fileInput("report", null, ".csv"),
                "application/octet-stream", "Q3.CSV"));
        assertFalse(FileInputs.accepts(fileInput("report", null, ".csv"),
                "text/csv", null));
    }
}
