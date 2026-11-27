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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * File inputs ({@code type: file}, experimental) on aggregate flows and MCP tools, and the
 * {@code binary} request body: schema validation and the spec model.
 */
class McpFileInputsSpecTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpFileInputsSpecTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String input, String body) {
        return """
                ikanos: "%s"
                info:
                  display: "File inputs"
                  description: "File input test"
                capability:
                  aggregates:
                    - display: "Documents"
                      namespace: "documents"
                      flows:
                        import-report:
                          description: "Import a lab report."
                          inputParameters:
                            report:
                %s
                          call: "records.upload"
                  consumes:
                    - type: "http"
                      namespace: "records"
                      baseUri: "https://api.records.test"
                      resources:
                        reports:
                          path: "/reports"
                          operations:
                            upload:
                              method: "POST"
                %s
                  exposes:
                    - type: "mcp"
                      namespace: "documents-mcp"
                      port: 3001
                      files: {}
                      tools:
                        import-report:
                          description: "Import a lab report."
                          ref: "documents.import-report"
                """.formatted(IKANOS, input.indent(14).stripTrailing(),
                body.indent(14).stripTrailing());
    }

    private static final String MULTIPART = """
            body:
              type: multipartForm
              data:
                - name: file
                  value: "{{report.data}}"
                  encoding: base64
                  filename: "{{report.name}}"
                  contentType: "{{report.mimeType}}"
            """;

    private static List<Error> validate(String input, String body) throws Exception {
        return schema.validate(YAML.readTree(capability(input, body)));
    }

    @Test
    void schemaShouldAcceptAFileInputWithAcceptAndMaxSize() throws Exception {
        List<Error> errors = validate("""
                type: file
                accept: ["application/pdf", "text/csv", ".csv"]
                maxSize: 50MiB
                required: true
                description: "The report to import"
                """, MULTIPART);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectAcceptOnAnInputThatIsNotAFile() throws Exception {
        assertFalse(validate("""
                type: string
                accept: ["application/pdf"]
                description: "The report to import"
                """, MULTIPART).isEmpty());
    }

    @Test
    void schemaShouldRejectMaxSizeWithoutUnit() throws Exception {
        assertFalse(validate("""
                type: file
                maxSize: "1024"
                description: "The report to import"
                """, MULTIPART).isEmpty());
    }

    @Test
    void schemaShouldAcceptABinaryBody() throws Exception {
        List<Error> errors = validate("""
                type: file
                description: "The report to import"
                """, """
                body:
                  type: binary
                  data: "{{report.data}}"
                  contentType: "{{report.mimeType}}"
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectABinaryBodyWithoutData() throws Exception {
        assertFalse(validate("""
                type: file
                description: "The report to import"
                """, """
                body:
                  type: binary
                  contentType: "application/pdf"
                """).isEmpty());
    }

    @Test
    void specShouldReadAcceptAndMaxSize() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("""
                type: file
                accept: ["application/pdf", ".csv"]
                maxSize: 50MiB
                description: "The report to import"
                """, MULTIPART), IkanosSpec.class);
        AggregateFlowSpec flow = spec.getCapability().getAggregates().get("documents").getFlows()
                .get("import-report");
        InputParameterSpec report = flow.getInputParameters().get("report");

        assertTrue(report.isFile());
        assertEquals(List.of("application/pdf", ".csv"), report.getAccept());
        assertEquals("50MiB", report.getMaxSize());
    }

    @Test
    void copyShouldKeepAcceptAndMaxSize() {
        InputParameterSpec input = new InputParameterSpec();
        input.setName("report");
        input.setType("file");
        input.getAccept().add("image/*");
        input.setMaxSize("1MiB");

        InputParameterSpec copy = input.copy();

        assertEquals(List.of("image/*"), copy.getAccept());
        assertEquals("1MiB", copy.getMaxSize());
    }

    @Test
    void specShouldWriteAcceptAndMaxSizeBack() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("""
                type: file
                accept: ["application/pdf"]
                maxSize: 5MiB
                description: "The report to import"
                """, MULTIPART), IkanosSpec.class);

        String written = YAML.writeValueAsString(spec);

        assertTrue(written.contains("application/pdf"), written);
        assertTrue(written.contains("maxSize: \"5MiB\"") || written.contains("maxSize: 5MiB"),
                written);
    }
}
