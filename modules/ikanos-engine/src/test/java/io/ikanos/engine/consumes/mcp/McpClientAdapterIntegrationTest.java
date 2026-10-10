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
package io.ikanos.engine.consumes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Ikanos-to-Ikanos composition over MCP (design doc {@code mcp-client-adapter.md} §6).
 *
 * <p>A publisher capability exposes {@code get-invoice} over MCP Streamable HTTP, backed by a local
 * HTTP stub. A consumer capability declares {@code consumes: type: mcp} against the publisher,
 * composes the tool in an aggregate flow, and re-exposes it. Everything runs in-process on free
 * ports; no external network.</p>
 */
class McpClientAdapterIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private Component erp;
    private Capability publisher;
    private Capability consumer;
    private int mcpPort;

    @BeforeEach
    void setUp() throws Exception {
        int erpPort = freePort();
        mcpPort = freePort();
        erp = erpStub(erpPort);
        erp.start();
        publisher = capability(publisherYaml(erpPort, mcpPort));
        publisher.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.stop();
        }
        publisher.stop();
        erp.stop();
    }

    private static Capability capability(String yaml) throws Exception {
        return new Capability(YAML.readValue(yaml, IkanosSpec.class));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Component erpStub(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/invoices/{id}", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        String id = (String) request.getAttributes().get("id");
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new StringRepresentation("""
                                {"InvoiceID":"%s","Totals":{"Gross":"125.5","Currency":"EUR"},
                                 "State":"PAID"}""".formatted(id), MediaType.APPLICATION_JSON));
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static String publisherYaml(int erpPort, int mcpPort) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: erp
                      baseUri: http://localhost:%d
                      resources:
                        invoice:
                          path: /invoices/{{invoice-id}}
                          operations:
                            get-invoice:
                              method: GET
                              inputParameters:
                                invoice-id:
                                  in: path
                  exposes:
                    - type: mcp
                      transport: http
                      address: localhost
                      port: %d
                      namespace: billing-mcp
                      tools:
                        get-invoice:
                          description: Retrieve one invoice
                          hints: { readOnly: true, idempotent: true }
                          inputParameters:
                            invoice-id: { type: string, description: Invoice identifier }
                          call: erp.get-invoice
                          with:
                            invoice-id: billing-mcp.invoice-id
                          outputParameters:
                            - type: object
                              properties:
                                id:     { type: string, mapping: $.InvoiceID }
                                amount:
                                  type: object
                                  properties:
                                    total:    { type: number, mapping: $.Totals.Gross }
                                    currency: { type: string, mapping: $.Totals.Currency }
                                status: { type: string, mapping: $.State }
                        plain-text:
                          description: Mock tool returning a plain JSON text block
                          outputParameters:
                            greeting:
                              type: string
                              value: hello
                """.formatted(IKANOS, erpPort, mcpPort);
    }

    private String consumerYaml(String discovery, String extraTool) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: mcp
                      namespace: billing
                      description: Vendor billing capability
                      endpoint: http://localhost:%d/
                      authentication:
                        type: bearer
                        token: vendor-secret-token
                      discovery: %s
                      tools:
                        get-invoice:
                          description: Retrieve one invoice
                          hints: { readOnly: true, idempotent: true }
                          inputParameters:
                            invoice-id: { type: string, description: Invoice identifier }
                          outputParameters:
                            total:  { type: number, value: $.amount.total }
                            status: { type: string, value: $.status }
                        plain-text:
                          description: Mock tool
                          hints: { readOnly: true }
                %s
                  aggregates:
                    - namespace: collections
                      flows:
                        invoice-status:
                          description: Invoice total and status
                          inputParameters:
                            invoice-id: { type: string, description: Invoice identifier }
                          steps:
                            inv:
                              type: call
                              call: billing.get-invoice
                              with:
                                invoice-id: collections.invoice-id
                          mappings:
                            - { target: total,  value: $.inv.total }
                            - { target: status, value: $.inv.status }
                          outputParameters:
                            total:  { type: number }
                            status: { type: string }
                  exposes:
                    - type: mcp
                      transport: http
                      address: localhost
                      port: 0
                      namespace: collections-mcp
                      tools:
                        invoice-status:
                          description: Invoice total and status
                          ref: collections.invoice-status
                        greeting:
                          description: Pass-through of a text-only upstream tool
                          call: billing.plain-text
                """.formatted(IKANOS, mcpPort, discovery, extraTool);
    }

    private Capability startConsumer(String discovery, String extraTool) throws Exception {
        consumer = capability(consumerYaml(discovery, extraTool));
        consumer.start();
        return consumer;
    }

    private McpSchema.CallToolResult callConsumer(String tool, Map<String, Object> args)
            throws Exception {
        McpServerAdapter mcp = (McpServerAdapter) consumer.getServerAdapters().get(0);
        return mcp.getToolHandler().handleToolCall(tool, args);
    }

    private static JsonNode textPayload(McpSchema.CallToolResult result) throws Exception {
        return JSON.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void consumerShouldInstantiateMcpClientAdapter() throws Exception {
        startConsumer("verify", "");

        assertInstanceOf(McpClientAdapter.class, consumer.getClientAdapters().get(0));
    }

    @Test
    void flowShouldComposeUpstreamToolThroughStructuredContent() throws Exception {
        startConsumer("verify", "");

        McpSchema.CallToolResult result =
                callConsumer("invoice-status", Map.of("invoice-id", "INV-42"));

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result.content()));
        JsonNode payload = textPayload(result);
        assertEquals(125.5, payload.path("total").asDouble());
        assertEquals("PAID", payload.path("status").asText());
    }

    @Test
    void composedToolShouldStayTypedOneHopFurther() throws Exception {
        startConsumer("verify", "");

        McpSchema.CallToolResult result =
                callConsumer("invoice-status", Map.of("invoice-id", "INV-42"));

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertNotNull(structured, "consumer's own steps tool should publish structuredContent");
        assertEquals("PAID", structured.get("status"));
    }

    @Test
    void singleTextBlockShouldBeParsedAsJsonWhenNoStructuredContent() throws Exception {
        startConsumer("verify", "");

        McpSchema.CallToolResult result = callConsumer("greeting", Map.of());

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result.content()));
        assertEquals("hello", textPayload(result).path("greeting").asText());
    }

    @Test
    void discoveryShouldCacheUpstreamOutputSchema() throws Exception {
        startConsumer("verify", "");

        McpClientAdapter adapter = (McpClientAdapter) consumer.getClientAdapters().get(0);
        assertNotNull(adapter.outputSchema("get-invoice"));
    }

    @Test
    void discoveryShouldFailStartWhenDeclaredToolIsMissingUpstream() throws Exception {
        consumer = capability(consumerYaml("verify", """
                        no-such-tool:
                          description: Missing upstream
                          hints: { readOnly: true }
                """));

        Exception error = assertThrows(Exception.class, consumer::start);

        assertTrue(error.getMessage().contains("tool 'no-such-tool' not found upstream"),
                error.getMessage());
        consumer.stop();
        consumer = null;
    }

    @Test
    void discoveryOffShouldSkipStartupListing() throws Exception {
        startConsumer("off", """
                        no-such-tool:
                          description: Missing upstream, not checked
                          hints: { readOnly: true }
                """);

        McpClientAdapter adapter = (McpClientAdapter) consumer.getClientAdapters().get(0);
        assertEquals(null, adapter.outputSchema("get-invoice"));
    }

    @Test
    void callToUndeclaredUpstreamToolShouldFailWithMcpError() throws Exception {
        startConsumer("off", """
                        no-such-tool:
                          description: Missing upstream, not checked
                          hints: { readOnly: true }
                """);
        McpClientAdapter adapter = (McpClientAdapter) consumer.getClientAdapters().get(0);

        McpClientException error = assertThrows(McpClientException.class,
                () -> adapter.prepare("no-such-tool", Map.of()).invoke());

        assertTrue(error.getMessage().contains("Upstream MCP tools/call failed"),
                error.getMessage());
    }
}
