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
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test: a {@code {{name}}} variable in a consumed resource path takes the call
 * parameter of the same name, with no {@code inputParameters} entry on the adapter, the resource
 * or the operation (#978). The capability below declares no consumed input parameter at all.
 */
public class ConsumedPathParameterInferenceIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldSubstituteUndeclaredPathVariablesFromToolArguments()
            throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("account_id", "acme");
        arguments.put("invoice_id", "inv-1");

        assertEquals("/v1/accounts/acme/invoices/inv-1",
                recordedPath("get-invoice", arguments),
                "#978: tool arguments should fill the path variables without a declaration");
    }

    @Test
    public void handleToolCallShouldPercentEncodeUndeclaredPathVariable() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("account_id", "acme");
        arguments.put("invoice_id", "Q1 #3?draft");

        assertEquals("/v1/accounts/acme/invoices/Q1%20%233%3Fdraft",
                recordedPath("get-invoice", arguments),
                "#978: an undeclared path variable should stay one percent-encoded segment");
    }

    @Test
    public void handleToolCallShouldSubstituteUndeclaredPathVariableFromWithMapping()
            throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("invoice_id", "inv-2");

        assertEquals("/v1/accounts/house/invoices/inv-2",
                recordedPath("get-house-invoice", arguments),
                "#978: a with: value should fill an undeclared path variable");
    }

    private String recordedPath(String tool, ObjectNode arguments) throws Exception {
        AtomicReference<String> recorded = new AtomicReference<>();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Server upstream = new Server(Protocol.HTTP, "127.0.0.1", port);
        upstream.setNext(new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                recorded.set(request.getResourceRef().getPath());
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("{\"id\":\"inv\"}", MediaType.APPLICATION_JSON);
            }
        });
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(port));

            JsonNode result = callTool(capability, tool, arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            return recorded.get();
        } finally {
            upstream.stop();
        }
    }

    private JsonNode callTool(Capability capability, String tool, ObjectNode arguments) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return dispatcher.dispatch(request).responseBody().path("result");
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: billing
                      type: http
                      baseUri: "http://127.0.0.1:%d/v1"
                      resources:
                        invoice:
                          path: "/accounts/{{account_id}}/invoices/{{invoice_id}}"
                          operations:
                            get-invoice:
                              method: GET
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: billing-tools
                      tools:
                        get-invoice:
                          description: "Get an invoice of an account"
                          inputParameters:
                            account_id:
                              type: string
                              description: "Account identifier"
                            invoice_id:
                              type: string
                              description: "Invoice identifier"
                          call: billing.get-invoice
                        get-house-invoice:
                          description: "Get an invoice of the house account"
                          inputParameters:
                            invoice_id:
                              type: string
                              description: "Invoice identifier"
                          call: billing.get-invoice
                          with:
                            account_id: "house"
                """.formatted(schemaVersion, port);
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }
}
