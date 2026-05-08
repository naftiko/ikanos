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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import org.restlet.security.ChallengeAuthenticator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpOAuth2Restlet;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

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
    void skillAdapterShouldReturnGenericOAuth2RestletForOAuth2() throws Exception {
        String authBlock = """
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "https://auth.example.com"
                        resource: "https://skills.example.com"
                        scopes:
                          - "read"
                """;
        ServerAdapter adapter = adapterFromYaml(SKILL_YAML.formatted(schemaVersion, authBlock));

        Router router = new Router();
        Restlet chain = adapter.buildServerChain(router);

        assertInstanceOf(OAuth2AuthenticationRestlet.class, chain,
                "Skill adapter OAuth2 should produce OAuth2AuthenticationRestlet");
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

    /** Skill adapter YAML template. First %s = schema version, second %s = authentication block. */
    private static final String SKILL_YAML = """
            ikanos: "%s"
            capability:
              exposes:
                - type: "skill"
                  address: "localhost"
                  port: 0
                  namespace: "test-skills"
            %s      skills:
                    - name: "test-skill"
                      description: "A test skill"
                      location: "file:///tmp/test-skill"
                      tools:
                        - name: "test-tool"
                          description: "A test tool"
                          instruction: "guide.md"
              consumes: []
            """;

    private static ServerAdapter adapterFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        return (ServerAdapter) capability.getServerAdapters().get(0);
    }
}
