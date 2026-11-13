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
package io.ikanos.engine.exposes.rest;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Restlet;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * {@code Cache-Control} on REST answers (#1021), read from what an HTTP client receives: the
 * headers must be set before the answer is committed, and a forward that a declared operation
 * falls back to must not get the operation's caching policy.
 */
class RestCacheControlHttpIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Component upstream;
    private static Capability capability;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        int upstreamPort = freePort();
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, upstreamPort);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attachDefault(new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"forwarded\":true}", MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        upstream.start();

        port = freePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    address: localhost
                    port: %d
                    namespace: catalog
                    ttlMs: 300000
                    cacheScope: public
                    resources:
                      products:
                        path: "/products"
                        operations:
                          list-products:
                            method: GET
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                      fallback:
                        path: "/fallback/{{path}}"
                        operations:
                          get-fallback:
                            method: GET
                        forward:
                          targetNamespace: upstream
                  consumes:
                  - type: http
                    namespace: upstream
                    baseUri: "http://localhost:%d"
                """.formatted(IKANOS, port, upstreamPort);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void getShouldSendCacheControlOverHttp() throws Exception {
        HttpResponse<String> response = get("/products");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValue("public, max-age=300");
    }

    @Test
    void forwardThatAnOperationFallsBackToShouldNotGetTheOperationCacheControl()
            throws Exception {
        HttpResponse<String> response = get("/fallback/items");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("forwarded");
        assertThat(response.headers().firstValue("Cache-Control")).isEmpty();
    }

    static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
