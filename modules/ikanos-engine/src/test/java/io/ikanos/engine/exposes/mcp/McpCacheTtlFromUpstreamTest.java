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
import java.net.ServerSocket;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.CacheDirective;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * {@code ttlFromUpstream} on an MCP resource (#1019): {@code resources/read} takes its
 * {@code ttlMs} from the upstream's caching headers. A unit test: a local in-process Restlet
 * upstream sends each kind of caching header.
 */
class McpCacheTtlFromUpstreamTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper JSON = IkanosMappers.json();

    private static Component upstream;
    private static McpServerAdapter adapter;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        upstream = upstream(port);
        upstream.start();
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(capability(port), IkanosSpec.class);
        adapter = (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    @AfterAll
    static void stop() throws Exception {
        upstream.stop();
    }

    @Test
    void readShouldTakeTheTtlFromTheUpstreamMaxAge() {
        // the upstream's Date is to the second, so the time since it is deducted too
        assertThat(read("data://fresh").path("ttlMs").asInt(-1)).isBetween(290_000, 300_000);
    }

    @Test
    void readShouldMakeTheScopePrivateWhenTheUpstreamAnswerIsPrivate() {
        JsonNode result = read("data://personal");

        assertThat(result.path("ttlMs").asInt(-1)).isBetween(50_000, 60_000);
        assertThat(result.path("cacheScope").asString()).isEqualTo("private");
    }

    @Test
    void readShouldGiveZeroWhenTheUpstreamSaysNoStore() {
        assertThat(read("data://volatile").path("ttlMs").asInt(-1)).isZero();
    }

    @Test
    void readShouldTakeTheTtlFromTheUpstreamExpires() {
        int ttl = read("data://expiring").path("ttlMs").asInt(-1);

        // Expires is ten minutes after the upstream's Date header
        assertThat(ttl).isBetween(590_000, 600_000);
    }

    @Test
    void readShouldSubtractTheAgeOfACachedUpstreamAnswer() {
        assertThat(read("data://aged").path("ttlMs").asInt(-1)).isEqualTo(10_000);
    }

    @Test
    void readShouldFallBackToTheResourceTtlWhenTheUpstreamGivesNoFreshness() {
        assertThat(read("data://plain").path("ttlMs").asInt(-1)).isEqualTo(5_000);
    }

    @Test
    void readShouldIgnoreTheUpstreamWhenTheResourceDoesNotOptIn() {
        JsonNode result = read("data://opted-out");

        assertThat(result.path("ttlMs").asInt(-1)).isEqualTo(1_000);
        assertThat(result.path("cacheScope").asString()).isEqualTo("public");
    }

    static JsonNode read(String uri) {
        ObjectNode params = JSON.createObjectNode().put("uri", uri);
        return McpCacheHintsIntegrationTest.dispatch(adapter, "resources/read", params);
    }

    static String capability(int port) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 9099
                    namespace: ttl-upstream
                    description: "TTL from upstream"
                    ttlMs: 1000
                    cacheScope: public
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      fresh:
                        display: "Fresh"
                        uri: "data://fresh"
                        description: "max-age=300"
                        call: api.fresh
                        ttlFromUpstream: true
                      personal:
                        display: "Personal"
                        uri: "data://personal"
                        description: "private, max-age=60"
                        call: api.personal
                        ttlFromUpstream: true
                      volatile:
                        display: "Volatile"
                        uri: "data://volatile"
                        description: "no-store"
                        call: api.volatile
                        ttlFromUpstream: true
                      expiring:
                        display: "Expiring"
                        uri: "data://expiring"
                        description: "Expires in ten minutes"
                        call: api.expiring
                        ttlFromUpstream: true
                      aged:
                        display: "Aged"
                        uri: "data://aged"
                        description: "max-age=300, Age 290"
                        call: api.aged
                        ttlFromUpstream: true
                      plain:
                        display: "Plain"
                        uri: "data://plain"
                        description: "No caching headers"
                        call: api.plain
                        ttlFromUpstream: true
                        ttlMs: 5000
                      opted-out:
                        display: "Opted out"
                        uri: "data://opted-out"
                        description: "max-age=300, but ttlFromUpstream is off"
                        call: api.fresh
                  consumes:
                  - type: http
                    namespace: api
                    baseUri: "http://localhost:%d/v1"
                    resources:
                      fresh:
                        path: "/fresh"
                        operations:
                          fresh:
                            method: GET
                      personal:
                        path: "/personal"
                        operations:
                          personal:
                            method: GET
                      volatile:
                        path: "/volatile"
                        operations:
                          volatile:
                            method: GET
                      expiring:
                        path: "/expiring"
                        operations:
                          expiring:
                            method: GET
                      aged:
                        path: "/aged"
                        operations:
                          aged:
                            method: GET
                      plain:
                        path: "/plain"
                        operations:
                          plain:
                            method: GET
                """.formatted(IKANOS, port);
    }

    static Component upstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/v1/fresh", answer(List.of(CacheDirective.maxAge(300)), null));
                router.attach("/v1/personal", answer(
                        List.of(CacheDirective.privateInfo(), CacheDirective.maxAge(60)), null));
                router.attach("/v1/volatile", answer(List.of(CacheDirective.noStore()), null));
                router.attach("/v1/expiring", answer(List.of(), 600_000L));
                router.attach("/v1/aged", aged());
                router.attach("/v1/plain", answer(List.of(), null));
                return router;
            }
        });
        return component;
    }

    static Restlet aged() {
        Restlet fresh = answer(List.of(CacheDirective.maxAge(300)), null);
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                fresh.handle(request, response);
                response.setAge(290);
            }
        };
    }

    static Restlet answer(List<CacheDirective> directives, Long expiresInMillis) {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
                StringRepresentation entity =
                        new StringRepresentation("{\"ok\":true}", MediaType.APPLICATION_JSON);
                Date now = new Date();
                response.setDate(now);
                if (expiresInMillis != null) {
                    entity.setExpirationDate(new Date(now.getTime() + expiresInMillis));
                }
                response.setEntity(entity);
                response.getCacheDirectives().addAll(directives);
            }
        };
    }
}
