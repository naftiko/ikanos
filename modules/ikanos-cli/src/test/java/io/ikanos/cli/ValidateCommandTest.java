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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.exposes.ServerSpecDeserializer;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import picocli.CommandLine;

public class ValidateCommandTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path tempDir;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream outCapture;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        originalErr = System.err;
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

        assertEquals("test", yamlNode.path("name").asString());
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
        assertDoesNotThrow(() -> Files.writeString(yaml, "ikanos: \"%s\"\n".formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "9.9");

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Schema ikanos-schema-v9.9.json is not supported"));
    }

    @Test
    public void callShouldFailWhenValidationDetectsSchemaErrors() {
        Path yaml = tempDir.resolve("invalid-capability.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, "ikanos: \"%s\"\ninfo: {}\n".formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Validation failed"));
    }

    @Test
    public void callShouldFailWhenConsumedTemplateCannotBeParsed() {
        Path yaml = tempDir.resolve("bad-template.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            create:
                              method: "POST"
                              body:
                                type: "json"
                                data: '{"a":1{{#x}},"x":"{{x}}"}'
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      tools:
                        create:
                          description: "Create an item"
                          call: "svc.create"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode, "#849: a template the engine cannot parse must fail validate");
        assertTrue(errCapture.toString().contains("svc.create"), errCapture.toString());
    }

    /** A capability whose MCP tool declares one input parameter, written as {@code param}. */
    private Path toolInputCapability(String param) {
        Path yaml = tempDir.resolve("tool-input-default.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "crm"
                      baseUri: "https://crm.example.com"
                      resources:
                        accounts:
                          path: "/accounts"
                          operations:
                            ask:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      tools:
                        ask-account:
                          description: "Ask about an account"
                          call: "crm.ask"
                          inputParameters:
                            %s
                """.formatted(IKANOS, param)));
        return yaml;
    }

    @Test
    public void callShouldFailWhenToolInputDefaultDoesNotMatchType() {
        Path yaml = toolInputCapability("size: { type: \"integer\", description: \"Size\","
                + " required: false, default: \"200\" }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "#979: a default must match its type\n" + err + outCapture);
        assertTrue(err.contains("'tools.ask-account' input parameter 'size'"), err);
    }

    @Test
    public void callShouldFailWhenToolInputDefaultIsNotInEnum() {
        Path yaml = toolInputCapability("period: { type: \"string\", description: \"Period\","
                + " required: false, enum: [\"LAST_7DAYS\"], default: \"LAST_YEAR\" }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "#979: a default must be one of the enum values\n" + err);
        assertTrue(err.contains("default is not one of the enum values"), err);
    }

    @Test
    public void callShouldWarnWithoutFailingWhenRequiredToolInputDeclaresDefault() {
        Path yaml = toolInputCapability("period: { type: \"string\", description: \"Period\","
                + " required: true, default: \"LAST_90DAYS\" }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String out = outCapture.toString();
        assertEquals(0, exitCode, "#979: a default on a required input is a warning only\n"
                + errCapture + out);
        assertTrue(out.contains("is required, so its default never applies"), out);
    }

    @Test
    public void callShouldSucceedWhenToolInputDefaultMatchesTypeAndEnum() {
        Path yaml = toolInputCapability("period: { type: \"string\", description: \"Period\","
                + " required: false, enum: [\"LAST_7DAYS\", \"LAST_90DAYS\"],"
                + " default: \"LAST_90DAYS\" }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, errCapture.toString() + outCapture);
        assertFalse(outCapture.toString().contains("Warning"), outCapture.toString());
    }

    @Test
    public void callShouldFailWhenAToolUsesAnUnknownInputParameterSet() {
        Path yaml = tempDir.resolve("unknown-set.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, definitionsCapability("""
                      tools:
                        list:
                          description: "List items"
                          inputParameterSets: [paging]
                          call: "svc.list"
                """)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode, "#910: an unknown definition must fail validate");
        assertTrue(errCapture.toString().contains("Invalid definitions: MCP adapter 'tools', tool"
                + " 'list': unknown input parameter set 'paging'. MCP adapter 'tools' declares"
                + " odata-list."), errCapture.toString());
    }

    @Test
    public void callShouldWarnButPassWhenADefinitionIsUnused() {
        Path yaml = tempDir.resolve("unused-set.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, definitionsCapability("""
                      tools:
                        list:
                          description: "List items"
                          call: "svc.list"
                """)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, errCapture.toString());
        assertTrue(outCapture.toString().contains("Warnings:"), outCapture.toString());
        assertTrue(outCapture.toString().contains("MCP adapter 'tools' declares the input"
                + " parameter set 'odata-list', which no tool uses."), outCapture.toString());
    }

    @Test
    public void callShouldPassWhenToolsUseTheirDefinitions() {
        Path yaml = tempDir.resolve("used-set.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, definitionsCapability("""
                      tools:
                        list:
                          description: "List items"
                          inputParameterSets: [odata-list]
                          call: "svc.list"
                """)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, errCapture.toString());
        assertFalse(outCapture.toString().contains("Warnings:"), outCapture.toString());
    }

    private static String definitionsCapability(String tools) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      definitions:
                        inputParameterSets:
                          odata-list:
                            filter: { type: string, required: false, description: "Filter." }
                %s""".formatted(IKANOS, tools);
    }

    @Test
    public void callShouldNameAYamlAliasInsteadOfReportingASchemaMismatch() {
        Path yaml = tempDir.resolve("alias.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      tools:
                        list-a:
                          description: "List A"
                          inputParameters: &inputs
                            - name: "filter"
                              type: "string"
                              description: "Filter"
                          call: "svc.list"
                        list-b:
                          description: "List B"
                          inputParameters: *inputs
                          call: "svc.list"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode);
        String err = errCapture.toString();
        assertTrue(err.contains("YAML alias '*inputs' is not supported"), err);
        assertTrue(err.contains("line: 27"), err);
    }

    @Test
    public void callShouldReportTheMigrationForARemovedSkillAdapter() {
        Path yaml = tempDir.resolve("skill-adapter.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Legacy skills"
                  description: "Still declares the removed skill adapter"
                capability:
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      description: "Tools"
                      tools:
                        hello:
                          description: "Say hello"
                          outputParameters:
                            message:
                              type: "string"
                              value: "hi"
                    - type: "skill"
                      port: 3001
                      namespace: "skills"
                      skills:
                        greetings:
                          description: "Greets people"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode);
        assertTrue(err.contains(ServerSpecDeserializer.SKILL_ADAPTER_REMOVED), err);
        assertTrue(err.contains("Path: $.capability.exposes[1]"), err);
        assertFalse(err.contains("[2]"),
                "The removed adapter should not also be reported as a schema mismatch:\n" + err);
    }

    /** #842: whatever the engine refuses to load, {@code validate} refuses first. */
    @Test
    public void callShouldRejectOAuth2OnAConsumedAdapter() {
        Path yaml = tempDir.resolve("oauth2-consumes.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "OAuth2 upstream"
                  description: "Consumes an API with oauth2, which the engine can't send"
                capability:
                  consumes:
                    - namespace: "crm"
                      type: "http"
                      baseUri: "https://crm.example.com"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "https://auth.example.com"
                        resource: "https://crm.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: "GET"
                  exposes:
                    - type: "rest"
                      port: 3000
                      namespace: "demo"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            get:
                              method: "GET"
                              call: "crm.list"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, err + outCapture);
        assertTrue(err.contains("Path: $.capability.consumes[0].authentication"), err);
    }

    /** #964: an apikey without a value can't be sent, so validate refuses it before the engine. */
    @Test
    public void callShouldRejectAnApiKeyWithoutAValueOnAConsumedAdapter() {
        Path yaml = tempDir.resolve("apikey-without-value.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Apikey upstream"
                  description: "Consumes an API with an apikey that has no value"
                capability:
                  consumes:
                    - namespace: "crm"
                      type: "http"
                      baseUri: "https://crm.example.com"
                      authentication:
                        type: "apikey"
                        key: "X-Api-Key"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: "GET"
                  exposes:
                    - type: "rest"
                      port: 3000
                      namespace: "demo"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            get:
                              method: "GET"
                              call: "crm.list"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, err + outCapture);
        assertTrue(err.contains("Path: $.capability.consumes[0].authentication"), err);
    }

    @Test
    public void removedSkillAdaptersShouldBeFoundInStandaloneExposesFiles() throws Exception {
        JsonNode data = IkanosMappers.yaml().readTree("""
                ikanos: "1.0.0-beta9"
                exposes:
                  - type: "rest"
                    port: 3000
                  - type: "skill"
                    port: 3001
                  - from: "./shared.yml"
                    type: "skill"
                """);

        assertEquals(java.util.List.of("$.exposes[1]"), ValidateCommand.removedSkillAdapters(data));
    }

    @Test
    public void callShouldSucceedForValidCapabilityYaml() {
        Path yaml = tutorialCapabilityPath();

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
        assertDoesNotThrow(() -> Files.writeString(yaml, "ikanos: \"%s\"\n".formatted(IKANOS)));

        int exitCode = new CommandLine(command).execute(yaml.toString());

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Error: boom"));
    }

    @Test
    public void callShouldValidateWithV7SchemaWhenProvided() throws Exception {
        Path yaml = tempDir.resolve("v7-schema.yaml");
        Files.writeString(yaml, "name: example\n");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "7");

        assertEquals(0, exitCode);
        assertTrue(outCapture.toString().contains("Validation successful"));
    }

    @Test
    public void callShouldValidateWithJson201909SchemaWhenProvided() throws Exception {
        Path yaml = tempDir.resolve("v2019-schema.yaml");
        Files.writeString(yaml, "name: example\n");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "2019");

        assertEquals(0, exitCode);
        assertTrue(outCapture.toString().contains("Validation successful"));
    }

    @Test
    public void callShouldUseSpecVersionParameterWhenProvided() {
        Path yaml = tutorialCapabilityPath();

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString(), "1.0.0");

        assertEquals(1, exitCode);
        String output = errCapture.toString();
        assertTrue(output.contains("Schema ikanos-schema-v1.0.0.json"));
        assertTrue(output.contains("is not supported"));
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

    private Path tutorialCapabilityPath() {
        String projectBasedir = System.getProperty("user.dir");
        Path mavenPath = Path.of(projectBasedir, "..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml");
        if (Files.exists(mavenPath)) {
            return mavenPath;
        }
        return Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml")
                .toAbsolutePath().normalize();
    }
}