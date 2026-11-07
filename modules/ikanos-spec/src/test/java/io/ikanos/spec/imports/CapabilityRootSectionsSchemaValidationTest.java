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
package io.ikanos.spec.imports;

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
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;

/**
 * A capability document has one technical root, {@code capability}. Root {@code binds},
 * {@code consumes}, {@code exposes} and {@code aggregates} are only valid in standalone files;
 * bindings of a capability go under {@code capability.binds} (#955).
 */
class CapabilityRootSectionsSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String CAPABILITY = """
            capability:
              exposes:
                - from: "./shared.exposes.yml"
                  import: "api"
            """;

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = CapabilityRootSectionsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            SchemaRegistry registry =
                    IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12);
            schema = registry.getSchema(IkanosMappers.json().readTree(in));
        }
    }

    private static List<Error> validate(String yaml) {
        JsonNode document = IkanosMappers.yaml().readTree(yaml);
        return schema.validate(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"binds", "consumes", "exposes", "aggregates"})
    void schemaShouldRejectRootSectionNextToCapability(String section) {
        List<Error> errors = validate("""
                ikanos: "%s"
                %s:
                  - from: "./shared.%s.yml"
                    import: "shared"
                %s""".formatted(IKANOS, section, section, CAPABILITY));

        assertFalse(errors.isEmpty(),
                "root '" + section + "' next to 'capability' must be invalid");
    }

    @Test
    void schemaShouldAcceptBindsUnderCapability() {
        List<Error> errors = validate("""
                ikanos: "%s"
                %s  binds:
                    - namespace: "registry-env"
                      location: "file:///./shared/secrets.yaml"
                      keys:
                        REGISTRY_TOKEN: "registry-bearer-token"
                """.formatted(IKANOS, CAPABILITY));

        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    void schemaShouldAcceptStandaloneBindsDocument() {
        List<Error> errors = validate("""
                ikanos: "%s"
                binds:
                  - namespace: "registry-env"
                    location: "file:///./shared/secrets.yaml"
                    keys:
                      REGISTRY_TOKEN: "registry-bearer-token"
                """.formatted(IKANOS));

        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }
}
