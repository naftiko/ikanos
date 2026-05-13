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

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import io.ikanos.Cli;

public class ExportOpenApiCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void exportShouldSucceedWithValidCapabilityFile() throws Exception {
        Path capFile = tempDir.resolve("capability.yml");
        Files.writeString(capFile, """
                ikanos: "1.0.0-alpha1"
                info:
                  label: "Test API"
                  description: "A test capability"
                capability:
                  exposes:
                    - type: rest
                      address: localhost
                      port: 8080
                      resources:
                        - path: /items
                          name: items
                          operations:
                            - method: GET
                              name: list-items
                              description: List all items
                """);

        Path output = tempDir.resolve("openapi.yaml");

        CommandLine cmd = new CommandLine(new Cli());
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("export", "openapi", capFile.toString(),
                "-o", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("openapi:"));
        assertTrue(content.contains("Test API"));
    }

    @Test
    void exportShouldSupportJsonFormat() throws Exception {
        Path capFile = tempDir.resolve("capability.yml");
        Files.writeString(capFile, """
                ikanos: "1.0.0-alpha1"
                info:
                  label: "JSON Test"
                capability:
                  exposes:
                    - type: rest
                      address: localhost
                      port: 9090
                      resources:
                        - path: /data
                          name: data
                          operations:
                            - method: GET
                              name: get-data
                """);

        Path output = tempDir.resolve("openapi.json");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("export", "openapi", capFile.toString(),
                "-o", output.toString(), "-f", "json");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("\"openapi\""));
    }

    @Test
    void exportShouldFailWhenCapabilityFileDoesNotExist() {
        CommandLine cmd = new CommandLine(new Cli());

        int exitCode = cmd.execute("export", "openapi", "missing-capability.yml");

        assertEquals(1, exitCode);
    }

    @Test
    void exportShouldFailWhenSpecVersionIsUnsupported() throws Exception {
        Path capFile = tempDir.resolve("capability.yml");
        Files.writeString(capFile, """
                ikanos: "1.0.0-alpha1"
                info:
                  label: "Spec Version Test"
                capability:
                  exposes:
                    - type: rest
                      address: localhost
                      port: 8080
                      resources:
                        - path: /items
                          name: items
                          operations:
                            - method: GET
                              name: list-items
                """);

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("export", "openapi", capFile.toString(),
                "--spec-version", "2.0");

        assertEquals(1, exitCode);
    }

    @Test
    void exportShouldSupportSpecVersion31() throws Exception {
        Path capFile = tempDir.resolve("spec31-capability.yml");
        Files.writeString(capFile, """
                ikanos: "1.0.0-alpha1"
                info:
                  label: "OpenAPI 3.1 Test"
                capability:
                  exposes:
                    - type: rest
                      address: localhost
                      port: 8080
                      resources:
                        - path: /items
                          name: items
                          operations:
                            - method: GET
                              name: list-items
                """);

        Path output = tempDir.resolve("openapi31.yaml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("export", "openapi", capFile.toString(),
                "-o", output.toString(), "--spec-version", "3.1");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
    }

    @Test
    void exportShouldUseDefaultOutputPathWhenOutputNotProvided() throws Exception {
        Path capFile = tempDir.resolve("default-output-cap.yml");
        Files.writeString(capFile, """
                ikanos: "1.0.0-alpha1"
                info:
                  label: "Default Output Test"
                capability:
                  exposes:
                    - type: rest
                      address: localhost
                      port: 8080
                      resources:
                        - path: /items
                          name: items
                          operations:
                            - method: GET
                              name: list-items
                """);

        Path expectedOutput = Path.of("openapi.yaml").toAbsolutePath().normalize();
        try {
            CommandLine cmd = new CommandLine(new Cli());
            int exitCode = cmd.execute("export", "openapi", capFile.toString());

            assertEquals(0, exitCode);
            assertTrue(Files.exists(expectedOutput));
        } finally {
            Files.deleteIfExists(expectedOutput);
        }
    }

    @Test
    void exportShouldUseJsonExtensionForDefaultOutputWhenFormatIsJson() throws Exception {
        Path capFile = tempDir.resolve("json-default-cap.yml");
        Files.writeString(capFile, """
                ikanos: "1.0.0-alpha1"
                info:
                  label: "JSON Default Output Test"
                capability:
                  exposes:
                    - type: rest
                      address: localhost
                      port: 8080
                      resources:
                        - path: /items
                          name: items
                          operations:
                            - method: GET
                              name: list-items
                """);

        Path expectedOutput = Path.of("openapi.json").toAbsolutePath().normalize();
        try {
            CommandLine cmd = new CommandLine(new Cli());
            int exitCode = cmd.execute("export", "openapi", capFile.toString(), "-f", "json");

            assertEquals(0, exitCode);
            assertTrue(Files.exists(expectedOutput));
        } finally {
            Files.deleteIfExists(expectedOutput);
        }
    }
}
