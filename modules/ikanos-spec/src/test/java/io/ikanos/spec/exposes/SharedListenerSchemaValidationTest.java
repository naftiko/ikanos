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
package io.ikanos.spec.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * Schema rules and deserialization for the {@code path} and {@code hosts} fields that let
 * exposed adapters share one listener (issue #893).
 */
class SharedListenerSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = SharedListenerSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/mcp", "/api/v1", "/mcp-admin"})
    void schemaShouldAcceptPath(String path) throws Exception {
        assertValid(rest("path: \"" + path + "\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "api", "/api/", "/{tenant}", "//api", "/a b"})
    void schemaShouldRejectPath(String path) throws Exception {
        assertInvalid(rest("path: \"" + path + "\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcp.acme.com", "*.acme.com", "localhost", "{{CUSTOM_DOMAIN}}",
            "{{ deploy.CUSTOM_DOMAIN }}"})
    void schemaShouldAcceptHost(String host) throws Exception {
        assertValid(rest("hosts: [\"" + host + "\"]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme.com:8080", "a.*.acme.com", "*", "mcp.{{TENANT}}.acme.com",
            "https://acme.com"})
    void schemaShouldRejectHost(String host) throws Exception {
        assertInvalid(rest("hosts: [\"" + host + "\"]"));
    }

    @Test
    void schemaShouldRejectAnEmptyHostList() throws Exception {
        assertInvalid(rest("hosts: []"));
    }

    @Test
    void schemaShouldAcceptPathAndHostsOnMcpAdapters() throws Exception {
        assertValid("""
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 8080
                    path: "/mcp"
                    hosts: ["mcp.acme.com"]
                    namespace: demo
                    description: "Demo"
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                  - type: mcp
                    port: 8080
                    path: "/catalog"
                    hosts: ["*.acme.com"]
                    namespace: demo-skills
                    skills:
                      greetings:
                        description: "Greets people"
                """.formatted(IKANOS));
    }

    @Test
    void schemaShouldRejectPathOnTheControlAdapter() throws Exception {
        assertInvalid("""
                ikanos: "%s"
                capability:
                  exposes:
                  - type: control
                    port: 9090
                    path: "/control"
                """.formatted(IKANOS));
    }

    @Test
    void deserializationShouldReadPathAndHosts() throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(
                rest("path: \"/api\"\n    hosts: [\"api.acme.com\", \"*.acme.com\"]"),
                IkanosSpec.class);
        ServerSpec adapter = spec.getCapability().getExposes().get(0);

        assertEquals("/api", adapter.getPath());
        assertEquals(List.of("api.acme.com", "*.acme.com"), adapter.getHosts());
    }

    @Test
    void serializationShouldOmitPathAndHostsWhenAbsent() throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(rest(""), IkanosSpec.class);

        JsonNode adapter = IkanosMappers.json().valueToTree(spec)
                .path("capability").path("exposes").path(0);

        assertFalse(adapter.has("path"), adapter.toString());
        assertFalse(adapter.has("hosts"), adapter.toString());
    }

    private static String rest(String mountLines) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 8080
                    %s
                    namespace: demo
                    resources:
                      ping:
                        path: "/ping"
                        operations:
                          ping:
                            method: GET
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                """.formatted(IKANOS, mountLines);
    }

    private static void assertValid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertTrue(errors.isEmpty(), "Expected valid capability, got:\n" + errors + "\n" + yaml);
    }

    private static void assertInvalid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertFalse(errors.isEmpty(), "Expected schema errors for:\n" + yaml);
    }
}
