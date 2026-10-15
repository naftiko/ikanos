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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * Schema rules for the MCP {@code trust} block (issue #483).
 */
class McpTrustSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = McpTrustSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @Test
    void schemaShouldAcceptMcpAdapterWithoutTrust() throws Exception {
        assertValid(capability("    port: 3001", ""));
    }

    @Test
    void schemaShouldAcceptExplicitModeNone() throws Exception {
        assertValid(capability("    port: 3001", """
                    trust:
                      mode: none
                """));
    }

    @Test
    void schemaShouldAcceptForwardedWithHeaderAndNetworks() throws Exception {
        assertValid(capability("    port: 3001", """
                    trust:
                      mode: forwarded
                      agentIdentityHeader: X-Agent-Id
                      trustedNetworks: ["10.0.0.0/8", "fd00::/8"]
                """));
    }

    @Test
    void schemaShouldRejectClaimsHeaderBecauseUnsignedClaimsAreNotSupported() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: forwarded
                      agentIdentityHeader: X-Agent-Id
                      claimsHeader: X-Forwarded-Claims
                      trustedNetworks: ["10.0.0.0/8"]
                """));
    }

    @Test
    void schemaShouldRejectForwardedWithClaimsHeaderOnly() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: forwarded
                      claimsHeader: X-Forwarded-Claims
                      trustedNetworks: ["10.0.0.0/8"]
                """));
    }

    @Test
    void schemaShouldAcceptForwardedTogetherWithAuthentication() throws Exception {
        assertValid(capability("    port: 3001", """
                    authentication:
                      type: oauth2
                      authorizationServerUri: "https://auth.example.com"
                      resource: "https://mcp.example.com"
                    trust:
                      mode: forwarded
                      agentIdentityHeader: X-Agent-Id
                      trustedNetworks: ["10.0.0.0/8"]
                """));
    }

    @Test
    void schemaShouldRejectForwardedWithoutTrustedNetworks() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: forwarded
                      agentIdentityHeader: X-Agent-Id
                """));
    }

    @Test
    void schemaShouldRejectForwardedWithoutAgentIdentityHeader() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: forwarded
                      trustedNetworks: ["10.0.0.0/8"]
                """));
    }

    @Test
    void schemaShouldRejectForwardedFieldsWhenModeIsNone() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: none
                      agentIdentityHeader: X-Agent-Id
                """));
    }

    @Test
    void schemaShouldRejectVerifyWithoutOAuth2Authentication() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: verify
                """));
    }

    @Test
    void schemaShouldAcceptVerifyWithOAuth2Authentication() throws Exception {
        assertValid(capability("    port: 3001", """
                    authentication:
                      type: oauth2
                      authorizationServerUri: "https://auth.example.com"
                      resource: "https://mcp.example.com"
                    trust:
                      mode: verify
                """));
    }

    @Test
    void schemaShouldRejectTrustOnStdioTransport() throws Exception {
        assertInvalid(capability("    transport: stdio", """
                    trust:
                      mode: none
                """));
    }

    @Test
    void schemaShouldRejectUnknownMode() throws Exception {
        assertInvalid(capability("    port: 3001", """
                    trust:
                      mode: trust-everyone
                """));
    }

    private static String capability(String transportLine, String trustBlock) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                %s
                    namespace: demo
                    description: "Demo"
                %s    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                """.formatted(IKANOS, transportLine, trustBlock);
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
