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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test proving that an MCP tool backed by a consumed operation with a structured
 * {@code formUrlEncoded} body sends a real {@code application/x-www-form-urlencoded} payload to
 * the upstream API (YAML, then Capability, then MCP tools/call, then HTTP), as form-only APIs
 * such as Stripe require.
 */
public class McpFormUrlEncodedBodyIntegrationTest {

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSendFormUrlEncodedPayloadToUpstream() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<MediaType> receivedType = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody, receivedType);
        upstream.start();

        try {
            JsonNode result = callCreateSession(port, "500000", "Shaft & seal");

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            assertEquals("mode=payment"
                    + "&line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=500000"
                    + "&line_items%5B0%5D%5Bprice_data%5D%5Bproduct_data%5D%5Bname%5D=Shaft+%26+seal",
                    receivedBody.get());
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldSendFormUrlEncodedMediaTypeToUpstream() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<MediaType> receivedType = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody, receivedType);
        upstream.start();

        try {
            callCreateSession(port, "100", "Bolt");

            assertEquals(MediaType.APPLICATION_WWW_FORM, receivedType.get());
        } finally {
            upstream.stop();
        }
    }

    private JsonNode callCreateSession(int upstreamPort, String amount, String name)
            throws Exception {
        Capability capability = capabilityFromYaml(capabilityYaml(upstreamPort));
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "create-session");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("amount", amount);
        arguments.put("name", name);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: payments
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        sessions:
                          path: "/checkout/sessions"
                          operations:
                            create-session:
                              method: POST
                              body:
                                type: formUrlEncoded
                                data:
                                  mode: "payment"
                                  "line_items[0][price_data][unit_amount]": "{{amount}}"
                                  "line_items[0][price_data][product_data][name]": "{{name}}"
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: payment-tools
                      tools:
                        create-session:
                          description: "Create a checkout session"
                          inputParameters:
                            amount:
                              type: string
                              description: "Amount in cents"
                            name:
                              type: string
                              description: "Line item name"
                          call: payments.create-session
                          with:
                            amount: payment-tools.amount
                            name: payment-tools.name
                          outputParameters:
                            - type: object
                              properties:
                                id:
                                  type: string
                                  mapping: "$.id"
                """.formatted(schemaVersion, port);
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> body,
            AtomicReference<MediaType> type) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/checkout/sessions", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            body.set(request.getEntity().getText());
                            type.set(request.getEntity().getMediaType());
                        } catch (java.io.IOException e) {
                            body.set("<unreadable: " + e.getMessage() + ">");
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"id\":\"cs_test_1\"}", MediaType.APPLICATION_JSON);
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
