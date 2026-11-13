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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * {@code authorization} on exposed REST adapters, resources and operations, and on MCP adapters and
 * tools (#999).
 */
class AuthorizationSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    private static final String OAUTH2 = """
            authentication:
              type: oauth2
              authorizationServerUri: "https://idp.example.org/realms/app"
              resource: "https://app.example.org/api"
              scopes: [records.read, records.write]
            """;

    /** Requires records.read. */
    private static final String READ = "authorization:\n  scopes: [records.read]\n";

    /** Requires records.write and the editor or admin role. */
    private static final String WRITE_AS_EDITOR =
            "authorization:\n  scopes: [records.write]\n  claims:\n    roles: [editor, admin]\n";

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = AuthorizationSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @Test
    void schemaShouldAcceptAuthorizationOnRestAdapterResourceAndOperation() throws Exception {
        assertValid(rest(OAUTH2 + READ,
                READ,
                WRITE_AS_EDITOR));
    }

    @Test
    void schemaShouldAcceptAuthorizationOnMcpAdapterAndTool() throws Exception {
        assertValid(mcp(OAUTH2 + READ,
                "authorization:\n  claims:\n    realm_access.roles: [editor]\n"));
    }

    @Test
    void schemaShouldAcceptAuthorizationOnAForwardResource() throws Exception {
        assertValid("""
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 8080
                    namespace: demo
                %s
                    resources:
                      files:
                        path: "/files/{{path}}"
                        authorization:
                          scopes: [records.read]
                        forward:
                          targetNamespace: upstream
                """.formatted(IKANOS, OAUTH2.indent(4)));
    }

    @Test
    void schemaShouldRejectAdapterAuthorizationWithoutOAuth2() throws Exception {
        assertInvalid(rest(READ, "", ""));
        assertInvalid(mcp("""
                authentication:
                  type: bearer
                  token: "{{API_TOKEN}}"
                authorization:
                  scopes: [records.read]
                """, ""));
    }

    @Test
    void schemaShouldRejectAdapterAuthorizationOnTheStdioTransport() throws Exception {
        assertInvalid("""
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    transport: stdio
                    namespace: demo
                    description: "Demo"
                    authorization:
                      scopes: [records.read]
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                """.formatted(IKANOS));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "authorization: {}\n",
        "authorization:\n  scopes: []\n",
        "authorization:\n  claims: {}\n",
        "authorization:\n  claims:\n    roles: []\n",
        "authorization:\n  roles: [admin]\n"
    })
    void schemaShouldRejectAnEmptyOrUnknownRequirement(String authorization) throws Exception {
        assertInvalid(rest(OAUTH2, "", authorization));
    }

    @Test
    void specShouldReadAuthorizationOnEveryLevel() throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(rest(OAUTH2 + READ,
                READ,
                WRITE_AS_EDITOR),
                IkanosSpec.class);
        RestServerSpec adapter = (RestServerSpec) spec.getCapability().getExposes().get(0);
        RestServerResourceSpec resource = adapter.getResources().get("records");

        assertEquals(List.of("records.read"), adapter.getAuthorization().getScopes());
        assertEquals(List.of("records.read"), resource.getAuthorization().getScopes());
        AuthorizationSpec operation = resource.getOperations().get("update").getAuthorization();
        assertEquals(List.of("records.write"), operation.getScopes());
        assertEquals(Map.of("roles", List.of("editor", "admin")), operation.getClaims());
    }

    @Test
    void specShouldReadToolAuthorizationAndLeaveOtherLevelsUnset() throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(
                mcp(OAUTH2, "authorization:\n  scopes: [records.write]\n"), IkanosSpec.class);
        McpServerSpec adapter = (McpServerSpec) spec.getCapability().getExposes().get(0);

        assertNull(adapter.getAuthorization());
        assertEquals(List.of("records.write"),
                adapter.getTools().get("hello").getAuthorization().getScopes());
        assertTrue(adapter.getTools().get("hello").getAuthorization().getClaims().isEmpty());
    }

    @Test
    void nearestShouldReturnTheNarrowestDeclaredRequirement() {
        AuthorizationSpec adapter = new AuthorizationSpec(List.of("a"), null);
        AuthorizationSpec operation = new AuthorizationSpec(List.of("b"), null);

        assertSame(operation, AuthorizationSpec.nearest(adapter, null, operation));
        assertSame(adapter, AuthorizationSpec.nearest(adapter, null, null));
        assertNull(AuthorizationSpec.nearest(null, null));
    }

    /** A REST adapter with one resource holding one PUT operation, each with an optional block. */
    private static String rest(String adapter, String resource, String operation) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 8080
                    namespace: demo
                %s
                    resources:
                      records:
                        path: "/records/{{id}}"
                %s
                        operations:
                          update:
                            method: PUT
                %s
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                """.formatted(IKANOS, adapter.indent(4), resource.indent(8),
                operation.indent(12));
    }

    /** An HTTP MCP adapter with one tool, each with an optional block. */
    private static String mcp(String adapter, String tool) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 8080
                    namespace: demo
                    description: "Demo"
                %s
                    tools:
                      hello:
                        description: "Say hello"
                %s
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                """.formatted(IKANOS, adapter.indent(4), tool.indent(8));
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
