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

    @Test
    public void callShouldFailWhenNestedAuthorizationSitsOnAnAdapterWithoutOAuth2() {
        Path yaml = authorizationCapability("", "records.read");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "#999: authorization needs oauth2\n" + err + outCapture);
        assertTrue(err.contains("Invalid authorization"), err);
        assertTrue(err.contains("needs 'authentication: oauth2'"), err);
    }

    @Test
    public void callShouldFailWhenAuthorizationScopeIsNotInAuthenticationScopes() {
        Path yaml = authorizationCapability(OAUTH2, "records.admin");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "#999: a required scope must be recognized\n" + err + outCapture);
        assertTrue(err.contains("'records.admin'"), err);
        assertTrue(err.contains("not listed in 'authentication.scopes'"), err);
    }

    @Test
    public void callShouldSucceedWhenAuthorizationScopeIsRecognized() {
        Path yaml = authorizationCapability(OAUTH2, "records.read");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, errCapture.toString() + outCapture);
    }

    @Test
    public void callShouldWarnWhenAuthenticationScopesAreNoLongerRequired() {
        // #999: up to 1.0.0-beta9, authentication.scopes were required on every call
        Path yaml = authorizationCapability(OAUTH2, null);

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String out = outCapture.toString();
        assertEquals(0, exitCode, errCapture.toString() + out);
        assertTrue(out.contains("Warnings:"), out);
        assertTrue(out.contains("REST adapter 'records-api'"), out);
        assertTrue(out.contains("'authorization'"), out);
    }

    private static final String OAUTH2 = """
                  authentication:
                    type: "oauth2"
                    authorizationServerUri: "https://idp.example.com"
                    resource: "https://api.example.com"
                    scopes: ["records.read"]
            """;

    /** A REST capability whose operation requires {@code scope}, or nothing when it is null. */
    private Path authorizationCapability(String authentication, String scope) {
        Path yaml = tempDir.resolve("authorization.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "crm"
                      baseUri: "https://crm.example.com"
                      resources:
                        records:
                          path: "/records"
                          operations:
                            list-records:
                              method: "GET"
                  exposes:
                    - type: "rest"
                      port: 8080
                      namespace: "records-api"
                %s
                      resources:
                        records:
                          path: "/records"
                          operations:
                            list-records:
                              method: "GET"
                              call: "crm.list-records"
                %s""".formatted(IKANOS, authentication, scope == null ? ""
                : "              authorization:\n                scopes: [\"" + scope + "\"]\n")));
        return yaml;
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
    public void callShouldFailWhenToolInputExampleDoesNotMatchType() {
        Path yaml = toolInputCapability("size: { type: \"integer\", description: \"Size\","
                + " examples: [25.0, 2.5] }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "an example must match its type\n" + err + outCapture);
        assertTrue(err.contains("Invalid input example: MCP tool 'tools.ask-account' input"
                + " parameter 'size': example '2.5' does not match type 'integer'"), err);
        assertFalse(err.contains("'25.0'"), "25.0 is an integer, as a default: " + err);
    }

    @Test
    public void callShouldFailWhenToolInputExampleIsNotInEnum() {
        Path yaml = toolInputCapability("severity: { type: \"string\", description: \"S\","
                + " enum: [\"sev1\", \"sev2\"], examples: [\"sev9\"] }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "an example must be one of the enum values\n" + err);
        assertTrue(err.contains("example 'sev9' is not one of the enum values"), err);
    }

    @Test
    public void callShouldSucceedWhenToolInputExamplesMatchTypeAndEnum() {
        Path yaml = toolInputCapability("severity: { type: \"string\", description: \"S\","
                + " enum: [\"sev1\", \"sev2\"], examples: [\"sev2\"] }");

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, errCapture.toString() + outCapture);
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
    public void callShouldFailWhenAForwardTrustsAResponseHeaderItCanNeverRelay() {
        Path yaml = tempDir.resolve("bad-response-header.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "https://api.example.com"
                  exposes:
                    - type: "rest"
                      port: 3000
                      namespace: "proxy"
                      resources:
                        proxy:
                          path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedResponseHeaders:
                              - Transfer-Encoding
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode, "#1002: validate must refuse what the engine refuses to load");
        assertTrue(errCapture.toString().contains("Transfer-Encoding")
                && errCapture.toString().contains("hop-by-hop"), errCapture.toString());
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
    public void callShouldFailWhenARestOutputUsesAnUnknownSchema() {
        Path yaml = tempDir.resolve("rest-unknown-schema.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: rest
                      port: 3002
                      namespace: api
                      definitions:
                        schemas:
                          range: { type: object, properties: { from: { type: string } } }
                      resources:
                        items:
                          path: /items
                          operations:
                            list-items:
                              method: GET
                              steps:
                                - {name: items, type: call, what: svc.list }
                              mappings:
                                - target: first
                                  value: "$.items"
                              outputParameters:
                                first: { schema: range }
                                second: { schema: rang }
                  consumes:
                    - type: http
                      namespace: svc
                      baseUri: "http://localhost:1"
                      resources:
                        items:
                          path: /items
                          operations:
                            list:
                              method: GET
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode, "#1000: an unknown schema of a REST adapter must fail validate");
        assertTrue(errCapture.toString().contains("Invalid definitions: REST adapter 'api',"
                + " resource 'items', operation 'list-items', output 'second': unknown schema"
                + " 'rang'. REST adapter 'api' declares range."), errCapture.toString());
    }

    @Test
    public void callShouldFailWhenAMappedOutputLeavesAPathUnbound() {
        Path yaml = tempDir.resolve("unbound-path.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: rest
                      port: 3002
                      namespace: api
                      definitions:
                        schemas:
                          item:
                            type: object
                            properties:
                              id: { type: string }
                              label: { type: string }
                      resources:
                        items:
                          path: /items
                          operations:
                            list-items:
                              method: GET
                              call: svc.list
                              outputParameters:
                                - type: array
                                  mapping: "$.items"
                                  items:
                                    schema: item
                                    mappings:
                                      id: "$.id"
                  consumes:
                    - type: http
                      namespace: svc
                      baseUri: "http://localhost:1"
                      resources:
                        items:
                          path: /items
                          operations:
                            list:
                              method: GET
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode, "#1036: an unbound path of a mapped output must fail validate");
        assertTrue(errCapture.toString().contains("Invalid definitions: REST adapter 'api',"
                + " resource 'items', operation 'list-items', output, items: 'schema: item'"
                + " leaves paths unbound: [label]."), errCapture.toString());
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
    public void callShouldAcceptAQueryParametersList() {
        int exitCode = new CommandLine(new ValidateCommand())
                .execute(queryParametersCapability("[state, page]", "").toString());

        assertEquals(0, exitCode, "#987: " + errCapture);
    }

    @Test
    public void callShouldFailWhenAQueryParameterNameIsAlsoInInputParameters() {
        Path yaml = queryParametersCapability("[state, page]", """
                              inputParameters:
                                state:
                                  in: "query"
                """);

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Invalid consumed query parameter: svc.list"
                + " declares 'state' in both inputParameters and queryParameters"),
                errCapture.toString());
    }

    @Test
    public void callShouldFailWhenAQueryParameterNameIsListedTwice() {
        int exitCode = new CommandLine(new ValidateCommand())
                .execute(queryParametersCapability("[state, state]", "").toString());

        assertEquals(1, exitCode, "#987: the schema requires unique names");
        assertTrue(errCapture.toString().contains("queryParameters"), errCapture.toString());
    }

    private Path queryParametersCapability(String names, String operationInputParameters) {
        Path yaml = tempDir.resolve("query-parameters.yaml");
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
                          queryParameters: [per_page]
                          operations:
                            list:
                              method: "GET"
                              queryParameters: %s
                %s  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      tools:
                        list:
                          description: "List items"
                          call: "svc.list"
                """.formatted(IKANOS, names, operationInputParameters)));
        return yaml;
    }

    @Test
    public void callShouldAcceptAnMcpToolWhoseCallIsInferredFromItsName() {
        Path yaml = tempDir.resolve("inferred-call.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, inferredCallCapability(
                "list-items", "list-items").formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, errCapture.toString());
    }

    @Test
    public void callShouldFailWhenNoConsumedNamespaceHasTheToolName() {
        Path yaml = tempDir.resolve("inferred-call-missing.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, inferredCallCapability(
                "list-items", "list-things").formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(1, exitCode, "#988: a tool whose call cannot be inferred must fail validate");
        assertTrue(errCapture.toString().contains("MCP tool 'list-things'"), errCapture.toString());
    }

    /** One consumed operation and one MCP tool without {@code call}, mapping its result. */
    private static String inferredCallCapability(String operation, String tool) {
        return """
                ikanos: "%%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            %s:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      tools:
                        %s:
                          description: "List the items"
                          outputParameters:
                            - type: "string"
                              mapping: "$.title"
                """.formatted(operation, tool);
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
    public void callShouldFailWhenForwardTrustsAHeaderTheEngineCannotSend() {
        Path yaml = tempDir.resolve("forward-host.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Forward proxy"
                  description: "Forwards to an upstream and trusts a header it can't send"
                capability:
                  consumes:
                    - namespace: "up"
                      type: "http"
                      baseUri: "https://api.example.com"
                  exposes:
                    - type: "rest"
                      port: 3000
                      namespace: "demo"
                      resources:
                        proxy:
                          path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: "up"
                            trustedHeaders:
                              - "Host"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "#1001: a trusted header the engine can never send must fail "
                + "validate, not be dropped on every request. " + err + outCapture);
        assertTrue(err.contains("Host"), err);
    }

    @Test
    public void callShouldFailWhenAStaticHeaderInputCannotBeParsed() {
        Path yaml = tempDir.resolve("static-if-match.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Conditional writes"
                  description: "Sends a fixed precondition that isn't a valid entity tag"
                capability:
                  consumes:
                    - namespace: "up"
                      type: "http"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            update:
                              method: "PUT"
                              inputParameters:
                                If-Match:
                                  in: "header"
                                  value: "v1"
                  exposes:
                    - type: "rest"
                      port: 3000
                      namespace: "demo"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            put:
                              method: "PUT"
                              call: "up.update"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode, "#1001: a static header value the engine can't send must "
                + "fail validate. " + err + outCapture);
        assertTrue(err.contains("If-Match"), err);
    }

    /**
     * #1001: a precondition header input is required by default, and {@code required: false} is
     * the documented way to keep it optional, so the schema must accept it on a consumed input.
     */
    @Test
    public void callShouldAcceptRequiredFalseOnAConsumedHeaderInput() {
        Path yaml = tempDir.resolve("optional-if-match.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Conditional writes"
                  description: "Sends an If-Match only when the caller gives one"
                capability:
                  consumes:
                    - namespace: "up"
                      type: "http"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            update:
                              method: "PUT"
                              inputParameters:
                                If-Match:
                                  in: "header"
                                  value: "{{etag}}"
                                  required: false
                  exposes:
                    - type: "rest"
                      port: 3000
                      namespace: "demo"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            put:
                              method: "PUT"
                              call: "up.update"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        assertEquals(0, exitCode, "#1001: required: false keeps a precondition input optional "
                + "and must validate. " + errCapture + outCapture);
    }

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
    public void callShouldReportWhereToMoveRootBindsInACapabilityDocument() {
        Path yaml = tempDir.resolve("root-binds.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Root binds"
                  description: "Declares its bindings at the root"
                binds:
                  - namespace: "env"
                    keys:
                      TOKEN: "TOKEN"
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
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode);
        assertTrue(err.contains("move it under 'capability.binds'"), err);
        assertTrue(err.contains("Path: $.binds"), err);
        assertFalse(err.contains("[2]"),
                "Root binds should not also be reported as a schema mismatch:\n" + err);
    }

    /** A capability whose MCP tool runs one step guarded by {@code onlyIf}. */
    private Path guardedStepCapability(String onlyIf) {
        Path yaml = tempDir.resolve("guarded-step.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Guarded step"
                  description: "A step that runs only when its guard holds"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "api"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      description: "Tools"
                      tools:
                        list:
                          description: "List the items"
                          steps:
                            - name: "fetch"
                              type: "call"
                              what: "api.list-items"
                              onlyIf: "%s"
                          mappings:
                            - target: "items"
                              value: "$.fetch"
                          outputParameters:
                            items:
                              type: "array"
                """.formatted(IKANOS, onlyIf)));
        return yaml;
    }

    @Test
    public void callShouldReportAStepGuardThatDoesNotParse() {
        int exitCode = new CommandLine(new ValidateCommand())
                .execute(guardedStepCapability("auth.claims.sub ==").toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode);
        assertTrue(err.contains("Invalid step guard: The onlyIf of step 'fetch' in MCP tool 'list'"),
                err);
    }

    @Test
    public void callShouldWarnAboutAStepGuardThatReadsANameNothingProvides() {
        int exitCode = new CommandLine(new ValidateCommand())
                .execute(guardedStepCapability("ordr.status == 'open'").toString());

        String out = outCapture.toString();
        assertEquals(0, exitCode, errCapture.toString());
        assertTrue(out.contains("reads 'ordr'"), out);
    }

    @Test
    public void callShouldReportAStepMapWithTheMigrateHintAndNothingElse() {
        Path yaml = tempDir.resolve("step-map.yaml");
        assertDoesNotThrow(() -> Files.writeString(yaml, """
                ikanos: "%s"
                info:
                  display: "Step map"
                  description: "Steps written as a map keyed by step name"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "api"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      description: "Tools"
                      tools:
                        list:
                          description: "List the items"
                          steps:
                            fetch:
                              type: "call"
                              call: "api.list-items"
                          mappings:
                            - target: "items"
                              value: "$.fetch"
                          outputParameters:
                            items:
                              type: "array"
                """.formatted(IKANOS)));

        int exitCode = new CommandLine(new ValidateCommand()).execute(yaml.toString());

        String err = errCapture.toString();
        assertEquals(1, exitCode);
        assertTrue(err.contains("ikanos migrate"), err);
        assertTrue(err.contains("Path: $.capability.exposes[0].tools.list.steps"), err);
        assertFalse(err.contains("[2]"),
                "A step map should not also be reported as a schema mismatch:\n" + err);
    }

    @Test
    public void findEarlierFormsShouldFindACallInAStepListThatAlreadyHasNames() throws Exception {
        JsonNode data = IkanosMappers.yaml().readTree("""
                flows:
                  f:
                    steps:
                      - name: fetch
                        type: call
                        call: api.list-items
                  g:
                    steps:
                      - name: fetch
                        type: call
                        what: api.list-items
                """);

        assertEquals(java.util.List.of("$.flows.f.steps"), StepsMigrator.findEarlierForms(data));
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
    public void removedSkillAdaptersShouldSkipRootExposesOfACapabilityDocument() throws Exception {
        JsonNode data = IkanosMappers.yaml().readTree("""
                ikanos: "%s"
                exposes:
                  - type: "skill"
                    port: 3001
                capability:
                  exposes:
                    - type: "skill"
                      port: 3002
                """.formatted(IKANOS));

        assertEquals(java.util.List.of("$.capability.exposes[0]"),
                ValidateCommand.removedSkillAdapters(data));
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