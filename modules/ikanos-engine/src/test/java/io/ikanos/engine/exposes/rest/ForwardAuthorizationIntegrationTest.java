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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * End to end: in forward mode, the caller's {@code Authorization} reaches the upstream only when it
 * is listed in {@code trustedHeaders} (#1010).
 */
public class ForwardAuthorizationIntegrationTest {

    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedProxyAuthorization = new AtomicReference<>();
    private HttpServer upstream;
    private ServerAdapter adapter;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    public void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedProxyAuthorization
                    .set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    public void stopAll() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
        upstream.stop(0);
    }

    @Test
    public void forwardShouldNotSendAuthorizationWhenItIsNotATrustedHeader() throws Exception {
        int port = start("", "Accept");

        HttpResponse<String> response = send(port, "Authorization", "Bearer abc");

        assertEquals(200, response.statusCode());
        assertNull(receivedAuthorization.get(),
                "#1010: the caller's Authorization must not reach the upstream when it is not in trustedHeaders");
    }

    @Test
    public void forwardShouldSendAuthorizationWhenItIsATrustedHeaderInAnyCase() throws Exception {
        int port = start("", "authorization");

        HttpResponse<String> response = send(port, "Authorization", "Bearer abc");

        assertEquals(200, response.statusCode());
        assertEquals("Bearer abc", receivedAuthorization.get(),
                "#1010: a trusted Authorization, listed in any case, must still reach the upstream");
    }

    @Test
    public void forwardShouldNotSendProxyAuthorizationWhenItIsNotATrustedHeader() throws Exception {
        int port = start("", "Accept");

        HttpResponse<String> response = send(port, "Proxy-Authorization", "Basic dXNlcjpwYXNz");

        assertEquals(200, response.statusCode());
        assertNull(receivedProxyAuthorization.get(),
                "#1010: Proxy-Authorization must not reach the upstream when it is not in trustedHeaders");
    }

    @Test
    public void forwardShouldNotLeakTheExposedAdapterCredentialWhenTheTargetHasNoAuthentication()
            throws Exception {
        String exposedAuthentication = """
                      authentication:
                        type: "bearer"
                        token: "ikanos-secret"
                """;
        int port = start(exposedAuthentication, "Accept");

        HttpResponse<String> response = send(port, "Authorization", "Bearer ikanos-secret");

        assertEquals(200, response.statusCode(),
                "the caller is authenticated by the exposed adapter");
        assertNull(receivedAuthorization.get(),
                "#1010: the credential presented to Ikanos must not be passed on to the upstream");
    }

    private int start(String exposedAuthentication, String trustedHeader) throws Exception {
        int port = freePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "sample"
                %s
                      resources:
                        - path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: up
                            trustedHeaders:
                              - %s
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://localhost:%d/v1"
                """.formatted(VersionHelper.getSchemaVersion(), port, exposedAuthentication,
                trustedHeader, upstream.getAddress().getPort());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        adapter = new Capability(spec).getServerAdapters().get(0);
        adapter.start();
        return port;
    }

    private HttpResponse<String> send(int port, String header, String value) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/proxy/items"))
                .header(header, value).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
