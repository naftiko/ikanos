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
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Schema validation of the default tool {@code hints} declared on an exposed MCP server (#980).
 */
class McpServerHintsSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String EXAMPLE = "schemas/examples/capability-mcp-server-hints.yml";
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpServerHintsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String serverHints) {
        return """
                ikanos: "%s"
                info:
                  display: "Server hints"
                  description: "Server-level default tool hints"
                capability:
                  consumes:
                    - type: http
                      namespace: gong
                      baseUri: "https://api.gong.example"
                      resources:
                        calls:
                          path: "/calls"
                          operations:
                            list-calls:
                              method: GET
                  exposes:
                    - type: mcp
                      namespace: gong-mcp
                      port: 3001
                      hints:
                %s
                      tools:
                        list-calls:
                          description: "List calls in a date range."
                          call: "gong.list-calls"
                """.formatted(IKANOS, serverHints);
    }

    private static List<Error> validate(String yaml) throws Exception {
        return schema.validate(YAML.readTree(yaml));
    }

    @Test
    void schemaShouldAcceptHintsWhenDeclaredOnMcpServer() throws Exception {
        List<Error> errors = validate(capability("        readOnly: true\n"
                + "        destructive: false\n"
                + "        idempotent: true\n"
                + "        openWorld: true"));

        assertTrue(errors.isEmpty(), "server hints must validate: " + errors);
    }

    @Test
    void schemaShouldRejectServerHintsWhenFieldIsUnknown() throws Exception {
        assertFalse(validate(capability("        cacheable: true")).isEmpty(),
                "server hints accept the McpToolHints fields only");
    }

    @Test
    void schemaShouldRejectServerHintsWhenValueIsNotBoolean() throws Exception {
        assertFalse(validate(capability("        readOnly: \"yes\"")).isEmpty(),
                "server hint values are booleans");
    }

    @Test
    @DisplayName("The shipped capability-mcp-server-hints.yml example validates against the schema")
    void schemaShouldAcceptBundledServerHintsExample() throws Exception {
        try (InputStream in = McpServerHintsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream(EXAMPLE)) {
            assertNotNull(in, EXAMPLE + " must be on the classpath");
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            List<Error> errors = validate(yaml);
            assertTrue(errors.isEmpty(), "shipped example must validate: " + errors);

            // The engine reads capabilities leniently (unknown properties are ignored)
            McpServerSpec server = (McpServerSpec) IkanosMappers.lenientYaml()
                    .readValue(yaml, IkanosSpec.class)
                    .getCapability().getExposes().get(0);
            assertEquals(Boolean.TRUE, server.getHints().getReadOnly(),
                    "the shipped example must declare server hints");
        }
    }
}
