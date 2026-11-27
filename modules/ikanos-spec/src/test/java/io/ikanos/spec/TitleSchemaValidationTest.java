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
package io.ikanos.spec;

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
 * Schema validation of {@code title}, which replaced {@code display} with no alias (#1164).
 */
class TitleSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = TitleSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    /**
     * A capability whose {@code info} carries {@code infoName} and whose tool carries
     * {@code toolName}, each a block of YAML lines (or empty).
     */
    private static String capability(String infoName, String toolName) {
        return """
                ikanos: "%s"
                info:
                %s
                  description: "Title alias"
                capability:
                  consumes:
                    - type: http
                      namespace: registry
                      baseUri: "https://api.registry.test"
                      resources:
                        ships:
                          path: "/ships"
                          operations:
                            list-ships:
                              method: GET
                  exposes:
                    - type: mcp
                      namespace: fleet
                      port: 3001
                      tools:
                        list-ships:
                %s
                          description: "List ships."
                          call: "registry.list-ships"
                """.formatted(IKANOS, infoName, toolName);
    }

    private static List<Error> validate(String yaml) throws Exception {
        return schema.validate(YAML.readTree(yaml));
    }

    @Test
    void schemaShouldAcceptTitleOnInfoAndTool() throws Exception {
        List<Error> errors = validate(capability("  title: \"Fleet\"",
                "          title: \"List Ships\""));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void schemaShouldRejectDisplayOnInfo() throws Exception {
        List<Error> errors = validate(capability("  display: \"Fleet\"",
                "          title: \"List Ships\""));

        assertFalse(errors.isEmpty());
        assertTrue(errors.toString().contains("display"), errors.toString());
    }

    @Test
    void schemaShouldRejectDisplayOnTool() throws Exception {
        List<Error> errors = validate(capability("  title: \"Fleet\"",
                "          display: \"List Ships\""));

        assertFalse(errors.isEmpty());
        assertTrue(errors.toString().contains("display"), errors.toString());
    }

    @Test
    void schemaShouldRejectInfoWithoutTitle() throws Exception {
        List<Error> errors = validate(capability("", ""));

        assertFalse(errors.isEmpty());
    }

}
