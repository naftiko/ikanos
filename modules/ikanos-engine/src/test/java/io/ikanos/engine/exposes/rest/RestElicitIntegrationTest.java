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
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A REST operation whose steps ask for input. The REST adapter cannot ask yet (answering on a
 * {@code 202} execution is not specified), so it answers {@code 501} naming the step, rather than
 * a generic server error.
 */
class RestElicitIntegrationTest {

    private static final String CAPABILITY = """
            ikanos: "%s"
            info:
              display: "Departure confirmation"
              description: "REST elicit test"
            capability:
              aggregates:
              - namespace: departure
                flows:
                  confirm-departure:
                    description: Ask the duty officer to confirm a departure.
                    steps:
                      confirm:
                        type: elicit
                        message: Confirm the departure?
                        fields:
                          confirmed:
                            type: boolean
                            description: Whether the departure is confirmed
              exposes:
              - type: rest
                address: localhost
                port: 0
                namespace: departures
                resources:
                  confirm:
                    path: /confirm
                    operations:
                      by-ref:
                        method: POST
                        ref: departure.confirm-departure
                  inline:
                    path: /confirm-inline
                    operations:
                      by-steps:
                        method: POST
                        steps:
                          ask:
                            type: elicit
                            message: Confirm the departure?
                            fields:
                              confirmed:
                                type: boolean
                                description: Whether the departure is confirmed
            """.formatted(VersionHelper.getSchemaVersion());

    private Response post(String resource, String path) throws Exception {
        IkanosSpec spec = IkanosMappers.yaml().readValue(CAPABILITY, IkanosSpec.class);
        Capability capability = new Capability(spec);
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof RestServerAdapter rest) {
                RestServerSpec restSpec = (RestServerSpec) rest.getSpec();
                ResourceRestlet restlet = new ResourceRestlet(capability, restSpec,
                        restSpec.getResources().get(resource));
                Request request = new Request(Method.POST,
                        new Reference("http://localhost" + path));
                Response response = new Response(request);
                restlet.handle(request, response);
                return response;
            }
        }
        throw new IllegalStateException("No REST adapter");
    }

    @Test
    void aFlowAskingForInputShouldAnswerNotImplemented() throws Exception {
        Response response = post("confirm", "/confirm");

        assertEquals(Status.SERVER_ERROR_NOT_IMPLEMENTED, response.getStatus());
        String body = response.getEntity().getText();
        assertTrue(body.contains("steps/confirm"), body);
        assertTrue(body.contains("MCP"), body);
    }

    @Test
    void inlineStepsAskingForInputShouldAnswerNotImplemented() throws Exception {
        Response response = post("inline", "/confirm-inline");

        assertEquals(Status.SERVER_ERROR_NOT_IMPLEMENTED, response.getStatus());
        assertTrue(response.getEntity().getText().contains("steps/ask"));
    }
}
