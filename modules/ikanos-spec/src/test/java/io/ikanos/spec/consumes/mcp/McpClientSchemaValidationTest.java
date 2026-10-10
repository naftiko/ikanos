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
package io.ikanos.spec.consumes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Keeps schema validation and runtime deserialization of {@code consumes: type: mcp} in step for
 * the enum-like {@code discovery} and {@code validateOutput} fields. YAML 1.1 reads a bare
 * {@code off} as boolean {@code false}; both paths must accept it as {@code off}.
 */
class McpClientSchemaValidationTest {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpClientSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String discovery, String validateOutput) {
        return """
                ikanos: "%s"
                info:
                  display: "MCP client"
                  description: "MCP client schema test"
                capability:
                  consumes:
                    - type: mcp
                      namespace: billing
                      endpoint: "https://billing.example.com/mcp"
                      discovery: %s
                      tools:
                        get-invoice:
                          description: "Retrieve one invoice"
                          validateOutput: %s
                  exposes:
                    - type: mcp
                      namespace: billing-tools
                      port: 3001
                      tools:
                        get-invoice:
                          description: "Retrieve one invoice"
                          call: "billing.get-invoice"
                """.formatted(IKANOS, discovery, validateOutput);
    }

    private static Set<ValidationMessage> validate(String yaml) throws Exception {
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    private static McpClientSpec runtime(String yaml) throws Exception {
        IkanosSpec spec = YAML.readValue(yaml, IkanosSpec.class);
        return (McpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    @Test
    void bareOffShouldPassSchemaAndReadAsOffAtRuntime() throws Exception {
        String yaml = capability("off", "off");

        assertTrue(YAML.readTree(yaml).at("/capability/consumes/0/discovery").isBoolean(),
                "precondition: YAML 1.1 reads a bare off as a boolean");
        Set<ValidationMessage> errors = validate(yaml);
        assertTrue(errors.isEmpty(), "bare off must validate: " + errors);

        McpClientSpec spec = runtime(yaml);
        assertEquals(McpClientSpec.DISCOVERY_OFF, spec.getDiscovery());
        assertFalse(spec.isDiscoveryVerify());
        assertEquals(McpClientToolSpec.VALIDATE_OFF,
                spec.getTools().get("get-invoice").validationMode());
    }

    @Test
    void quotedValuesShouldPassSchemaAndMatchRuntime() throws Exception {
        String yaml = capability("\"verify\"", "\"warn\"");

        Set<ValidationMessage> errors = validate(yaml);
        assertTrue(errors.isEmpty(), "quoted values must validate: " + errors);

        McpClientSpec spec = runtime(yaml);
        assertTrue(spec.isDiscoveryVerify());
        assertEquals(McpClientToolSpec.VALIDATE_WARN,
                spec.getTools().get("get-invoice").validationMode());
    }

    @Test
    void bareOnShouldFailSchemaValidation() throws Exception {
        assertFalse(validate(capability("on", "\"fail\"")).isEmpty(),
                "a bare on (boolean true) is not a discovery mode");
        assertFalse(validate(capability("\"verify\"", "on")).isEmpty(),
                "a bare on (boolean true) is not a validateOutput mode");
    }

    @Test
    void unknownStringShouldFailSchemaValidation() throws Exception {
        assertFalse(validate(capability("\"sometimes\"", "\"fail\"")).isEmpty());
        assertFalse(validate(capability("\"verify\"", "\"maybe\"")).isEmpty());
    }
}
