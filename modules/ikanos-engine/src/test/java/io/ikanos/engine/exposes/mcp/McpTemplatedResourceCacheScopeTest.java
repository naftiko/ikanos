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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Cache hints on a templated resource, where per-user data usually lives: a resource that sets
 * {@code cacheScope: private} under a {@code public} adapter is read through the dispatcher, with
 * a local stub as the consumed API.
 */
class McpTemplatedResourceCacheScopeTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper JSON = IkanosMappers.json();

    private static RecordingUpstream upstream;
    private static McpServerAdapter adapter;

    @BeforeAll
    static void buildAdapter() throws Exception {
        upstream = new RecordingUpstream();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 9099
                    namespace: templated-cache-hints
                    description: "Templated resource cache hints"
                    ttlMs: 60000
                    cacheScope: public
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      user:
                        display: "User"
                        uri: "data://users/{userId}"
                        description: "A user by id"
                        call: upstream.get-user
                        with:
                          id: "{{userId}}"
                        cacheScope: private
                  consumes:
                  - type: http
                    namespace: upstream
                    baseUri: "%s"
                    resources:
                      users:
                        path: "/users/{{id}}"
                        operations:
                          get-user:
                            method: GET
                """.formatted(IKANOS, upstream.baseUri());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    @AfterAll
    static void stopUpstream() throws Exception {
        upstream.close();
    }

    @Test
    void resourceReadShouldUseThePrivateScopeOfATemplatedResourceUnderAPublicServer() {
        JsonNode result = McpCacheHintsIntegrationTest.dispatch(adapter, "resources/read",
                JSON.createObjectNode().put("uri", "data://users/42"));

        assertThat(result.path("contents").get(0).path("uri").asString())
                .isEqualTo("data://users/42");
        assertThat(result.path("cacheScope").asString()).isEqualTo("private");
        assertThat(result.path("ttlMs").asInt(-1)).isEqualTo(60000);
    }

    @Test
    void listResultShouldKeepThePublicScopeOfTheServer() {
        ObjectNode params = JSON.createObjectNode();

        JsonNode result =
                McpCacheHintsIntegrationTest.dispatch(adapter, "resources/templates/list", params);

        assertThat(result.path("cacheScope").asString()).isEqualTo("public");
    }
}
