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
package io.ikanos.spec.exposes.rest;

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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates {@code forward.trustedResponseHeaders} against the schema (#1002).
 */
class ForwardConfigSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ForwardConfigSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validate(String forwardBlock) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://localhost:9090/v1"
                  exposes:
                    - type: "rest"
                      namespace: "proxy"
                      port: 8080
                      resources:
                        proxy:
                          path: "/proxy/{{path}}"
                          forward:
                %s
                """.formatted(VersionHelper.getSchemaVersion(), forwardBlock.indent(12).stripTrailing());
        return schema.validate(YAML.readTree(yaml));
    }

    @Test
    void schemaShouldAcceptTrustedResponseHeaders() throws Exception {
        List<Error> errors = validate("""
                targetNamespace: up
                trustedResponseHeaders:
                  - X-Request-Id
                  - Set-Cookie
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldAcceptForwardWithoutTrustedResponseHeaders() throws Exception {
        List<Error> errors = validate("""
                targetNamespace: up
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectEmptyTrustedResponseHeaders() throws Exception {
        assertFalse(validate("""
                targetNamespace: up
                trustedResponseHeaders: []
                """).isEmpty(), "an empty trustedResponseHeaders list must be rejected");
    }

    @Test
    void schemaShouldRejectTrustedResponseHeaderThatIsNotAHeaderName() throws Exception {
        assertFalse(validate("""
                targetNamespace: up
                trustedResponseHeaders:
                  - "X-Request-Id: abc"
                """).isEmpty(), "a value that is not an HTTP field name must be rejected");
    }
}
