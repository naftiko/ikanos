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
package io.naftiko.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.restlet.data.MediaType;
import org.restlet.representation.StringRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import io.naftiko.spec.OutputParameterSpec;

public class ConverterTest {

    @TempDir
    Path tempDir;

    @Test
    public void convertToJsonShouldRejectUnsupportedFormat() {
        StringRepresentation entity =
                new StringRepresentation("{}", MediaType.APPLICATION_JSON);

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertToJson("INI", null, entity));

        assertEquals("Unsupported \"INI\" format specified", error.getMessage());
    }

    @Test
    public void convertToJsonShouldRequireSchemaForProtobuf() {
        StringRepresentation entity =
                new StringRepresentation("{}", MediaType.APPLICATION_OCTET_STREAM);

        IOException error = assertThrows(IOException.class,
                () -> Converter.convertToJson("Protobuf", null, entity));

        assertEquals(
                "Protobuf format requires outputSchema to be specified in operation specification",
                error.getMessage());
    }

    @Test
    public void loadSchemaFileShouldPreferLocalFileAndSupportClasspathFallback() throws Exception {
        Path localSchema = tempDir.resolve("local-schema.avsc");
        Files.writeString(localSchema, "{\"type\":\"record\",\"name\":\"Local\",\"fields\":[]}");

        try (InputStream local = Converter.loadSchemaFile(localSchema.toString())) {
            assertNotNull(local);
            assertEquals('{', new String(local.readAllBytes(), StandardCharsets.UTF_8).charAt(0));
        }

                try (InputStream classpath = Converter.loadSchemaFile("schemas/test-records.avsc")) {
            assertNotNull(classpath);
            String content = new String(classpath.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(true, content.contains("record"));
        }
    }

    @Test
    public void applyMaxLengthIfNeededShouldTruncateAndIgnoreInvalidLengths() throws Exception {
        OutputParameterSpec truncatedSpec = new OutputParameterSpec();
        truncatedSpec.setMaxLength("5");

        JsonNode truncated = Converter.applyMaxLengthIfNeeded(truncatedSpec,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("\"abcdefgh\""));
        assertEquals("abcde", truncated.asText());

        OutputParameterSpec invalidSpec = new OutputParameterSpec();
        invalidSpec.setMaxLength("abc");

        JsonNode untouched = Converter.applyMaxLengthIfNeeded(invalidSpec,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("\"abcdefgh\""));
        assertEquals("abcdefgh", untouched.asText());
    }

    @Test
    public void jsonPathExtractShouldSupportMissingPathsAndPropertiesWithSpaces() throws Exception {
        JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "user details": {
                    "contact email": "alice@example.com"
                  }
                }
                """);

        JsonNode value = Converter.jsonPathExtract(root,
                "$['user details']['contact email']");
        JsonNode missing = Converter.jsonPathExtract(root, "$.missing.field");

        assertEquals("alice@example.com", value.asText());
        assertEquals(true, missing.isNull());
        assertEquals("$.['user details'].email",
                Converter.fixJsonPathWithSpaces("$.user details.email"));
    }
}