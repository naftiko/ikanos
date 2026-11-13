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
import io.ikanos.spec.util.VersionHelper;

/**
 * A REST resource in forward mode passes the query string of the incoming request to the
 * upstream, as received (#953), except the exposed adapter's query API key and the parameters the
 * consumed adapter sets itself.
 */
public class ForwardQueryIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private final AtomicReference<String> receivedPath = new AtomicReference<>();

    private final AtomicReference<String> receivedQuery = new AtomicReference<>();

    private Component upstream;

    private Capability capability;

    private Client client;

    private int restPort;

    private int upstreamPort;

    @BeforeEach
    void setUp() throws Exception {
        upstreamPort = findFreePort();
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

        client = new Client(new Context(), Protocol.HTTP);
        client.start();
    }

    /**
     * Starts a capability that forwards {@code /proxy/{{path}}} to the upstream.
     *
     * @param exposedAuthentication the YAML of the exposed adapter's authentication block, at the
     *        adapter's indentation, or an empty string
     * @param consumedExtras the YAML added to the consumed adapter, at its indentation
     */
    private void startCapability(String exposedAuthentication, String consumedExtras)
            throws Exception {
        startCapability(exposedAuthentication, "", consumedExtras);
    }

    /**
     * Same as {@link #startCapability(String, String)}, with {@code baseUriQuery} (such as
     * {@code ?key=k}, or an empty string) appended to the consumed adapter's base URI.
     */
    private void startCapability(String exposedAuthentication, String baseUriQuery,
            String consumedExtras) throws Exception {
        restPort = findFreePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                %s
                      resources:
                        - path: "/proxy/{{path}}"
                          forward:
                            targetNamespace: "upstream"
                  consumes:
                    - type: "http"
                      namespace: "upstream"
                      baseUri: "http://localhost:%d/v1%s"
                %s
                """.formatted(IKANOS, restPort, exposedAuthentication, upstreamPort,
                baseUriQuery, consumedExtras);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
    }

    /** A consumed adapter that adds its own {@code api-version=2} query parameter. */
    private void startCapabilityWithAdapterQueryParameter() throws Exception {
        startCapability("", """
                      inputParameters:
                        - name: "api-version"
                          in: "query"
                          value: "2"
                """);
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
    void forwardsTheQueryStringAsReceived() throws Exception {
        startCapabilityWithAdapterQueryParameter();
        Response response = get("/proxy/items?limit=5&cursor=a%2Fb%20c&tag=x&tag=y");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("/v1/items", receivedPath.get());
        // The caller's query keeps its order, repetitions and encoding; the adapter's own query
        // parameter follows it.
        assertEquals("limit=5&cursor=a%2Fb%20c&tag=x&tag=y&api-version=2", receivedQuery.get());
    }

    @Test
    void sendsOnlyTheAdapterQueryWithoutACallerQuery() throws Exception {
        startCapabilityWithAdapterQueryParameter();
        Response response = get("/proxy/items");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("/v1/items", receivedPath.get());
        assertEquals("api-version=2", receivedQuery.get());
    }

    @Test
    void forwardShouldStripTheExposedApiKeyWhenItsPlacementIsQuery() throws Exception {
        startCapability("""
                      authentication:
                        type: "apikey"
                        key: "api_key"
                        value: "ikanos-secret"
                        placement: "query"
                """, "");

        Response response = get("/proxy/items?limit=5&api_key=ikanos-secret&tag=x");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        // The key that protects Ikanos never reaches the upstream
        assertEquals("limit=5&tag=x", receivedQuery.get());
    }

    @Test
    void forwardShouldStripTheExposedApiKeyWhenItsNameIsPercentEncoded() throws Exception {
        startCapability("""
                      authentication:
                        type: "apikey"
                        key: "api_key"
                        value: "ikanos-secret"
                        placement: "query"
                """, "");

        Response response = get("/proxy/items?api%5Fkey=ikanos-secret&limit=5");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("limit=5", receivedQuery.get());
    }

    @Test
    void forwardShouldDropCallerParametersThatTheAdapterSetsItself() throws Exception {
        startCapability("", """
                      authentication:
                        type: "apikey"
                        key: "key"
                        value: "upstream-secret"
                        placement: "query"
                      inputParameters:
                        - name: "api-version"
                          in: "query"
                          value: "2"
                """);

        Response response = get("/proxy/items?api-version=1&limit=5&key=caller&api-version=0");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        // Only the adapter's values remain for the names it sets
        assertEquals("limit=5&api-version=2&key=upstream-secret", receivedQuery.get());
    }

    @Test
    void forwardShouldDropTheConsumedApiKeyWhoseNameAnAdapterInputSets() throws Exception {
        startCapability("", """
                      authentication:
                        type: "apikey"
                        key: "{{key-name}}"
                        value: "upstream-secret"
                        placement: "query"
                      inputParameters:
                        - name: "key-name"
                          in: "body"
                          value: "key"
                """);

        Response response = get("/proxy/items?key=caller&limit=5");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        // The key name is rendered with the adapter's inputs, as authentication renders it, so
        // the caller's credential never precedes the configured one
        assertEquals("limit=5&key=upstream-secret", receivedQuery.get());
    }

    @Test
    void forwardShouldDropCallerParametersPinnedInTheBaseUri() throws Exception {
        startCapability("", "?key=k&api%5Fversion=2", "");

        Response response = get("/proxy/items?key=evil&limit=5&api_version=1&key=evil2");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("/v1/items", receivedPath.get());
        // The base URI's own query parameters cannot be repeated, so an upstream that takes the
        // last value still gets the pinned one
        assertEquals("key=k&api%5Fversion=2&limit=5", receivedQuery.get());
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
