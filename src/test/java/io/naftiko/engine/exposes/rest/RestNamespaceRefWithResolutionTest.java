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
package io.naftiko.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.util.VersionHelper;

/**
 * Regression test: REST adapter must resolve namespace-qualified 'with' references
 * (e.g. shipyard-api.shipImo) before passing them to the upstream body template.
 *
 * When an operation uses with: { shipImo: shipyard-api.shipImo }, the upstream
 * body template {{shipImo}} must receive the actual request value "IMO-9321483",
 * not the literal string "shipyard-api.shipImo".
 */
public class RestNamespaceRefWithResolutionTest {
  private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void withNamespaceRefsShouldResolveToActualValuesInUpstreamBody() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        int mockPort = findFreePort();
        Component mockServer = new Component();
        mockServer.getServers().add(Protocol.HTTP, mockPort);
        mockServer.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/voyages", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            receivedBody.set(request.getEntity().getText());
                        } catch (Exception e) {
                            receivedBody.set("error: " + e.getMessage());
                        }
                        response.setStatus(Status.SUCCESS_CREATED);
                        response.setEntity(
                                "{\"voyageId\":\"VOY-001\",\"shipImo\":\"IMO-9321483\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        mockServer.start();

        try {
            String yaml = """
                    naftiko: "%s"
                    capability:
                      exposes:
                        - type: "rest"
                          address: "localhost"
                          port: 0
                          namespace: "shipyard-api"
                          resources:
                            - name: voyages
                              path: "/voyages"
                              operations:
                                - name: create-voyage
                                  method: POST
                                  inputParameters:
                                    - name: shipImo
                                      in: body
                                  call: registry.create-voyage
                                  with:
                                    shipImo: "shipyard-api.shipImo"
                      consumes:
                        - type: "http"
                          namespace: "registry"
                          baseUri: "http://localhost:%d"
                          resources:
                            - name: voyages
                              path: "/voyages"
                              operations:
                                - name: create-voyage
                                  method: POST
                                  body: |
                                    {"shipImo": "{{shipImo}}"}
                    """.formatted(schemaVersion, mockPort);

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);

            Capability capability = new Capability(spec);
            RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(0);
            RestServerSpec serverSpec = (RestServerSpec) adapter.getSpec();
            RestServerResourceSpec resourceSpec = serverSpec.getResources().get(0);
            ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec, resourceSpec);

            Request request = new Request(Method.POST, "http://localhost/voyages");
            request.setEntity("{\"shipImo\":\"IMO-9321483\"}", MediaType.APPLICATION_JSON);
            Response response = new Response(request);

            restlet.handle(request, response);

            assertEquals(Status.SUCCESS_CREATED, response.getStatus(),
                    "REST adapter should resolve namespace ref in 'with' and call upstream successfully");

            String body = receivedBody.get();
            assertEquals("{\"shipImo\": \"IMO-9321483\"}\n", body,
                    "Upstream should receive resolved shipImo value, not the namespace reference literal");
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
