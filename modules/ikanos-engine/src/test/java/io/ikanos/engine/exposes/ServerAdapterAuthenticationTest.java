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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import org.restlet.security.ChallengeAuthenticator;
import org.restlet.security.SecretVerifier;
import org.restlet.security.Verifier;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpOAuth2Restlet;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Unit tests for the shared authentication chain wiring in {@link ServerAdapter#buildServerChain}.
 *
 * <p>Uses MCP adapter YAML for generic auth tests (the logic is in ServerAdapter, not
 * adapter-specific). The MCP-specific OAuth 2.1 test verifies the {@code createOAuth2Restlet}
 * override produces {@link McpOAuth2Restlet}.</p>
 */
class ServerAdapterAuthenticationTest {

    private String schemaVersion;

    @BeforeEach
    void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    void buildServerChainShouldReturnRouterWhenNoAuthentication() throws Exception {
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, ""));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertSame(router, chain, "Without authentication, chain should be the router itself");
    }

    @Test
    void buildServerChainShouldReturnServerAuthenticationRestletForBearer() throws Exception {
        String authBlock = """
                      authentication:
                        type: "bearer"
                        token: "secret-token"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(ServerAuthenticationRestlet.class, chain,
                "Bearer auth should produce ServerAuthenticationRestlet");
    }

    @Test
    void buildServerChainShouldReturnServerAuthenticationRestletForApiKey() throws Exception {
        String authBlock = """
                      authentication:
                        type: "apikey"
                        key: "X-API-Key"
                        value: "abc123"
                        placement: "header"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(ServerAuthenticationRestlet.class, chain,
                "API key auth should produce ServerAuthenticationRestlet");
    }

    @Test
    void buildServerChainShouldReturnChallengeAuthenticatorForBasic() throws Exception {
        String authBlock = """
                      authentication:
                        type: "basic"
                        username: "admin"
                        password: "pass"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(ChallengeAuthenticator.class, chain,
                "Basic auth should produce ChallengeAuthenticator");
    }

    @Test
    void buildServerChainShouldReturnChallengeAuthenticatorForDigest() throws Exception {
        String authBlock = """
                      authentication:
                        type: "digest"
                        username: "admin"
                        password: "pass"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(ChallengeAuthenticator.class, chain,
                "Digest auth should produce ChallengeAuthenticator");
    }

    @Test
    void basicChallengeAuthenticatorWithNoPasswordShouldAcceptEmptyPassword() throws Exception {
        String authBlock = """
                      authentication:
                        type: "basic"
                        username: "admin"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        ChallengeAuthenticator chain = (ChallengeAuthenticator) adapter.buildServerChain(new Router());
        SecretVerifier verifier = (SecretVerifier) chain.getVerifier();

        assertEquals(Verifier.RESULT_VALID, verifier.verify("admin", new char[0]),
                "Omitted password should be satisfied by an empty password on the incoming request");
        assertEquals(Verifier.RESULT_INVALID, verifier.verify("admin", "wrong".toCharArray()),
                "Omitted password should still reject an arbitrary non-empty password");
    }

    @Test
    void digestChallengeAuthenticatorWithNoPasswordShouldAcceptEmptyPassword() throws Exception {
        String authBlock = """
                      authentication:
                        type: "digest"
                        username: "admin"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        ChallengeAuthenticator chain = (ChallengeAuthenticator) adapter.buildServerChain(new Router());
        SecretVerifier verifier = (SecretVerifier) chain.getVerifier();

        assertEquals(Verifier.RESULT_VALID, verifier.verify("admin", new char[0]),
                "Omitted password should be satisfied by an empty password on the incoming request");
        assertEquals(Verifier.RESULT_INVALID, verifier.verify("admin", "wrong".toCharArray()),
                "Omitted password should still reject an arbitrary non-empty password");
    }

    @Test
    void buildServerChainShouldReturnOAuth2RestletForOAuth2() throws Exception {
        String authBlock = """
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "https://auth.example.com"
                        resource: "https://api.example.com"
                        scopes:
                          - "read"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(OAuth2AuthenticationRestlet.class, chain,
                "OAuth2 auth should produce an OAuth2AuthenticationRestlet subtype");
    }

    @Test
    void mcpAdapterShouldReturnMcpOAuth2RestletForOAuth2() throws Exception {
        String authBlock = """
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "https://auth.example.com"
                        resource: "https://mcp.example.com/mcp"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(McpOAuth2Restlet.class, chain,
                "MCP adapter OAuth2 should produce McpOAuth2Restlet");
    }

    @Test
    void restAdapterShouldReturnGenericOAuth2RestletForOAuth2() throws Exception {
        String authBlock = """
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "https://auth.example.com"
                        resource: "https://api.example.com"
                        scopes:
                          - "read"
                """;
        ServerAdapter adapter = adapterFromYaml(REST_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertEquals(OAuth2AuthenticationRestlet.class, chain.getClass(),
                "REST adapter OAuth2 should produce the generic OAuth2AuthenticationRestlet");
    }

    @Test
    void serverSpecShouldDeserializeAuthentication() throws Exception {
        String authBlock = """
                      authentication:
                        type: "bearer"
                        token: "my-token"
                """;
        ServerAdapter adapter = adapterFromYaml(MCP_YAML.formatted(schemaVersion, authBlock));

        assertNotNull(adapter.getSpec().getAuthentication(),
                "Authentication spec should be deserialized");
        assertNotNull(adapter.getSpec().getAuthentication().getType(),
                "Auth type should be set");
    }

    // #856: a capability must not start when an exposed apikey or bearer credential resolves to
    // blank, since it would accept an empty credential. The error names the variable.

    @TempDir
    Path secrets;

    @ParameterizedTest(name = "placement: {0}")
    @ValueSource(strings = {"header", "query"})
    void capabilityShouldRefuseToStartWhenApiKeyIsBoundToEmptyValue(String placement)
            throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapterWithSecrets("api-key: \"\"\n", """
                              authentication:
                                type: "apikey"
                                key: "X-Api-Key"
                                value: "{{API_KEY}}"
                                placement: "%s"
                        """.formatted(placement)));

        assertTrue(e.getMessage().contains("API_KEY"), e.getMessage());
        assertTrue(e.getMessage().contains("apikey"), e.getMessage());
    }

    @ParameterizedTest(name = "placement: {0}")
    @ValueSource(strings = {"header", "query"})
    void capabilityShouldRefuseToStartWhenApiKeyVariableIsMisspelled(String placement)
            throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapterWithSecrets("api-key: \"s3cr3t\"\n", """
                              authentication:
                                type: "apikey"
                                key: "X-Api-Key"
                                value: "{{API_KY}}"
                                placement: "%s"
                        """.formatted(placement)));

        assertTrue(e.getMessage().contains("API_KY"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"), "The error must not show a secret");
    }

    @Test
    void capabilityShouldRefuseToStartWhenBearerTokenIsBoundToEmptyValue() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapterWithSecrets("api-key: \"\"\n", """
                              authentication:
                                type: "bearer"
                                token: "{{API_KEY}}"
                        """));

        assertTrue(e.getMessage().contains("API_KEY"), e.getMessage());
        assertTrue(e.getMessage().contains("bearer"), e.getMessage());
    }

    @Test
    void capabilityShouldStartWhenApiKeyIsBoundToValue() throws Exception {
        ServerAdapter adapter = adapterWithSecrets("api-key: \"s3cr3t\"\n", """
                      authentication:
                        type: "apikey"
                        key: "X-Api-Key"
                        value: "{{API_KEY}}"
                """);

        assertInstanceOf(ServerAuthenticationRestlet.class, adapter.buildServerChain(new Router()));
    }

    /** A REST adapter whose API_KEY binding comes from a secrets file with the given content. */
    private ServerAdapter adapterWithSecrets(String secretsYaml, String authBlock) throws Exception {
        Path file = Files.writeString(secrets.resolve("secrets.yaml"), secretsYaml);
        String yaml = REST_YAML.formatted(schemaVersion, authBlock).replaceFirst("capability:", """
                binds:
                  - namespace: "test-secrets"
                    location: "%s"
                    keys:
                      API_KEY: "api-key"
                capability:""".formatted(file.toUri()));
        return adapterFromYaml(yaml);
    }

    /** MCP adapter YAML template. First %s = schema version, second %s = authentication block. */
    private static final String MCP_YAML = """
            ikanos: "%s"
            capability:
              exposes:
                - type: "mcp"
                  address: "localhost"
                  port: 0
                  namespace: "test-mcp"
            %s      tools:
                    - name: "my-tool"
                      description: "A test tool"
                      outputParameters:
                        - type: "string"
                          value: "ok"
              consumes: []
            """;

    /** REST adapter YAML template. First %s = schema version, second %s = authentication block. */
    private static final String REST_YAML = """
            ikanos: "%s"
            capability:
              exposes:
                - type: "rest"
                  address: "localhost"
                  port: 0
                  namespace: "test-rest"
            %s      resources:
                    - path: "/hello"
                      description: "hello"
                      operations:
                        - method: "GET"
                          outputParameters:
                            - type: "string"
                              value: "ok"
              consumes: []
            """;

    private static ServerAdapter adapterFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        return (ServerAdapter) capability.getServerAdapters().get(0);
    }
}
