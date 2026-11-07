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
package io.ikanos.spec.exposes.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * Validates the control port's {@code durability} block and a flow's {@code interaction} against
 * what this release serves: the modes {@code none}, {@code memory} and {@code local}, a store of
 * {@code type} and {@code path}, a {@code retention} that {@code java.time.Duration} parses, and
 * no {@code mayElicit} flag.
 */
class DurabilitySchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = DurabilitySchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static List<Error> validate(String durability, String interaction) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Durability"
                  description: "durability schema test"
                capability:
                  aggregates:
                  - display: departure flows
                    namespace: departure
                    flows:
                      get-voyage:
                        description: Mock voyage lookup.
                        semantics:
                          interaction: %s
                        outputParameters:
                          status:
                            type: string
                            value: planned
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9319
                    namespace: shipyard-ops
                    description: Shipyard operations
                    tools:
                      get-voyage:
                        description: Get a voyage
                        ref: departure.get-voyage
                  - type: control
                    address: localhost
                    port: 9320
                    durability: %s
                """.formatted(VersionHelper.getSchemaVersion(), interaction, durability);
        return schema.validate(YAML.readTree(yaml));
    }

    private static List<Error> validateDurability(String durability) throws Exception {
        return validate(durability, "{ mode: inline }");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ mode: none }",
            "{ mode: memory, retention: P7D }",
            "{ mode: memory, retention: PT12H }",
            "{ mode: memory, retention: P1DT2H30M }",
            "{ mode: memory, retention: PT0.5S }",
            "{ mode: local, store: { type: h2, path: ./data/durable } }"})
    void schemaShouldAcceptDurability(String durability) throws Exception {
        List<Error> errors = validateDurability(durability);
        assertTrue(errors.isEmpty(), "Expected no errors for " + durability + ", got: " + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ mode: distributed }",
            "{ mode: local, store: { type: jdbc, url: jdbc-url } }",
            "{ mode: memory, retention: 7D }",
            "{ mode: memory, retention: P }",
            "{ mode: memory, retention: PT }",
            "{ mode: memory, retention: P1Y }",
            "{ mode: memory, retention: P2W }"})
    void schemaShouldRejectDurability(String durability) throws Exception {
        assertFalse(validateDurability(durability).isEmpty(), durability + " must be rejected");
    }

    @Test
    void schemaShouldRejectMayElicit() throws Exception {
        assertFalse(validate("{ mode: memory }", "{ mode: task, mayElicit: true }").isEmpty(),
                "mayElicit must be rejected");
    }
}
