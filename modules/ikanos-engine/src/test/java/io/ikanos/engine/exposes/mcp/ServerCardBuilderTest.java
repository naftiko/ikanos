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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.engine.exposes.mcp.ServerCardBuilder.ServerCard;
import io.ikanos.spec.IconSpec;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpServerCardSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unit tests for {@link ServerCardBuilder}: how each card field is resolved from {@code info},
 * the exposes entry and the {@code card} overrides, and which cards fail the load.
 *
 * <p>Every rendered card is validated against {@code mcp/server-card.schema.v1.json}, a copy of
 * {@code schema.json} from modelcontextprotocol/experimental-ext-server-card at commit
 * 28db12c9b4a3 (Apache-2.0), the v1 schema SEP-2127 pins.</p>
 */
class ServerCardBuilderTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final List<String> VERSIONS = List.of("2026-07-28");
    private static final String SECRET = "s3cr3t-value-that-must-not-leak";

    private final List<String> warnings = new ArrayList<>();
    private McpServerSpec server;
    private InfoSpec info;

    @BeforeEach
    void setUp() {
        server = new McpServerSpec("127.0.0.1", 3001, "shipyard-tools",
                "Shipyard MCP tools for fleet management");
        server.setPath("/mcp");
        server.setCard(new McpServerCardSpec());
        info = new InfoSpec("Shipyard Fleet Management", "Fleet management", null, null);
        info.setVersion("1.4.0");
        info.setPublisher("io.naftiko");
    }

    private JsonNode render() {
        ServerCard card = ServerCardBuilder.build(server, info, VERSIONS, warnings::add,
                () -> "fleet.internal");
        JsonNode json = JSON.readTree(card.json());
        assertConformsToUpstreamSchema(json);
        return json;
    }

    static void assertConformsToUpstreamSchema(JsonNode card) {
        try (InputStream in = ServerCardBuilderTest.class
                .getResourceAsStream("/mcp/server-card.schema.v1.json")) {
            ObjectNode schema = (ObjectNode) JSON.readTree(in);
            schema.put("$ref", "#/$defs/ServerCard");
            Schema validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schema);
            List<Error> errors = validator.validate(card);
            assertTrue(errors.isEmpty(), "Card breaks the v1 schema: " + errors + "\n" + card);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void buildShouldNameTheCardFromPublisherAndNamespace() {
        assertEquals("io.naftiko/shipyard-tools", render().path("name").asString());
    }

    @Test
    void buildShouldPreferCardNameOverPublisher() {
        server.getCard().setName("com.example/fleet");
        assertEquals("com.example/fleet", render().path("name").asString());
    }

    @Test
    void buildShouldFailWhenNeitherCardNameNorPublisherIsSet() {
        info.setPublisher(null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                this::render);
        assertTrue(error.getMessage().contains("info.publisher"), error.getMessage());
    }

    @Test
    void buildShouldFailWhenCardNameHasNoSlash() {
        server.getCard().setName("no-slash-here");
        assertThrows(IllegalArgumentException.class, this::render);
    }

    @Test
    void buildShouldDeclareTheV1SchemaUrl() {
        assertEquals(ServerCardBuilder.SCHEMA_URL, render().path("$schema").asString());
    }

    @Test
    void buildShouldTakeVersionFromInfo() {
        assertEquals("1.4.0", render().path("version").asString());
    }

    @Test
    void buildShouldPreferCardVersionOverInfoVersion() {
        server.getCard().setVersion("2.0.0");
        assertEquals("2.0.0", render().path("version").asString());
    }

    @Test
    void resolveVersionShouldMatchTheCardVersionUsedForServerInfo() {
        server.getCard().setVersion("2.0.0");
        assertEquals("2.0.0", ServerCardBuilder.resolveVersion(server, info));
    }

    @Test
    void buildShouldFailWhenNoVersionIsDeclared() {
        info.setVersion(null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                this::render);
        assertTrue(error.getMessage().contains("version"), error.getMessage());
    }

    @Test
    void buildShouldFailWhenTheVersionIsLongerThan255Characters() {
        info.setVersion("1.0.0-" + "a".repeat(250));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                this::render);
        assertTrue(error.getMessage().contains("255"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"^1.2.3", "~1.2.3", ">=1.2.3", "<2", "1.x", "1.*", "*",
            "1.0 - 2.0", "1.0 || 2.0"})
    void buildShouldRejectVersionRanges(String range) {
        info.setVersion(range);
        assertThrows(IllegalArgumentException.class, this::render);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3", "2.1.0-alpha", "1.0.0-beta.1+exp.sha", "2026.11.26"})
    void buildShouldAcceptExactVersions(String version) {
        info.setVersion(version);
        assertEquals(version, render().path("version").asString());
    }

    @Test
    void buildShouldTakeDescriptionFromTheExposesEntry() {
        assertEquals("Shipyard MCP tools for fleet management",
                render().path("description").asString());
    }

    @Test
    void buildShouldFailRatherThanTruncateALongDescription() {
        server.setDescription("x".repeat(101));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                this::render);
        assertTrue(error.getMessage().contains("card.description"), error.getMessage());
    }

    @Test
    void buildShouldAcceptAShortCardDescriptionOverALongOne() {
        server.setDescription("x".repeat(500));
        server.getCard().setDescription("Fleet tools");
        assertEquals("Fleet tools", render().path("description").asString());
    }

    @Test
    void buildShouldFailWhenNoDescriptionIsDeclared() {
        server.setDescription(null);
        assertThrows(IllegalArgumentException.class, this::render);
    }

    @Test
    void buildShouldTakeTitleFromInfoDisplay() {
        assertEquals("Shipyard Fleet Management", render().path("title").asString());
    }

    @Test
    void buildShouldFailWhenTheInfoDisplayTitleIsTooLong() {
        info.setDisplay("x".repeat(101));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                this::render);
        assertTrue(error.getMessage().contains("card.title"), error.getMessage());
    }

    @Test
    void buildShouldFailWhenTheCardTitleIsTooLong() {
        server.getCard().setTitle("x".repeat(101));
        assertThrows(IllegalArgumentException.class, this::render);
    }

    @Test
    void buildShouldFailWhenTheTitleIsBlank() {
        info.setDisplay("   ");
        assertThrows(IllegalArgumentException.class, this::render);
    }

    @Test
    void buildShouldAcceptAShortCardTitleOverALongInfoDisplay() {
        info.setDisplay("x".repeat(500));
        server.getCard().setTitle("Fleet");
        assertEquals("Fleet", render().path("title").asString());
    }

    @Test
    void buildShouldOmitTitleWhenNoneIsDeclared() {
        info.setDisplay(null);
        assertFalse(render().has("title"));
    }

    @Test
    void buildShouldInferGithubAsRepositorySource() {
        info.setRepositoryUrl("https://github.com/example/shipyard");
        JsonNode repository = render().path("repository");
        assertEquals("https://github.com/example/shipyard", repository.path("url").asString());
        assertEquals("github", repository.path("source").asString());
    }

    @Test
    void buildShouldOmitRepositoryAndWarnWhenTheHostIsUnknown() {
        info.setRepositoryUrl("https://git.example.com/shipyard");
        assertFalse(render().has("repository"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("repository")), warnings.toString());
    }

    @Test
    void buildShouldInheritIconsAndWebsiteFromInfo() {
        IconSpec icon = new IconSpec("https://fleet.example.com/icon.png");
        icon.setSizes(List.of("64x64"));
        info.getIcons().add(icon);
        info.setWebsiteUrl("https://docs.example.com/shipyard");
        JsonNode card = render();
        assertEquals("https://fleet.example.com/icon.png",
                card.path("icons").path(0).path("src").asString());
        assertEquals("64x64", card.path("icons").path(0).path("sizes").path(0).asString());
        assertEquals("https://docs.example.com/shipyard", card.path("websiteUrl").asString());
    }

    @Test
    void buildShouldReplaceInfoIconsWholeWithCardIcons() {
        info.getIcons().add(new IconSpec("https://fleet.example.com/a.png"));
        info.getIcons().add(new IconSpec("https://fleet.example.com/b.png"));
        server.getCard().setIcons(List.of(new IconSpec("https://fleet.example.com/c.png")));
        JsonNode icons = render().path("icons");
        assertEquals(1, icons.size());
        assertEquals("https://fleet.example.com/c.png", icons.path(0).path("src").asString());
    }

    @Test
    void buildShouldGenerateTheUrlFromAddressPortAndPath() {
        assertEquals("http://127.0.0.1:3001/mcp",
                render().path("remotes").path(0).path("url").asString());
    }

    @Test
    void buildShouldPreferCardUrlOverTheGeneratedOne() {
        server.getCard().setUrl("https://fleet.example.com/mcp");
        assertEquals("https://fleet.example.com/mcp",
                render().path("remotes").path(0).path("url").asString());
    }

    @Test
    void buildShouldAdvertiseLocalhostWhenAddressIsUnset() {
        server.setAddress(null);
        assertEquals("http://localhost:3001/mcp",
                render().path("remotes").path(0).path("url").asString());
    }

    @Test
    void buildShouldAdvertiseTheHostNameAndWarnWhenAddressIsAWildcard() {
        server.setAddress("0.0.0.0");
        assertEquals("http://fleet.internal:3001/mcp",
                render().path("remotes").path(0).path("url").asString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("card.url")), warnings.toString());
    }

    @Test
    void buildShouldBracketAnIpv6Address() {
        server.setAddress("::1");
        assertEquals("http://[::1]:3001/mcp",
                render().path("remotes").path(0).path("url").asString());
    }

    @Test
    void buildShouldDropTheDefaultHttpPort() {
        server.setPort(80);
        server.setPath(null);
        assertEquals("http://127.0.0.1",
                render().path("remotes").path(0).path("url").asString());
    }

    @Test
    void buildShouldAdvertiseStreamableHttpAndTheSupportedProtocolVersions() {
        JsonNode remote = render().path("remotes").path(0);
        assertEquals("streamable-http", remote.path("type").asString());
        assertEquals("2026-07-28", remote.path("supportedProtocolVersions").path(0).asString());
    }

    @Test
    void buildShouldDescribeABearerHeaderWithoutItsValue() {
        BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
        bearer.setToken(SECRET);
        server.setAuthentication(bearer);
        ServerCard card = ServerCardBuilder.build(server, info, VERSIONS, warnings::add,
                () -> "h");
        JsonNode header = JSON.readTree(card.json()).path("remotes").path(0).path("headers")
                .path(0);
        assertEquals("Authorization", header.path("name").asString());
        assertEquals("Bearer {token}", header.path("value").asString());
        assertTrue(header.path("isSecret").asBoolean());
        assertFalse(card.json().contains(SECRET), card.json());
        assertConformsToUpstreamSchema(JSON.readTree(card.json()));
    }

    @Test
    void buildShouldDescribeAnApiKeyHeaderByItsName() {
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setKey("X-Api-Key");
        apiKey.setPlacement("header");
        apiKey.setValue(SECRET);
        server.setAuthentication(apiKey);
        JsonNode card = render();
        JsonNode header = card.path("remotes").path(0).path("headers").path(0);
        assertEquals("X-Api-Key", header.path("name").asString());
        assertEquals("{key}", header.path("value").asString());
        assertFalse(card.toString().contains(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "Query", "QUERY"})
    void buildShouldTemplateAQueryApiKeyIntoTheUrl(String placement) {
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setKey("api_key");
        apiKey.setPlacement(placement);
        apiKey.setValue(SECRET);
        server.setAuthentication(apiKey);
        JsonNode remote = render().path("remotes").path(0);
        assertEquals("http://127.0.0.1:3001/mcp?api_key={key}", remote.path("url").asString());
        assertTrue(remote.path("variables").path("key").path("isSecret").asBoolean());
        assertFalse(remote.has("headers"));
    }

    @Test
    void buildShouldDescribeBasicCredentialsWithoutAValue() {
        BasicAuthenticationSpec basic = new BasicAuthenticationSpec();
        basic.setUsername("admin");
        server.setAuthentication(basic);
        JsonNode header = render().path("remotes").path(0).path("headers").path(0);
        assertEquals("Authorization", header.path("name").asString());
        assertFalse(header.has("value"));
    }

    @Test
    void buildShouldDescribeDigestCredentialsWithoutAValue() {
        DigestAuthenticationSpec digest = new DigestAuthenticationSpec();
        digest.setUsername("admin");
        server.setAuthentication(digest);
        JsonNode header = render().path("remotes").path(0).path("headers").path(0);
        assertTrue(header.path("description").asString().contains("Digest"));
    }

    @Test
    void buildShouldDeclareNoHeaderForOAuth2() {
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setAuthorizationServerUri("https://auth.example.com");
        oauth2.setResource("https://fleet.example.com/mcp");
        server.setAuthentication(oauth2);
        assertFalse(render().path("remotes").path(0).has("headers"));
    }

    @Test
    void buildShouldDeclareNoHeaderForAnOpenServer() {
        assertFalse(render().path("remotes").path(0).has("headers"));
    }

    @Test
    void buildShouldWarnWhenAuthenticatedIsSetWithoutAuthentication() {
        server.getCard().setAuthenticated(true);
        render();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("card.authenticated")),
                warnings.toString());
    }

    @Test
    void buildShouldNotWarnWhenAuthenticatedIsSetUnderForwardedTrust() {
        server.getCard().setAuthenticated(true);
        server.setTrust(new McpTrustSpec(McpTrustSpec.MODE_FORWARDED));
        render();
        assertTrue(warnings.stream().noneMatch(w -> w.contains("card.authenticated")),
                warnings.toString());
        assertTrue(ServerCardBuilder.isRestricted(server));
    }

    @Test
    void buildShouldRefuseACardOnAStdioServer() {
        server.setTransport("stdio");
        assertThrows(IllegalArgumentException.class, this::render);
    }

    @Test
    void buildShouldGiveTheSameCardTheSameEtag() {
        ServerCard first = ServerCardBuilder.build(server, info, VERSIONS, warnings::add,
                () -> "h");
        ServerCard second = ServerCardBuilder.build(server, info, VERSIONS, warnings::add,
                () -> "h");
        assertEquals(first.etag(), second.etag());
    }

    @Test
    void buildShouldChangeTheEtagWhenTheCardChanges() {
        ServerCard first = ServerCardBuilder.build(server, info, VERSIONS, warnings::add,
                () -> "h");
        info.setVersion("1.5.0");
        ServerCard second = ServerCardBuilder.build(server, info, VERSIONS, warnings::add,
                () -> "h");
        assertNotEquals(first.etag(), second.etag());
    }
}
