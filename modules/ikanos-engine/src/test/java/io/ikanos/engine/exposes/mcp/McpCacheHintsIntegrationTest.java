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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Cache hints on MCP results (MCP 2026-07-28, SEP-2549): every list and resource-read result
 * carries {@code ttlMs} and {@code cacheScope}; tool calls and prompt gets do not.
 */
class McpCacheHintsIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper JSON = IkanosMappers.json();

    @TempDir
    static Path tempDir;

    private static McpServerAdapter configured;
    private static McpServerAdapter unset;

    @BeforeAll
    static void buildAdapters() throws Exception {
        configured = adapter("ttlMs: 60000\n    cacheScope: public");
        unset = adapter("");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tools/list", "prompts/list", "resources/list",
            "resources/templates/list", "server/discover"})
    void listResultsShouldCarryTheServerCacheHints(String method) throws Exception {
        JsonNode result = dispatch(configured, method, JSON.createObjectNode());

        assertThat(result.path("ttlMs").asInt(-1)).isEqualTo(60000);
        assertThat(result.path("cacheScope").asString()).isEqualTo("public");
    }

    @Test
    void resourceReadResultShouldCarryTheServerCacheHints() throws Exception {
        ObjectNode params = JSON.createObjectNode().put("uri", "docs://guide/guide.md");

        JsonNode result = dispatch(configured, "resources/read", params);

        assertThat(result.path("ttlMs").asInt(-1)).isEqualTo(60000);
        assertThat(result.path("cacheScope").asString()).isEqualTo("public");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tools/list", "resources/list"})
    void cacheHintsShouldDefaultToZeroAndPrivateWhenUnset(String method) throws Exception {
        JsonNode result = dispatch(unset, method, JSON.createObjectNode());

        assertThat(result.path("ttlMs").asInt(-1)).isZero();
        assertThat(result.path("cacheScope").asString()).isEqualTo("private");
    }

    @Test
    void toolCallResultShouldNotCarryCacheHints() throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", "hello");
        params.putObject("arguments");

        JsonNode result = dispatch(configured, "tools/call", params);

        assertThat(result.has("ttlMs")).isFalse();
        assertThat(result.has("cacheScope")).isFalse();
    }

    @Test
    void promptGetResultShouldNotCarryCacheHints() throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", "greet");
        params.putObject("arguments").put("who", "Ada");

        JsonNode result = dispatch(configured, "prompts/get", params);

        assertThat(result.has("ttlMs")).isFalse();
        assertThat(result.has("cacheScope")).isFalse();
    }

    @Test
    void negativeTtlShouldFailAdapterInitialization() {
        assertThatThrownBy(() -> adapter("ttlMs: -1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlMs");
    }

    static McpServerAdapter adapter(String cacheLines) throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("guide.md"), "# guide\n");

        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 9097
                    namespace: cache-hints
                    description: "Cache hints test server"
                    %s
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      guide:
                        display: "Guide"
                        uri: "docs://guide"
                        description: "Static guide"
                        location: "%s"
                      user:
                        display: "User"
                        uri: "data://users/{userId}"
                        description: "A user by id"
                        call: upstream.get-user
                        with:
                          id: "{{userId}}"
                    prompts:
                      greet:
                        description: "Greet someone"
                        arguments:
                          who:
                            description: "Who to greet"
                            required: true
                        template:
                        - role: user
                          content: "Say hello to {{who}}"
                  consumes:
                  - type: http
                    namespace: upstream
                    baseUri: "http://localhost:1"
                    resources:
                      users:
                        path: "users/{{id}}"
                        operations:
                          get-user:
                            method: GET
                """.formatted(IKANOS, cacheLines, docs.toUri());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    static JsonNode dispatch(McpServerAdapter adapter, String method, ObjectNode params) {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        request.set("params", params);
        JsonNode response = new ProtocolDispatcher(adapter).dispatch(request).responseBody();
        assertThat(response.has("error")).as("error in %s", response).isFalse();
        return response.path("result");
    }
}
