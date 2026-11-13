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

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Schema tests for an MCP tool whose {@code call} is inferred from its name (#988): a tool with
 * none of {@code call}, {@code steps}, {@code ref} or mock {@code outputParameters}.
 */
class McpToolInferredCallSchemaTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpToolInferredCallSchemaTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(IkanosMappers.json().readTree(in));
        }
    }

    @Test
    void schemaShouldAcceptToolWithoutCallOrOutputParameters() throws Exception {
        assertTrue(validate("").isEmpty());
    }

    @Test
    void schemaShouldAcceptToolWithoutCallWithMappedOutputParametersAndWith() throws Exception {
        List<Error> errors = validate("""
                          with:
                            state: "open"
                          outputParameters:
                            - type: "string"
                              mapping: "$.title"
                """);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void schemaShouldStillAcceptMockOutputParametersAsAMap() throws Exception {
        List<Error> errors = validate("""
                          outputParameters:
                            message:
                              type: "string"
                              value: "hello"
                """);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void schemaShouldRejectArrayOfStaticValuesWithoutCall() throws Exception {
        // The engine runs such a tool as a mock, so it is not an inferred call either.
        assertFalse(validate("""
                          outputParameters:
                            - type: "string"
                              value: "hello"
                """).isEmpty());
    }

    @Test
    void schemaShouldRejectToolWithoutCallOrDescription() throws Exception {
        assertFalse(validate("", "").isEmpty());
    }

    private static List<Error> validate(String toolFields) throws Exception {
        return validate("description: \"List the items\"", toolFields);
    }

    private static List<Error> validate(String description, String toolFields) throws Exception {
        String yaml = """
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
                            list-items:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      port: 3000
                      namespace: "tools"
                      tools:
                        list-items:
                          inputParameters:
                            state:
                              type: "string"
                              description: "Issue state"
                          %s
                %s""".formatted(IKANOS, description, toolFields);
        return schema.validate(IkanosMappers.yaml().readTree(yaml));
    }
}
