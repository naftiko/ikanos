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

    @Test
    void importShouldProduceJsonWhenFormatIsJson() throws Exception {
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
                      responses:
                        "200":
                          description: OK
                """);

        Path output = tempDir.resolve("output.json");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(),
                "-o", output.toString(), "-f", "json");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.stripLeading().startsWith("{"), "Output should be valid JSON");
        assertTrue(content.contains("\"petstore\""));
    }

    @Test
    void importShouldAutoConvertSwagger20ToNaftikoConsumes() throws Exception {
        Path oasFile = tempDir.resolve("petstore-v2.yaml");
        Files.writeString(oasFile, """
                swagger: "2.0"
                info:
                  title: "Petstore"
                  version: "1.0.0"
                host: "petstore.swagger.io"
                basePath: "/v1"
                schemes:
                  - "https"
                paths:
                  /pets:
                    get:
                      operationId: listPets
                      summary: List all pets
                      parameters:
                        - name: limit
                          in: query
                          required: false
                          type: integer
                      produces:
                        - application/json
                      responses:
                        "200":
                          description: A list of pets
                          schema:
                            type: array
                            items:
                              type: object
                              properties:
                                id:
                                  type: integer
                                name:
                                  type: string
                """);

        Path output = tempDir.resolve("output.yml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(),
                "-o", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("petstore"), "Namespace should be derived from Swagger 2.0 title");
        assertTrue(content.contains("list-pets"), "Operation should be converted from Swagger 2.0");
    }

    @Test
    void importShouldDeriveBaseUriFromSwagger20HostAndBasePath() throws Exception {
        Path oasFile = tempDir.resolve("v2-api.yaml");
        Files.writeString(oasFile, """
                swagger: "2.0"
                info:
                  title: "My Legacy API"
                  version: "2.0.0"
                host: "api.legacy.io"
                basePath: "/v2"
                schemes:
                  - "https"
                paths:
                  /users:
                    get:
                      operationId: listUsers
                      summary: List users
                      responses:
                        "200":
                          description: OK
                """);

        Path output = tempDir.resolve("legacy.yml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(),
                "-o", output.toString());

        assertEquals(0, exitCode);
        String content = Files.readString(output);
        assertTrue(content.contains("https://api.legacy.io/v2"),
                "Base URI should be derived from Swagger 2.0 host + basePath");
        assertTrue(content.contains("my-legacy-api"), "Namespace should be kebab-cased title");
    }

    @Test
    void importShouldConvertSwagger20RequestBodyParameters() throws Exception {
        Path oasFile = tempDir.resolve("v2-body.yaml");
        Files.writeString(oasFile, """
                swagger: "2.0"
                info:
                  title: "Body Test API"
                  version: "1.0.0"
                host: "api.example.com"
                basePath: "/"
                schemes:
                  - "https"
                paths:
                  /items:
                    post:
                      operationId: createItem
                      summary: Create an item
                      consumes:
                        - application/json
                      parameters:
                        - name: body
                          in: body
                          required: true
                          schema:
                            type: object
                            required:
                              - name
                            properties:
                              name:
                                type: string
                              description:
                                type: string
                      responses:
                        "201":
                          description: Created
                """);

        Path output = tempDir.resolve("body-test.yml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(),
                "-o", output.toString());

        assertEquals(0, exitCode);
        String content = Files.readString(output);
        assertTrue(content.contains("create-item"), "Operation name should be converted");
        assertTrue(content.contains("name"), "Body parameter 'name' should be present");
    }

    @Test
    void importShouldProduceNonEmptyOutputForSwagger20WithRefDefinitions() throws Exception {
        Path oasFile = Path.of("src/test/resources/openapi/petstore-swagger2-full.json");

        Path output = tempDir.resolve("petstore-full.yml");

        CommandLine cmd = new CommandLine(new Cli());
        StringWriter errWriter = new StringWriter();
        cmd.setErr(new PrintWriter(errWriter));

        int exitCode = cmd.execute("import", "openapi", oasFile.toAbsolutePath().toString(),
                "-o", output.toString());

        assertEquals(0, exitCode, "Import should succeed. Errors: " + errWriter);
        assertTrue(Files.exists(output), "Output file should be created");

        String content = Files.readString(output);
        assertFalse(content.isBlank(), "Output should not be blank");
        assertTrue(content.contains("naftiko:"), "Output should contain naftiko version");
        assertTrue(content.contains("consumes:"), "Output should contain consumes section");
        assertTrue(content.contains("swagger-petstore"), "Namespace should be derived from title");
        assertTrue(content.contains("baseUri:"), "Output should contain baseUri");
        assertTrue(content.contains("resources:"), "Output should contain resources");
        assertTrue(content.contains("get-pet-by-id"), "Operation should be kebab-cased");
    }
}
