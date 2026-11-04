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
 * Validates the {@code fields} of an {@code elicit} step against what MCP elicitation accepts:
 * primitive types, {@code enum} of strings and {@code format} among email, uri, date, date-time,
 * both on string fields only.
 */
class ElicitFieldsSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ElicitFieldsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validate(String field) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Elicit fields"
                  description: "elicit fields test"
                capability:
                  aggregates:
                  - display: departure flows
                    namespace: departure
                    flows:
                      confirm-departure:
                        description: Ask the duty officer to confirm a departure.
                        steps:
                          confirm:
                            type: elicit
                            message: Confirm the departure?
                            fields:
                              answer: %s
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9315
                    namespace: shipyard-ops
                    description: Shipyard operations
                    tools:
                      confirm-departure:
                        description: Confirm a departure
                        ref: departure.confirm-departure
                """.formatted(VersionHelper.getSchemaVersion(), field);
        return schema.validate(YAML.readTree(yaml));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ type: boolean, description: Clear for departure }",
            "{ type: integer }",
            "{ type: string, format: email }",
            "{ type: string, format: date-time }",
            "{ type: string, enum: [berth-1, berth-4] }"})
    void schemaShouldAcceptField(String field) throws Exception {
        List<Error> errors = validate(field);
        assertTrue(errors.isEmpty(), "Expected no errors for " + field + ", got: " + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ type: string, format: phone }",
            "{ type: integer, format: email }",
            "{ type: number, enum: [1, 2] }",
            "{ type: string, enum: [1, 2] }",
            "{ type: string, enum: [] }",
            "{ type: object }",
            "{ description: Missing type }"})
    void schemaShouldRejectField(String field) throws Exception {
        assertFalse(validate(field).isEmpty(), field + " must be rejected");
    }
}
