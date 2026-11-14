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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * REST {@code ttlMs} and {@code cacheScope}, on the adapter and on an operation, in the Ikanos
 * schema (#1021).
 */
class RestCacheControlSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = RestCacheControlSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ttlMs: 0", "ttlMs: 1", "ttlMs: 2147483647", "cacheScope: public",
            "cacheScope: private", "cacheScope: auto"})
    void schemaShouldAcceptValidCacheSettingsOnTheAdapter(String line) throws Exception {
        assertValid(rest(line, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ttlMs: 0", "ttlMs: 1", "ttlMs: 2147483647", "cacheScope: public",
            "cacheScope: private", "cacheScope: auto"})
    void schemaShouldAcceptValidCacheSettingsOnAnOperation(String line) throws Exception {
        assertValid(rest("", line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ttlMs: -1", "ttlMs: 2.5", "ttlMs: \"60000\"", "ttlMs: 2147483648",
            "cacheScope: shared", "cacheScope: PUBLIC", "cacheScope: \"\""})
    void schemaShouldRejectInvalidCacheSettingsOnTheAdapter(String line) throws Exception {
        assertInvalid(rest(line, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ttlMs: -1", "ttlMs: 2.5", "ttlMs: \"60000\"", "ttlMs: 2147483648",
            "cacheScope: shared", "cacheScope: PUBLIC", "cacheScope: \"\""})
    void schemaShouldRejectInvalidCacheSettingsOnAnOperation(String line) throws Exception {
        assertInvalid(rest("", line));
    }

    static String rest(String adapterLine, String operationLine) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 8080
                    namespace: demo
                    %s
                    resources:
                      products:
                        path: "/products"
                        description: "Products"
                        operations:
                          list-products:
                            method: GET
                            description: "List products"
                            %s
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                """.formatted(IKANOS, adapterLine, operationLine);
    }

    static void assertValid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertTrue(errors.isEmpty(), "Expected valid capability, got:\n" + errors + "\n" + yaml);
    }

    static void assertInvalid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertFalse(errors.isEmpty(), "Expected schema errors for:\n" + yaml);
    }
}
