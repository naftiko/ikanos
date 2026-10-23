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
package io.ikanos.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates the {@code consumes[].baseUri} pattern, including binding placeholders (#745).
 */
class ConsumesBaseUriSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ConsumesBaseUriSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validate(String baseUri) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Base URI"
                  description: "baseUri pattern test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "api"
                      baseUri: '%s'
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: "GET"
                  exposes:
                    - type: "mcp"
                      namespace: "api-mcp"
                      port: 3001
                      tools:
                        list-items:
                          description: "List items."
                          call: "api.list-items"
                """.formatted(VersionHelper.getSchemaVersion(), baseUri);
        return schema.validate(YAML.readTree(yaml));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.getlago.com/api/v1",
            "http://localhost:3000",
            "https://{{LAGO_HOST}}/api/v1",
            "{{LAGO_API_URL}}"})
    void schemaShouldAcceptBaseUri(String baseUri) throws Exception {
        List<Error> errors = validate(baseUri);
        assertTrue(errors.isEmpty(), "Expected no errors for " + baseUri + ", got: " + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://api.test",
            "https://api.test/{{tenant}}/v1",
            "{{API_URL}}/v1",
            "prefix{{API_URL}}"})
    void schemaShouldRejectBaseUri(String baseUri) throws Exception {
        assertFalse(validate(baseUri).isEmpty(), baseUri + " must be rejected");
    }
}
