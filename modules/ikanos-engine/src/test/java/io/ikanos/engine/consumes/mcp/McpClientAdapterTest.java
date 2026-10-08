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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;

/**
 * Unit tests for {@link McpClientAdapter}: request shape on the wire, response body selection,
 * error mapping, SSE handling, discovery and output validation. No network: the Restlet
 * {@link Client} is replaced by a capturing stub.
 */
class McpClientAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Restlet client stub: records requests, answers with a scripted response. */
    static final class StubClient extends Client {
        final List<Request> requests = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        Function<JsonNode, Response> answer;

        StubClient() {
            super(Protocol.HTTP);
        }

        @Override
        public void handle(Request request, Response response) {
            requests.add(request);
            try {
                String body = request.getEntity().getText();
                bodies.add(body);
                Response scripted = answer.apply(JSON.readTree(body));
                response.setStatus(scripted.getStatus());
                response.setEntity(scripted.getEntity());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized void start() {
            // no connector
        }

        @Override
        public synchronized void stop() {
            // no connector
        }
    }

    private static Response json(int status, String body) {
        Response r = new Response(new Request());
        r.setStatus(Status.valueOf(status));
        r.setEntity(body, MediaType.APPLICATION_JSON);
        return r;
    }

    private static String rpcResult(JsonNode request, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":"
                + resultJson + "}";
    }

    private static McpClientSpec spec(String extra) throws Exception {
        return (McpClientSpec) YAML.readValue("""
                type: mcp
                namespace: billing
                endpoint: https://billing.example.com/mcp
                authentication:
                  type: bearer
                  token: "{{token}}"
                discovery: off
                tools:
                  get-invoice:
                    description: Retrieve one invoice
                    hints: { readOnly: true }
                    inputParameters:
                      invoice-id: { type: string, description: Invoice identifier }
                      locale: { type: string, description: Locale, required: false }
                    outputParameters:
                      total: { type: number, value: $.amount.total }
                %s
                """.formatted(extra), ClientSpec.class);
    }

    private static McpClientAdapter adapter(StubClient client, String extra) throws Exception {
        return new McpClientAdapter(null, spec(extra), client);
    }

    @Test
    void specShouldDeserializeAsMcpClientSpecWithToolKeyedByUpstreamName() throws Exception {
        McpClientSpec spec = spec("");

        assertEquals("https://billing.example.com/mcp", spec.getEndpoint());
        assertFalse(spec.isDiscoveryVerify());
        assertEquals("get-invoice", spec.getTools().get("get-invoice").getName());
        assertEquals(2, spec.getTools().get("get-invoice").getInputParameters().size());
    }

    @Test
    void prepareShouldReturnNullForUndeclaredTool() throws Exception {
        assertNull(adapter(new StubClient(), "").prepare("other", Map.of()));
    }

    @Test
    void prepareShouldFailWhenRequiredArgumentIsMissing() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter(new StubClient(), "").prepare("get-invoice", Map.of()));

        assertTrue(error.getMessage().contains("'invoice-id'"), error.getMessage());
    }

    @Test
    void toolsCallShouldCarryStatelessEnvelopeHeadersMetaAuthAndDeclaredArgumentsOnly()
            throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> json(200, rpcResult(req,
                "{\"content\":[],\"structuredContent\":{\"amount\":{\"total\":3}},"
                        + "\"resultType\":\"complete\"}"));

        adapter(client, "").prepare("get-invoice",
                Map.of("invoice-id", "INV-1", "token", "s3cret", "undeclared", "x")).invoke();

        Request request = client.requests.get(0);
        JsonNode body = JSON.readTree(client.bodies.get(0));
        assertEquals("tools/call", body.path("method").asText());
        assertEquals("get-invoice", body.path("params").path("name").asText());
        assertEquals("INV-1", body.path("params").path("arguments").path("invoice-id").asText());
        assertFalse(body.path("params").path("arguments").has("undeclared"));
        assertFalse(body.path("params").path("arguments").has("locale"));
        assertEquals(McpStreamableHttpTransport.PROTOCOL_VERSION, body.path("params")
                .path("_meta").path(McpStreamableHttpTransport.META_PROTOCOL_VERSION).asText());
        assertEquals("tools/call", request.getHeaders().getFirstValue("Mcp-Method", true));
        assertEquals("get-invoice", request.getHeaders().getFirstValue("Mcp-Name", true));
        assertEquals(McpStreamableHttpTransport.PROTOCOL_VERSION,
                request.getHeaders().getFirstValue("MCP-Protocol-Version", true));
        assertEquals("s3cret", request.getChallengeResponse().getRawValue());
        assertNull(request.getHeaders().getFirstValue("Mcp-Session-Id", true));
    }

    @Test
    void structuredContentShouldBeTheResponseDocument() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> json(200, rpcResult(req,
                "{\"content\":[{\"type\":\"text\",\"text\":\"ignored\"}],"
                        + "\"structuredContent\":{\"amount\":{\"total\":7}}}"));

        ConsumedResult result = adapter(client, "")
                .prepare("get-invoice", Map.of("invoice-id", "1")).invoke();

        assertEquals(7, result.document().path("amount").path("total").asInt());
        assertEquals(200, result.status());
    }

    @Test
    void singleJsonTextBlockShouldBeParsed() throws Exception {
        JsonNode result = JSON.readTree(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"a\\\":1}\"}]}");

        ConsumedResult body = McpClientAdapter.selectBody(result, ConsumedOperationView.NONE);

        assertEquals(1, body.document().path("a").asInt());
    }

    @Test
    void singleNonJsonTextBlockShouldBeRawString() throws Exception {
        JsonNode result = JSON.readTree(
                "{\"content\":[{\"type\":\"text\",\"text\":\"plain words\"}]}");

        ConsumedResult body = McpClientAdapter.selectBody(result, ConsumedOperationView.NONE);

        assertEquals("plain words", body.document().asText());
        assertEquals("plain words", body.text());
    }

    @Test
    void textBlockStartingWithJsonTokenShouldStayRawString() throws Exception {
        for (String text : List.of("3 results found", "42 apples", "true story",
                "null and void")) {
            JsonNode result = JSON.createObjectNode().set("content", JSON.createArrayNode()
                    .add(JSON.createObjectNode().put("type", "text").put("text", text)));

            ConsumedResult body = McpClientAdapter.selectBody(result, ConsumedOperationView.NONE);

            assertTrue(body.document().isTextual(), text);
            assertEquals(text, body.document().asText());
        }
    }

    @Test
    void multipleOrBinaryContentBlocksShouldFailNamingTheTypes() throws Exception {
        JsonNode result = JSON.readTree("{\"content\":[{\"type\":\"image\",\"data\":\"x\"},"
                + "{\"type\":\"text\",\"text\":\"t\"}]}");

        McpClientException error = assertThrows(McpClientException.class,
                () -> McpClientAdapter.selectBody(result, ConsumedOperationView.NONE));

        assertTrue(error.getMessage().contains("[image, text]"), error.getMessage());
    }

    @Test
    void isErrorResultShouldFailWithUpstreamText() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> json(200, rpcResult(req,
                "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"boom\"}],"
                        + "\"structuredContent\":{\"x\":1}}"));

        McpClientException error = assertThrows(McpClientException.class, () -> adapter(client,
                "").prepare("get-invoice", Map.of("invoice-id", "1")).invoke());

        assertTrue(error.getMessage().contains("boom"));
    }

    @Test
    void inputRequiredResultTypeShouldFail() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> json(200, rpcResult(req,
                "{\"resultType\":\"input_required\",\"content\":[]}"));

        McpClientException error = assertThrows(McpClientException.class, () -> adapter(client,
                "").prepare("get-invoice", Map.of("invoice-id", "1")).invoke());

        assertTrue(error.getMessage().contains("'input_required'"), error.getMessage());
    }

    @Test
    void unsupportedProtocolVersionShouldFailWithDedicatedMessage() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> json(400, "{\"jsonrpc\":\"2.0\",\"id\":" + req.path("id")
                + ",\"error\":{\"code\":-32022,\"message\":\"Unsupported protocol version\"}}");

        McpClientException error = assertThrows(McpClientException.class, () -> adapter(client,
                "").prepare("get-invoice", Map.of("invoice-id", "1")).invoke());

        assertTrue(error.getMessage().contains("Only stateless upstreams are supported"),
                error.getMessage());
    }

    @Test
    void httpErrorWithoutJsonRpcBodyShouldReportHttpStatus() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> json(503, "");

        McpClientException error = assertThrows(McpClientException.class, () -> adapter(client,
                "").prepare("get-invoice", Map.of("invoice-id", "1")).invoke());

        assertTrue(error.getMessage().contains("HTTP 503"), error.getMessage());
    }

    @Test
    void httpErrorWithHtmlBodyShouldReportHttpStatusNotJsonFailure() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> {
            Response r = new Response(new Request());
            r.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
            r.setEntity("<html><body><h1>401 Authorization Required</h1>"
                    + "x".repeat(500) + "</body></html>", MediaType.TEXT_HTML);
            return r;
        };

        McpClientException error = assertThrows(McpClientException.class, () -> adapter(client,
                "").prepare("get-invoice", Map.of("invoice-id", "1")).invoke());

        assertTrue(error.getMessage().contains("HTTP 401"), error.getMessage());
        assertTrue(error.getMessage().contains("401 Authorization Required"), error.getMessage());
        assertFalse(error.getMessage().contains("not JSON"), error.getMessage());
        assertTrue(error.getMessage().length() < 400, "excerpt must be bounded");
    }

    @Test
    void eventStreamShouldYieldTheResponseMatchingTheRequestId() {
        String sse = """
                event: message
                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

                event: message
                data: {"jsonrpc":"2.0","id":9,"result":{"ok":true}}

                """;

        JsonNode message = McpStreamableHttpTransport.fromEventStream(sse, 9);

        assertTrue(message.path("result").path("ok").asBoolean());
    }

    @Test
    void eventStreamResponseShouldBeUnwrapped() throws Exception {
        StubClient client = new StubClient();
        client.answer = req -> {
            Response r = new Response(new Request());
            r.setStatus(Status.SUCCESS_OK);
            r.setEntity("data: " + rpcResult(req, "{\"structuredContent\":{\"amount\":"
                    + "{\"total\":5}}}") + "\n\n", MediaType.valueOf("text/event-stream"));
            return r;
        };

        ConsumedResult result = adapter(client, "")
                .prepare("get-invoice", Map.of("invoice-id", "1")).invoke();

        assertEquals(5, result.document().path("amount").path("total").asInt());
    }

    private static JsonNode upstreamTool(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void verifyShouldReportMissingToolUnknownArgumentAndUndeclaredRequiredArgument()
            throws Exception {
        McpClientAdapter adapter = adapter(new StubClient(), "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.verify(List.of(upstreamTool("""
                        {"name":"get-invoice","inputSchema":{"type":"object",
                         "properties":{"invoice-id":{"type":"string"},"tenant":{"type":"string"}},
                         "required":["invoice-id","tenant"]}}"""))));

        assertTrue(error.getMessage().contains("argument 'locale' unknown upstream"),
                error.getMessage());
        assertTrue(error.getMessage().contains("required upstream argument 'tenant'"),
                error.getMessage());
    }

    @Test
    void verifyShouldFailWhenToolIsAbsentUpstream() throws Exception {
        McpClientAdapter adapter = adapter(new StubClient(), "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.verify(List.of()));

        assertTrue(error.getMessage().contains("tool 'get-invoice' not found upstream"));
    }

    private static McpClientAdapter adapterWithSchema(String validateOutput) throws Exception {
        String yaml = """
                type: mcp
                namespace: billing
                endpoint: https://billing.example.com/mcp
                tools:
                  get-invoice:
                    description: Retrieve one invoice
                """;
        if (validateOutput != null) {
            yaml += "    validateOutput: " + validateOutput + "\n";
        }
        McpClientAdapter adapter = new McpClientAdapter(null,
                (McpClientSpec) YAML.readValue(yaml, ClientSpec.class), new StubClient());
        adapter.verify(List.of(upstreamTool("""
                {"name":"get-invoice","inputSchema":{"type":"object"},
                 "outputSchema":{"type":"object","properties":{"total":{"type":"number"}},
                 "required":["total"]}}""")));
        return adapter;
    }

    @Test
    void invalidStructuredContentShouldFailByDefault() throws Exception {
        McpClientAdapter adapter = adapterWithSchema(null);
        ObjectNode bad = JSON.createObjectNode().put("total", "not-a-number");

        McpClientException error = assertThrows(McpClientException.class,
                () -> adapter.validate("get-invoice", bad));

        assertTrue(error.getMessage().contains("violating its outputSchema"));
    }

    @Test
    void invalidStructuredContentShouldPassWhenValidationIsWarnOrOff() throws Exception {
        ObjectNode bad = JSON.createObjectNode().put("total", "not-a-number");

        adapterWithSchema("warn").validate("get-invoice", bad);
        adapterWithSchema("off").validate("get-invoice", bad);
    }

    @Test
    void validStructuredContentShouldPass() throws Exception {
        adapterWithSchema(null).validate("get-invoice",
                JSON.createObjectNode().put("total", 1.5));
    }

    @Test
    void missingEndpointShouldBeRejected() {
        McpClientSpec spec = new McpClientSpec("billing", null);

        assertThrows(IllegalArgumentException.class,
                () -> new McpClientAdapter(null, spec, new StubClient()));
    }
}
