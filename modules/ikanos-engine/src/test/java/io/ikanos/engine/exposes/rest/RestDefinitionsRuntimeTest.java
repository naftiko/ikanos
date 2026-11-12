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

import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Restlet;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import org.restlet.routing.TemplateRoute;
import org.restlet.routing.Variable;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Serves a REST operation whose path parameter comes from an input parameter set of the
 * adapter's {@code definitions}, applied on its resource (#1000), against a local upstream: the
 * operation inherits the expanded input and resolves it as if it were declared inline.
 */
class RestDefinitionsRuntimeTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void operationShouldResolveAPathParameterFromItsResourceSet() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        int mockPort = findFreePort();
        Component mockServer = new Component();
        mockServer.getServers().add(Protocol.HTTP, mockPort);
        mockServer.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                TemplateRoute route = router.attach("/ships/{imo_number}", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        receivedPath.set(request.getResourceRef().getPath());
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"imo_number\":\"IMO-9321483\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                route.getTemplate().getVariables()
                        .put("imo_number", new Variable(Variable.TYPE_URI_PATH));
                return router;
            }
        });
        mockServer.start();
        try {
            String yaml = """
                    ikanos: "%s"
                    capability:
                      exposes:
                        - type: rest
                          address: localhost
                          port: 0
                          namespace: shipyard-api
                          definitions:
                            inputParameterSets:
                              ship-key:
                                imo: { in: path, type: string, description: "IMO number." }
                          resources:
                            ship-by-imo:
                              path: "/ships/{{imo}}"
                              inputParameterSets: [ship-key]
                              operations:
                                get-ship:
                                  method: GET
                                  call: registry.get-ship
                                  with:
                                    imo_number: "{{imo}}"
                      consumes:
                        - type: http
                          namespace: registry
                          baseUri: "http://localhost:%d"
                          resources:
                            ship:
                              path: "/ships/{{imo_number}}"
                              operations:
                                get-ship:
                                  method: GET
                                  inputParameters:
                                    imo_number: { in: path }
                    """.formatted(IKANOS, mockPort);
            IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
            Capability capability = new Capability(spec);
            RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(0);
            RestServerSpec serverSpec = (RestServerSpec) adapter.getSpec();
            RestServerResourceSpec resourceSpec = serverSpec.getResources().get("ship-by-imo");
            ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec, resourceSpec);
            Request request = new Request(Method.GET, "http://localhost/ships/IMO-9321483");
            request.getAttributes().put("imo", "IMO-9321483");
            Response response = new Response(request);

            restlet.handle(request, response);

            assertEquals(Status.SUCCESS_OK, response.getStatus());
            assertEquals("/ships/IMO-9321483", receivedPath.get(),
                    "The path parameter from the resource's set reaches the upstream");
        } finally {
            mockServer.stop();
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
