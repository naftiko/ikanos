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
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Regression tests for #739 through the REST adapter. A failing call step in an orchestrated
 * operation (inline {@code steps:} or an aggregate {@code ref:}) answers 502 Bad Gateway with a
 * generic message and a correlation id, whatever the upstream status was (404, 409, 500...).
 * The upstream body is not forwarded and later steps do not run.
 */
public class RestOrchestratedStepFailureIntegrationTest {

    private static final String UPSTREAM_MARKER = "UpstreamFailureDetail4b1d";

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void inlineStepsShouldAnswerBadGatewayWhenLastStepFails() throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, Status.SUCCESS_OK, Status.CLIENT_ERROR_CONFLICT,
                new AtomicInteger());
        upstream.start();
        try {
            Response response = invoke(port, "pay-inline");

            assertEquals(Status.SERVER_ERROR_BAD_GATEWAY, response.getStatus(),
                    "#739: a failing 'charge' step must not be reported as success");
            String body = response.getEntity().getText();
            assertFalse(body.contains(UPSTREAM_MARKER), "upstream body leaked: " + body);
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void inlineStepsShouldNotRunLaterStepsWhenFirstStepFails() throws Exception {
        int port = findFreePort();
        AtomicInteger chargeHits = new AtomicInteger();
        Component upstream = createUpstream(port, Status.CLIENT_ERROR_NOT_FOUND,
                Status.SUCCESS_OK, chargeHits);
        upstream.start();
        try {
            Response response = invoke(port, "pay-inline");

            assertEquals(Status.SERVER_ERROR_BAD_GATEWAY, response.getStatus());
            assertEquals(0, chargeHits.get(),
                    "#739: 'charge' must not run after 'read-part' failed");
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void aggregateRefShouldAnswerBadGatewayWhenStepFails() throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, Status.SUCCESS_OK,
                Status.SERVER_ERROR_INTERNAL, new AtomicInteger());
        upstream.start();
        try {
            Response response = invoke(port, "pay-ref");

            assertEquals(Status.SERVER_ERROR_BAD_GATEWAY, response.getStatus());
            String body = response.getEntity().getText();
            assertFalse(body.contains(UPSTREAM_MARKER), "upstream body leaked: " + body);
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void inlineStepsShouldSucceedWhenAllStepsSucceed() throws Exception {
        int port = findFreePort();
        AtomicInteger chargeHits = new AtomicInteger();
        Component upstream = createUpstream(port, Status.SUCCESS_OK, Status.SUCCESS_CREATED,
                chargeHits);
        upstream.start();
        try {
            Response response = invoke(port, "pay-inline");

            assertEquals(Status.SUCCESS_OK, response.getStatus());
            assertEquals(1, chargeHits.get());
        } finally {
            upstream.stop();
        }
    }

    private Response invoke(int upstreamPort, String resourceName) throws Exception {
        Capability capability = capabilityFromYaml(capabilityYaml(upstreamPort));
        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        RestServerResourceSpec resourceSpec = serverSpec.getResources().get(resourceName);
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec, resourceSpec);

        Request request = new Request(Method.POST, "http://localhost" + resourceSpec.getPath());
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }

    /** read-part, then charge; mappings only read read-part (the #739 shape). */
    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: shop
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        parts:
                          path: "/parts/p-1"
                          operations:
                            get-part:
                              method: GET
                        charges:
                          path: "/charges"
                          operations:
                            create-charge:
                              method: POST
                              body: |
                                {"amount": "{{amount}}"}
                  aggregates:
                    - namespace: billing
                      flows:
                        pay:
                          description: "Read a part, then charge its amount"
                          steps:
                            read-part:
                              type: call
                              call: shop.get-part
                            charge:
                              type: call
                              call: shop.create-charge
                              with:
                                amount: "{{read-part.amount}}"
                          mappings:
                            - target: amount
                              value: "$.read-part.amount"
                          outputParameters:
                            amount:
                              type: number
                  exposes:
                    - type: rest
                      port: 0
                      namespace: shop-api
                      resources:
                        pay-inline:
                          path: "/pay-inline"
                          operations:
                            pay:
                              method: POST
                              steps:
                                read-part:
                                  type: call
                                  call: shop.get-part
                                charge:
                                  type: call
                                  call: shop.create-charge
                                  with:
                                    amount: "{{read-part.amount}}"
                              mappings:
                                - target: amount
                                  value: "$.read-part.amount"
                              outputParameters:
                                - type: object
                                  properties:
                                    amount:
                                      type: number
                        pay-ref:
                          path: "/pay-ref"
                          operations:
                            pay:
                              method: POST
                              ref: billing.pay
                """.formatted(schemaVersion, port);
    }

    private static Component createUpstream(int port, Status partStatus, Status chargeStatus,
            AtomicInteger chargeHits) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/parts/p-1", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(partStatus);
                        response.setEntity(partStatus.isSuccess()
                                ? "{\"amount\":500000}"
                                : "{\"message\":\"" + UPSTREAM_MARKER + "\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                router.attach("/charges", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        chargeHits.incrementAndGet();
                        response.setStatus(chargeStatus);
                        response.setEntity(chargeStatus.isSuccess()
                                ? "{\"ok\":true}"
                                : "{\"message\":\"" + UPSTREAM_MARKER + "\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
