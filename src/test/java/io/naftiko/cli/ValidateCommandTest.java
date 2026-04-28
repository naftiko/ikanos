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
package io.naftiko.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

public class ValidateCommandTest {

    @TempDir
    Path tempDir;

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
}