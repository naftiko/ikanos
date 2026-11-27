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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.fake.FakeClientSpec;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A REST operation that reaches a URL mode {@code elicit} step (issue 1151) answers {@code 202}
 * with the URL request in its {@code inputRequests}, and completes once the caller accepted and
 * the step's {@code check} found the payment method. The consumed operation is the test
 * {@code fake} adapter's canned response, changed to play the payment page.
 */
class RestUrlElicitationIntegrationTest {

    private static final String KEY = "steps/payment";
    private static final String ACCEPT =
            "{\"inputResponses\":{\"" + KEY + "\":{\"action\":\"accept\"}}}";

    private final ObjectMapper json = IkanosMappers.json();

    private static String capability(String mode) {
        return """
                ikanos: "%s"
                info:
                  display: "Paid projects"
                  description: "REST URL mode elicitation"
                capability:
                  consumes:
                    - type: fake
                      namespace: payments
                      operations:
                        get-setup-status:
                          outputParameters: []
                      responses:
                        get-setup-status: '{"status":"pending"}'
                  aggregates:
                  - namespace: billing
                    flows:
                      add-payment-method:
                        description: Ask the customer to add a payment method on the payment page.
                        steps:
                          payment:
                            type: elicit
                            mode: url
                            message: Add a payment method.
                            url: "https://billing.example.com/setup?ref={{elicitation.id}}"
                            check:
                              type: call
                              call: payments.get-setup-status
                              with:
                                ref: "{{elicitation.id}}"
                            outputParameters:
                              paymentMethodId:
                                type: string
                        mappings:
                        - target: paymentMethodId
                          value: $.payment.paymentMethodId
                        outputParameters:
                          paymentMethodId:
                            type: string
                  exposes:
                  - type: rest
                    address: localhost
                    port: 0
                    namespace: billing-api
                    resources:
                      payment-methods:
                        path: /payment-methods
                        operations:
                          add:
                            method: POST
                            ref: billing.add-payment-method
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: %s
                """.formatted(VersionHelper.getSchemaVersion(), mode);
    }

    private static Capability load(String mode) throws Exception {
        return new Capability(
                IkanosMappers.yaml().readValue(capability(mode), IkanosSpec.class));
    }

    private static RestServerAdapter rest(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof RestServerAdapter rest) {
                return rest;
            }
        }
        throw new IllegalStateException("No REST adapter");
    }

    private static void setupStatus(Capability capability, String body) {
        for (ClientAdapter adapter : capability.getClientAdapters()) {
            if (adapter.getSpec() instanceof FakeClientSpec fake) {
                fake.getResponses().put("get-setup-status", body);
            }
        }
    }

    private static Response send(RestServerAdapter adapter, String path, String body) {
        Request request = new Request(Method.POST, new Reference("http://localhost" + path));
        request.getResourceRef().setBaseRef("http://localhost");
        if (body != null) {
            request.setEntity(body, MediaType.APPLICATION_JSON);
        }
        Response response = new Response(request);
        adapter.getRouter().handle(request, response);
        return response;
    }

    private JsonNode body(Response response) throws Exception {
        return json.readTree(response.getEntity().getText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void anOperationShouldAnswer202WithAUrlRequest(String mode) throws Exception {
        Response asked = send(rest(load(mode)), "/payment-methods", null);

        assertEquals(Status.SUCCESS_ACCEPTED, asked.getStatus());
        JsonNode params = body(asked).path("inputRequests").path(KEY).path("params");
        assertEquals("url", params.path("mode").asString(), params.toString());
        assertTrue(params.path("url").asString()
                .startsWith("https://billing.example.com/setup?ref="), params.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void postingAnAcceptShouldCompleteOnceTheCheckFindsTheValue(String mode) throws Exception {
        Capability capability = load(mode);
        RestServerAdapter adapter = rest(capability);
        Response asked = send(adapter, "/payment-methods", null);
        setupStatus(capability, "{\"status\":\"done\",\"paymentMethodId\":\"pm_123\"}");

        Response done = send(adapter, asked.getLocationRef().getPath(), ACCEPT);

        assertEquals(Status.SUCCESS_OK, done.getStatus(), String.valueOf(done.getEntity()));
        assertEquals("pm_123", body(done).path("paymentMethodId").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void postingAnAcceptShouldAskAgainWhileTheCheckFindsNoValue(String mode) throws Exception {
        RestServerAdapter adapter = rest(load(mode));
        Response asked = send(adapter, "/payment-methods", null);

        Response again = send(adapter, asked.getLocationRef().getPath(), ACCEPT);

        assertEquals(Status.SUCCESS_ACCEPTED, again.getStatus());
        assertEquals("url", body(again).path("inputRequests").path(KEY + "@2").path("params")
                .path("mode").asString(), String.valueOf(body(again)));
    }
}
