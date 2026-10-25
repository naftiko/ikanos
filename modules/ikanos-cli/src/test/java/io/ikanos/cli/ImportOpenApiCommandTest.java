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

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import io.ikanos.Cli;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;

public class ImportOpenApiCommandTest {

    /**
     * OpenAPI 3.0 document exercising every shape the importer converts: query, path and header
     * parameters, a JSON body, nested object and array response properties, a top-level array
     * response, and a security scheme.
     */
    private static final String RICH_OAS_30 = """
            openapi: "3.0.3"
            info:
              title: "Rich API"
              version: "1.0.0"
            servers:
              - url: "https://api.rich.io/v1"
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
            paths:
              /pets:
                get:
                  operationId: listPets
                  summary: List pets
                  description: Returns every pet.
                  parameters:
                    - name: limit
                      in: query
                      required: false
                      description: Maximum number of pets.
                      schema:
                        type: integer
                    - name: X-Request-Id
                      in: header
                      schema:
                        type: string
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: object
                              properties:
                                id:
                                  type: integer
                post:
                  operationId: createPet
                  summary: Create a pet
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name]
                          properties:
                            name:
                              type: string
                            tag:
                              type: string
                  responses:
                    "201":
                      description: Created
              /pets/{petId}:
                get:
                  operationId: showPetById
                  summary: Show a pet
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              owner:
                                type: object
                                properties:
                                  name:
                                    type: string
                              tags:
                                type: array
                                items:
                                  type: string
            """;

    private static Schema schema;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ImportOpenApiCommandTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            JsonNode schemaNode = IkanosMappers.json().readTree(in);
            schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaNode);
        }
    }

    private static List<String> schemaErrors(JsonNode document) {
        return schema.validate(document).stream()
                .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                .toList();
    }

    private Path importToYaml(String oasContent, String baseName) throws Exception {
        Path oasFile = tempDir.resolve(baseName + ".yaml");
        Files.writeString(oasFile, oasContent);
        Path output = tempDir.resolve(baseName + "-consumes.yml");
        int exitCode = new CommandLine(new Cli()).execute("import", "openapi",
                oasFile.toString(), "-o", output.toString());
        assertEquals(0, exitCode, "Import should succeed");
        return output;
    }

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
        assertTrue(content.startsWith("# @ikanos\n---\n"), "Output should start with Naftiko modeline header");
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
        assertTrue(content.startsWith("# @ikanos\n---\n"), "Output should start with Naftiko modeline header");
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
        assertTrue(content.startsWith("# @ikanos\n---\n"), "Output should start with Naftiko modeline header");
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
        assertTrue(content.startsWith("# @ikanos\n---\n"), "Output should start with Naftiko modeline header");
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
        assertTrue(content.startsWith("# @ikanos\n---\n"), "Output should start with Naftiko modeline header");
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
        assertTrue(content.startsWith("# @ikanos\n---\n"), "Output should start with Naftiko modeline header");
        assertTrue(content.contains("ikanos:"), "Output should contain ikanos version");
        assertTrue(content.contains("consumes:"), "Output should contain consumes section");
        assertTrue(content.contains("swagger-petstore"), "Namespace should be derived from title");
        assertTrue(content.contains("baseUri:"), "Output should contain baseUri");
        assertTrue(content.contains("resources:"), "Output should contain resources");
        assertTrue(content.contains("get-pet-by-id"), "Operation should be kebab-cased");
    }

      @Test
      void importShouldFailWhenSourceCannotBeParsed() {
        CommandLine cmd = new CommandLine(new Cli());

        int exitCode = cmd.execute("import", "openapi", "does-not-exist.yaml");

        assertEquals(1, exitCode);
      }

      @Test
      void importShouldWriteDefaultOutputPathWhenOutputNotProvided() throws Exception {
        Path oasFile = tempDir.resolve("default-output.yaml");
        Files.writeString(oasFile, """
            openapi: "3.0.3"
            info:
              title: "Default Output API"
              version: "1.0.0"
            paths: {}
            """);
        java.util.concurrent.atomic.AtomicReference<String> derivedPath =
                new java.util.concurrent.atomic.AtomicReference<>();
        ImportOpenApiCommand command = new ImportOpenApiCommand() {
          @Override
          String deriveOutputPath(String namespace, String format) {
            String derived = super.deriveOutputPath(namespace, format);
            derivedPath.set(derived);
            // Override to write to tempDir instead of CWD
            String ext = "json".equalsIgnoreCase(format) ? "json" : "yml";
            return tempDir.resolve(namespace + "-consumes." + ext).toString();
          }
        };
        // Use reflection to set the private fields (simulating CommandLine's behavior)
        java.lang.reflect.Field sourceField = ImportOpenApiCommand.class.getDeclaredField("source");
        sourceField.setAccessible(true);
        sourceField.set(command, oasFile.toString());

        int exitCode = command.call();

        assertEquals(0, exitCode);
        String path = derivedPath.get();
        assertTrue(path.contains("default-output-api-consumes.yml"),
                "Default output path should follow pattern ./<namespace>-consumes.yml");
      }

    @Test
    void importShouldHandleParseResultWithWarningMessages() throws Exception {
        Path oasFile = tempDir.resolve("with-warnings.yaml");
        Files.writeString(oasFile, """
                openapi: "3.0.3"
                info:
                  title: "API With Warnings"
                  version: "1.0.0"
                paths: {}
                """);

        Path output = tempDir.resolve("warned-output.yml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(), "-o", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("api-with-warnings"));
    }

    @Test
    void importShouldWriteJsonFormatWhenFormatSpecifiedAsJson() throws Exception {
        Path oasFile = tempDir.resolve("json-format-test.yaml");
        Files.writeString(oasFile, """
                openapi: "3.0.3"
                info:
                  title: "JSON Format Test"
                  version: "1.0.0"
                paths: {}
                """);

        Path output = tempDir.resolve("output-spec.json");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(), "-o", output.toString(), "-f", "json");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.startsWith("{"), "Output should be valid JSON");
        assertTrue(content.contains("ikanos"));
    }

    @Test
    void importShouldSelectJsonExtensionForDefaultOutputWhenFormatIsJson() throws Exception {
        Path oasFile = tempDir.resolve("json-ext-test.yaml");
        Files.writeString(oasFile, """
                openapi: "3.0.3"
                info:
                  title: "JSON Extension API"
                  version: "1.0.0"
                paths: {}
                """);

        Path expectedOutput = Path.of("json-extension-api-consumes.json").toAbsolutePath().normalize();
        try {
            CommandLine cmd = new CommandLine(new Cli());
            int exitCode = cmd.execute("import", "openapi", oasFile.toString(), "-f", "json");

            assertEquals(0, exitCode);
            assertTrue(Files.exists(expectedOutput));
        } finally {
            Files.deleteIfExists(expectedOutput);
        }
    }

    @Test
    void importShouldApplyNamespaceOverrideToOutput() throws Exception {
        Path oasFile = tempDir.resolve("ns-test.yaml");
        Files.writeString(oasFile, """
                openapi: "3.0.3"
                info:
                  title: "Original Title"
                  version: "1.0.0"
                paths: {}
                """);

        Path output = tempDir.resolve("ns-output.yml");

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("import", "openapi", oasFile.toString(), 
            "-o", output.toString(), "-n", "my-override-namespace");

        assertEquals(0, exitCode);
        String content = Files.readString(output);
        assertTrue(content.contains("my-override-namespace"));
    }

    @Test
    void importShouldWriteSchemaValidYamlForEveryConvertedShape() throws Exception {
        Path output = importToYaml(RICH_OAS_30, "rich");

        List<String> errors = schemaErrors(IkanosMappers.yaml().readTree(output.toFile()));

        assertTrue(errors.isEmpty(),
                "#871: imported YAML must pass ikanos validate, but got: " + errors);
    }

    @Test
    void importShouldWriteSchemaValidJsonForEveryConvertedShape() throws Exception {
        Path oasFile = tempDir.resolve("rich.yaml");
        Files.writeString(oasFile, RICH_OAS_30);
        Path output = tempDir.resolve("rich-consumes.json");

        int exitCode = new CommandLine(new Cli()).execute("import", "openapi",
                oasFile.toString(), "-o", output.toString(), "-f", "json");

        assertEquals(0, exitCode);
        List<String> errors = schemaErrors(IkanosMappers.json().readTree(output.toFile()));
        assertTrue(errors.isEmpty(),
                "#871: imported JSON must be schema-valid, but got: " + errors);
    }

    @Test
    void importShouldWriteSchemaValidYamlForSwagger20FixtureWithRefDefinitions()
            throws Exception {
        Path oasFile = Path.of("src/test/resources/openapi/petstore-swagger2-full.json");
        Path output = tempDir.resolve("petstore-full.yml");

        int exitCode = new CommandLine(new Cli()).execute("import", "openapi",
                oasFile.toAbsolutePath().toString(), "-o", output.toString());

        assertEquals(0, exitCode);
        List<String> errors = schemaErrors(IkanosMappers.yaml().readTree(output.toFile()));
        assertTrue(errors.isEmpty(),
                "#871: imported Swagger 2.0 fixture must be schema-valid, but got: " + errors);
    }

    @Test
    void importShouldKeyParametersByNameWithoutRepeatingNames() throws Exception {
        JsonNode adapter = IkanosMappers.yaml()
                .readTree(importToYaml(RICH_OAS_30, "rich").toFile())
                .path("consumes").path(0);

        JsonNode pets = adapter.path("resources").path("pets");
        assertFalse(pets.has("name"), "#871: the resource map key is the name");
        JsonNode listPets = pets.path("operations").path("list-pets");
        assertFalse(listPets.has("name"), "#871: the operation map key is the name");

        JsonNode inputs = listPets.path("inputParameters");
        assertTrue(inputs.isObject(), "#871: inputParameters must be a map keyed by name");
        assertEquals("query", inputs.path("limit").path("in").asString());
        assertEquals("header", inputs.path("X-Request-Id").path("in").asString());
        assertFalse(inputs.path("limit").has("name"));

        JsonNode body = adapter.path("resources").path("pets").path("operations")
                .path("create-pet").path("inputParameters");
        assertEquals("body", body.path("name").path("in").asString());
        assertEquals("body", body.path("tag").path("in").asString());
    }

    @Test
    void importShouldWriteConsumedOutputParametersAsTypedJsonPathMap() throws Exception {
        JsonNode outputs = IkanosMappers.yaml()
                .readTree(importToYaml(RICH_OAS_30, "rich").toFile())
                .path("consumes").path(0).path("resources").path("pets-pet-id")
                .path("operations").path("show-pet-by-id").path("outputParameters");

        assertTrue(outputs.isObject(), "#871: outputParameters must be a map keyed by name");
        assertEquals("number", outputs.path("id").path("type").asString());
        assertEquals("$.id", outputs.path("id").path("value").asString());
        assertEquals("object", outputs.path("owner").path("type").asString());
        assertEquals("$.owner", outputs.path("owner").path("value").asString());
        assertFalse(outputs.path("owner").has("properties"),
                "#871: ConsumedOutputParameter only allows type and value");
        assertEquals("array", outputs.path("tags").path("type").asString());
        assertEquals("$.tags[*]", outputs.path("tags").path("value").asString());
        assertFalse(outputs.path("tags").has("items"),
                "#871: ConsumedOutputParameter only allows type and value");
    }

    @Test
    void importShouldWriteAuthenticationWithoutYamlTypeTags() throws Exception {
        Path output = importToYaml(RICH_OAS_30, "rich");

        String content = Files.readString(output);
        assertFalse(content.contains("!<"), "#871: no YAML type tag in the output");
        JsonNode auth = IkanosMappers.yaml().readTree(output.toFile())
                .path("consumes").path(0).path("authentication");
        assertEquals("bearer", auth.path("type").asString());
        assertEquals("{{BEARER_TOKEN}}", auth.path("token").asString());
    }

    @Test
    void importedYamlShouldLoadBackIntoTheSpecModel() throws Exception {
        Path output = importToYaml(RICH_OAS_30, "rich");

        IkanosSpec spec = IkanosMappers.yaml().readValue(output.toFile(), IkanosSpec.class);

        HttpClientSpec adapter = (HttpClientSpec) spec.getConsumes().get(0);
        assertEquals("rich-api", adapter.getNamespace());
        HttpClientOperationSpec showPet = adapter.getResources().get("pets-pet-id")
                .getOperations().get("show-pet-by-id");
        assertEquals("show-pet-by-id", showPet.getName());
        InputParameterSpec petId = showPet.getInputParameters().get(0);
        assertEquals("petId", petId.getName());
        assertEquals("path", petId.getIn());
        OutputParameterSpec id = showPet.getOutputParameters().stream()
                .filter(p -> "id".equals(p.getName())).findFirst().orElseThrow();
        assertEquals("$.id", id.getMapping(),
                "#871: a reloaded consumed output must keep its JsonPath as the mapping");
    }

}
