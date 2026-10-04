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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Regression tests for #739 through the MCP adapter: an orchestrated tool whose
 * {@code mappings} only read an earlier step must still report an error when any step fails.
 */
public class McpOrchestratedStepFailureIntegrationTest {

    private static final String UPSTREAM_MARKER = "UpstreamFailureDetail9c2e";

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void toolCallShouldBeErrorWhenLastStepFailsAndMappingsReadEarlierStep()
            throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, Status.SUCCESS_OK,
                Status.SERVER_ERROR_INTERNAL, new AtomicInteger());
        upstream.start();
        try {
            JsonNode result = callPay(port);

            assertTrue(result.path("isError").asBoolean(),
                    "#739: a failing 'charge' step must make the tool call an error, got: "
                            + result);
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void toolCallShouldNotRunLaterStepsWhenFirstStepFails() throws Exception {
        int port = findFreePort();
        AtomicInteger chargeHits = new AtomicInteger();
        Component upstream = createUpstream(port, Status.CLIENT_ERROR_NOT_FOUND,
                Status.SUCCESS_OK, chargeHits);
        upstream.start();
        try {
            JsonNode result = callPay(port);

            assertTrue(result.path("isError").asBoolean(),
                    "#739: a failing 'read-part' step must make the tool call an error");
            assertEquals(0, chargeHits.get(),
                    "#739: 'charge' must not run after 'read-part' failed");
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void toolCallShouldNotDiscloseUpstreamBodyWhenStepFails() throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, Status.SUCCESS_OK,
                Status.SERVER_ERROR_INTERNAL, new AtomicInteger());
        upstream.start();
        try {
            String text = callPay(port).path("content").get(0).path("text").asText();

            assertFalse(text.contains(UPSTREAM_MARKER), "upstream body leaked: " + text);
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void toolCallShouldSucceedWhenAllStepsSucceed() throws Exception {
        int port = findFreePort();
        AtomicInteger chargeHits = new AtomicInteger();
        Component upstream = createUpstream(port, Status.SUCCESS_OK, Status.SUCCESS_CREATED,
                chargeHits);
        upstream.start();
        try {
            JsonNode result = callPay(port);

            assertFalse(result.path("isError").asBoolean(), "expected success, got: " + result);
            assertEquals(1, chargeHits.get());
        } finally {
            upstream.stop();
        }
    }

    private JsonNode callPay(int upstreamPort) throws Exception {
        Capability capability = capabilityFromYaml(capabilityYaml(upstreamPort));
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "pay");
        params.putObject("arguments").put("part_id", "p-1");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        return dispatcher.dispatch(request).responseBody().path("result");
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
                          path: "/parts/{{part_id}}"
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
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: shop-tools
                      tools:
                        pay:
                          description: "Read a part, then charge its amount"
                          inputParameters:
                            part_id:
                              type: string
                              required: true
                              description: "Part identifier"
                          steps:
                            read-part:
                              type: call
                              call: shop.get-part
                              with:
                                part_id: "{{part_id}}"
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
