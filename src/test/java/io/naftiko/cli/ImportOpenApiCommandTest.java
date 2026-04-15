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

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import io.naftiko.Cli;

public class ImportOpenApiCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void importShouldSucceedWithValidOpenApiFile() throws Exception {
        // Create a minimal OpenAPI spec
        Path oasFile = tempDir.resolve("petstore.yaml");
        Files.writeString(oasFile, """
                openapi: "3.0.3"
                info:
                  title: "Petstore"
                  version: "1.0.0"
                servers:
                  - url: "https://api.petstore.io/v1"
                paths:
                  /pets:
                    get:
                      operationId: listPets
                      summary: List all pets
                      tags:
                        - Pets
                      responses:
                        "200":
                          description: OK
                """);

        Path output = tempDir.resolve("output.yml");

        CommandLine cmd = new CommandLine(new Cli());
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("import", "openapi", oasFile.toString(),
                "-o", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("petstore"));
    }

    @Test
    void importShouldSucceedWithNamespaceOverride() throws Exception {
        Path oasFile = tempDir.resolve("api.yaml");
        Files.writeString(oasFile, """
                openapi: "3.0.3"
                info:
                  title: "Some API"
                  version: "1.0.0"
                paths: {}
                """);

        Path output = tempDir.resolve("custom.yml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(),
                "-o", output.toString(), "-n", "my-custom-ns");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("my-custom-ns"));
    }
}
