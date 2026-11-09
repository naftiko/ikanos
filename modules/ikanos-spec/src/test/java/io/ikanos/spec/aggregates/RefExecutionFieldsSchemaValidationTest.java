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
package io.ikanos.spec.aggregates;

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

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * An MCP tool or REST operation that uses {@code ref} runs the aggregate flow, so the schema
 * rejects {@code call}, {@code steps} and {@code mappings} next to it (#911). {@code with},
 * {@code inputParameters} and {@code outputParameters} adapt the flow and stay allowed.
 */
class RefExecutionFieldsSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;
    private static final String TOOL = "/capability/exposes/0/tools/list-things";
    private static final String OPERATION =
            "/capability/exposes/1/resources/things/operations/list";

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = RefExecutionFieldsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validate(String toolFields, String restFields) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Ref"
                  description: "Fields next to ref"
                capability:
                  aggregates:
                    - namespace: "things"
                      display: "Things"
                      flows:
                        list:
                          description: "List things."
                          call: "api.list-things"
                  consumes:
                    - type: "http"
                      namespace: "api"
                      baseUri: "https://api.example.test"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list-things:
                              method: "GET"
                            list-other:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      namespace: "things-mcp"
                      port: 3001
                      tools:
                        list-things:
                          description: "List things."
                          ref: "things.list"
                %s
                    - type: "rest"
                      namespace: "things-rest"
                      port: 3002
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: "GET"
                              ref: "things.list"
                %s
                """.formatted(IKANOS, indent(toolFields, 10), indent(restFields, 14));
        return schema.validate(YAML.readTree(yaml));
    }

    private static String indent(String block, int spaces) {
        if (block.isBlank()) {
            return "";
        }
        String pad = " ".repeat(spaces);
        StringBuilder out = new StringBuilder();
        for (String line : block.strip().split("\n")) {
            out.append(pad).append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** Asserts that the {@code not} rule next to {@code ref} reported an error on that unit. */
    private static void assertRejectedAt(String location, List<Error> errors, String message) {
        assertTrue(errors.stream().anyMatch(e -> "not".equals(e.getKeyword())
                && location.equals(String.valueOf(e.getInstanceLocation()))),
                message + "; got: " + errors);
    }

    @Test
    void schemaShouldAcceptRefAlone() throws Exception {
        List<Error> errors = validate("", "");
        assertTrue(errors.isEmpty(), "the fixture itself must be valid; got: " + errors);
    }

    @Test
    void schemaShouldRejectCallNextToRefOnMcpTool() throws Exception {
        assertRejectedAt(TOOL, validate("call: \"api.list-other\"", ""),
                "#911: call is ignored next to ref, so it must be invalid");
    }

    @Test
    void schemaShouldRejectStepsNextToRefOnMcpTool() throws Exception {
        assertRejectedAt(TOOL, validate("""
                steps:
                  list:
                    type: "call"
                    call: "api.list-other"
                """, ""),
                "#911: steps is ignored next to ref, so it must be invalid");
    }

    @Test
    void schemaShouldRejectMappingsNextToRefOnMcpTool() throws Exception {
        assertRejectedAt(TOOL, validate("""
                mappings:
                  - target: "items"
                    value: "$.list.items"
                """, ""),
                "#911: mappings is ignored next to ref, so it must be invalid");
    }

    @Test
    void schemaShouldRejectCallNextToRefOnRestOperation() throws Exception {
        assertRejectedAt(OPERATION, validate("", "call: \"api.list-other\""),
                "#911: call is ignored next to ref, so it must be invalid");
    }

    @Test
    void schemaShouldRejectStepsNextToRefOnRestOperation() throws Exception {
        assertRejectedAt(OPERATION, validate("", """
                steps:
                  list:
                    type: "call"
                    call: "api.list-other"
                """),
                "#911: steps is ignored next to ref, so it must be invalid");
    }

    @Test
    void schemaShouldRejectMappingsNextToRefOnRestOperation() throws Exception {
        assertRejectedAt(OPERATION, validate("", """
                mappings:
                  - target: "items"
                    value: "$.list.items"
                """),
                "#911: mappings is ignored next to ref, so it must be invalid");
    }

    @Test
    void schemaShouldAcceptRefWithWithAndParameters() throws Exception {
        List<Error> errors = validate("""
                inputParameters:
                  key:
                    type: "string"
                    description: "Key."
                with:
                  filter: "{{key}}"
                """, """
                inputParameters:
                  key:
                    in: "query"
                    type: "string"
                    description: "Key."
                with:
                  filter: "{{key}}"
                """);
        assertTrue(errors.isEmpty(), "with and inputParameters adapt the flow; got: " + errors);
    }
}
