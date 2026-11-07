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
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Client;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * A REST resource in forward mode passes the query string of the incoming request to the
 * upstream, as received (#953).
 */
public class ForwardQueryIntegrationTest {

    private final AtomicReference<String> receivedPath = new AtomicReference<>();

    private final AtomicReference<String> receivedQuery = new AtomicReference<>();

    private Component upstream;

    private Capability capability;

    private Client client;

    private int restPort;

    @BeforeEach
    void setUp() throws Exception {
        int upstreamPort = findFreePort();
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, upstreamPort);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.setDefaultMatchingMode(Template.MODE_STARTS_WITH);
                router.attach("/v1", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        receivedPath.set(request.getResourceRef().getPath());
                        receivedQuery.set(request.getResourceRef().getQuery());
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        upstream.start();

        restPort = findFreePort();
        String yaml = """
                ikanos: "0.4"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                      resources:
                        - path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: "upstream"
                  consumes:
                    - type: "http"
                      namespace: "upstream"
                      baseUri: "http://localhost:%d/v1"
                      inputParameters:
                        - name: "api-version"
                          in: "query"
                          value: "2"
                """.formatted(restPort, upstreamPort);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();

        client = new Client(new Context(), Protocol.HTTP);
        client.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (capability != null) {
            capability.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
    }

    private Response get(String pathAndQuery) {
        return client.handle(
                new Request(Method.GET, "http://localhost:" + restPort + pathAndQuery));
    }

    @Test
    void forwardsTheQueryStringAsReceived() {
        Response response = get("/proxy/items?limit=5&cursor=a%2Fb%20c&tag=x&tag=y");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("/v1/items", receivedPath.get());
        // The caller's query keeps its order, repetitions and encoding; the adapter's own query
        // parameter follows it.
        assertEquals("limit=5&cursor=a%2Fb%20c&tag=x&tag=y&api-version=2", receivedQuery.get());
    }

    @Test
    void sendsOnlyTheAdapterQueryWithoutACallerQuery() {
        Response response = get("/proxy/items");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("/v1/items", receivedPath.get());
        assertEquals("api-version=2", receivedQuery.get());
    }

    @Test
    void forwardTargetRefJoinsTheQueryToTheBaseUri() {
        assertEquals("http://h/v1/items",
                ResourceRestlet.forwardTargetRef("http://h/v1", "/items", null));
        assertEquals("http://h/v1/items",
                ResourceRestlet.forwardTargetRef("http://h/v1", "/items", ""));
        assertEquals("http://h/v1/items?a=1&b=%2F",
                ResourceRestlet.forwardTargetRef("http://h/v1", "/items", "a=1&b=%2F"));
        assertEquals("http://h/v1?key=k&a=1",
                ResourceRestlet.forwardTargetRef("http://h/v1?key=k", "", "a=1"));
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
