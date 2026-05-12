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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

public class ValidateCommandTest {

    @TempDir
    Path tempDir;

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream outCapture;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void setUp() {
        outCapture = new ByteArrayOutputStream();
        errCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outCapture, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void loadFileShouldParseYaml() throws Exception {
        Path yaml = tempDir.resolve("sample.yaml");
        Files.writeString(yaml, "name: test\n");

        ValidateCommand command = new ValidateCommand();

        JsonNode yamlNode = command.loadFile(yaml.toFile());

        assertEquals("test", yamlNode.path("name").asText());
    }

    @Test
    public void loadFileShouldRejectUnsupportedExtensions() throws Exception {
        Path txt = tempDir.resolve("sample.txt");
        Files.writeString(txt, "content\n");

        ValidateCommand command = new ValidateCommand();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> command.loadFile(txt.toFile()));

        assertEquals("Unsupported file format. Only .yaml and .yml are supported.",
                error.getMessage());
    }

    @Test
    public void callShouldPrintUserVisibleErrorForUnsupportedFileFormat() {
        Path txt = tempDir.resolve("sample.txt");
        assertDoesNotThrow(() -> Files.writeString(txt, "content\n"));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true));
            CommandLine cmd = new CommandLine(new ValidateCommand());
            exitCode = cmd.execute(txt.toString());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("Error: Unsupported file format. Only .yaml and .yml are supported."));
    }

        @Test
        public void callShouldFailWhenInputFileDoesNotExist() {
                Path missing = tempDir.resolve("missing.yaml");

                int exitCode = new CommandLine(new ValidateCommand()).execute(missing.toString());

                assertEquals(1, exitCode);
                assertTrue(errCapture.toString().contains("Error: File not found"));
        }

        @Test
        public void callShouldFailWhenSchemaVersionIsUnsupported() {
                Path yaml = tempDir.resolve("capability.yaml");
                assertDoesNotThrow(() -> Files.writeString(yaml, "ikanos: \"1.0.0-alpha3\"\n"));

                int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "9.9");

                assertEquals(1, exitCode);
                assertTrue(errCapture.toString().contains("Schema ikanos-schema-v9.9.json is not supported"));
        }

        @Test
        public void callShouldFailWhenValidationDetectsSchemaErrors() {
                Path yaml = tempDir.resolve("invalid-capability.yaml");
                assertDoesNotThrow(() -> Files.writeString(yaml, "ikanos: \"1.0.0-alpha3\"\ninfo: {}\n"));

                int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

                assertEquals(1, exitCode);
                assertTrue(errCapture.toString().contains("Validation failed"));
        }

        @Test
        public void callShouldSucceedForValidCapabilityYaml() {
                Path yaml = Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml")
                                .toAbsolutePath().normalize();

                int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

                assertEquals(0, exitCode);
                assertTrue(outCapture.toString().contains("Validation successful"));
        }

        @Test
        public void callShouldReturnOneWhenUnexpectedExceptionOccurs() {
                ValidateCommand command = new ValidateCommand() {
                        @Override
                        JsonNode loadFile(java.io.File file) {
                                throw new RuntimeException("boom");
                        }
                };

                Path yaml = tempDir.resolve("unexpected.yaml");
                assertDoesNotThrow(() -> Files.writeString(yaml, "ikanos: \"1.0.0-alpha3\"\n"));

                int exitCode = new CommandLine(command).execute(yaml.toString());

                assertEquals(1, exitCode);
                assertTrue(errCapture.toString().contains("Error: boom"));
        }

        @Test
        public void callShouldDetectAndUseJsonSchema() {
                Path yaml = Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml")
                                .toAbsolutePath().normalize();

                int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

                assertEquals(0, exitCode);
        }

        @Test
        public void callShouldValidateAgainstExplicitJsonSchemaVersion() {
                Path yaml = Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml")
                                .toAbsolutePath().normalize();

                int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "2020-12");

                assertEquals(0, exitCode);
        }

        @Test
        public void callShouldRejectUnsupportedSchemaVersion() {
                int exitCode = new CommandLine(new ValidateCommand())
                        .execute(Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml").toAbsolutePath().normalize().toString(), "9.9");

                assertEquals(1, exitCode);
                assertTrue(errCapture.toString().contains("Error: Schema"));
        }

        @Test
        public void callShouldUseSpecVersionParameterWhenProvided() throws Exception {
                Path yaml = Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml")
                                .toAbsolutePath().normalize();

                int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "1.0.0");

                assertEquals(1, exitCode);
                String errMsg = errCapture.toString();
                assertTrue(errMsg.contains("Schema") && errMsg.contains("not supported"), 
                        "Error message should indicate unsupported schema");
        }

        @Test
        public void loadFileShouldRejectJsonFiles() throws Exception {
                Path jsonFile = tempDir.resolve("file.json");
                Files.writeString(jsonFile, "{\"key\": \"value\"}");

                ValidateCommand command = new ValidateCommand();
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                                () -> command.loadFile(jsonFile.toFile()));

                assertEquals("Unsupported file format. Only .yaml and .yml are supported.",
                                error.getMessage());
        }
}