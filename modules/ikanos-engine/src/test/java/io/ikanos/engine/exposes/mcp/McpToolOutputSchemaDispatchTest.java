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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * End-to-end tests for MCP tool {@code outputSchema} ({@code tools/list}) and
 * {@code structuredContent} ({@code tools/call}), driven through the {@link ProtocolDispatcher}
 * against an in-process upstream (#772).
 */
class McpToolOutputSchemaDispatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SHIP_JSON = """
            {
              "imo_number": "IMO-9321483",
              "vessel_name": "Northern Star",
              "gross_tonnage": 42000,
              "dimensions": { "length_overall": 229 },
              "crew": [ { "full_name": "Ada" }, { "full_name": "Grace" } ],
              "crewIds": [ "CREW-001", "CREW-003" ],
              "ports": { "NOOSL": { "name": "Oslo" }, "SEGOT": { "name": "Gothenburg" } }
            }
            """;

    private Component upstream;
    private int port;
    private String schemaVersion;

    /** Body served by the in-process upstream; a test may replace it before calling a tool. */
    private volatile String upstreamBody = SHIP_JSON;

    @BeforeEach
    void setUp() throws Exception {
        schemaVersion = VersionHelper.getSchemaVersion();
        port = findFreePort();
        upstream = createUpstream(port);
        upstream.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void toolsListShouldAdvertiseOutputSchemaForObjectOutput() throws Exception {
        JsonNode tool = findTool(toolsList(), "get-ship");

        JsonNode outputSchema = tool.path("outputSchema");
        assertEquals("object", outputSchema.path("type").asText());
        JsonNode properties = outputSchema.path("properties");
        assertEquals("string", properties.path("imo").path("type").get(0).asText());
        assertEquals("number", properties.path("tonnage").path("type").get(0).asText());
        assertEquals("number",
                properties.path("specs").path("properties").path("length").path("type").get(0)
                        .asText());
        assertEquals("array", properties.path("crew").path("type").get(0).asText());
    }

    @Test
    void toolsListShouldAdvertiseNestedShapesAndRequiredKeys() throws Exception {
        JsonNode properties = findTool(toolsList(), "get-ship").path("outputSchema")
                .path("properties");

        assertEquals("string", properties.path("crew").path("items").path("properties")
                .path("name").path("type").get(0).asText());
        assertEquals("string",
                properties.path("crewIds").path("items").path("type").get(0).asText());
        assertEquals("string", properties.path("ports").path("additionalProperties")
                .path("type").get(0).asText());
        assertEquals("number", properties.path("dimensions").path("properties")
                .path("length").path("type").get(0).asText());
        assertTrue(findTool(toolsList(), "get-ship").path("outputSchema").path("required")
                .isArray());
    }

    @Test
    void toolsCallStructuredContentShouldConformToAdvertisedOutputSchema() throws Exception {
        for (String toolName : new String[] {"get-ship", "ship-name", "get-ship-ref"}) {
            JsonNode outputSchema = findTool(toolsList(), toolName).path("outputSchema");
            JsonNode structured = toolsCall(toolName).path("structuredContent");

            java.util.Set<ValidationMessage> errors = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(outputSchema).validate(structured);

            assertTrue(errors.isEmpty(),
                    "structuredContent of '" + toolName + "' violates its outputSchema: " + errors);
        }
    }

    @Test
    void toolsCallStructuredContentShouldConformWhenUpstreamOmitsMappedFields()
            throws Exception {
        upstreamBody = "{\"imo_number\": \"IMO-9321483\"}";
        JsonNode outputSchema = findTool(toolsList(), "get-ship").path("outputSchema");
        JsonNode structured = toolsCall("get-ship").path("structuredContent");

        java.util.Set<ValidationMessage> errors = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(outputSchema).validate(structured);

        assertTrue(errors.isEmpty(), "sparse upstream must still conform: " + errors);
        assertTrue(structured.path("crew").isNull());
        assertTrue(structured.path("dimensions").isNull());
    }

    @Test
    void toolsListShouldOmitOutputSchemaForArrayOutput() throws Exception {
        JsonNode tool = findTool(toolsList(), "list-crew");

        assertTrue(tool.path("outputSchema").isMissingNode(),
                "MCP requires an object outputSchema; an array root must not advertise one");
    }

    @Test
    void toolsListShouldOmitOutputSchemaForMockTool() throws Exception {
        JsonNode tool = findTool(toolsList(), "hello");

        assertTrue(tool.path("outputSchema").isMissingNode());
    }

    @Test
    void toolsCallShouldReturnStructuredContentMatchingTextContent() throws Exception {
        JsonNode result = toolsCall("get-ship");

        assertFalse(result.path("isError").asBoolean());
        JsonNode structured = result.path("structuredContent");
        assertEquals("IMO-9321483", structured.path("imo").asText());
        assertEquals(42000, structured.path("tonnage").asInt());
        assertEquals(229, structured.path("specs").path("length").asInt());
        assertEquals(2, structured.path("crew").size());
        assertEquals("Ada", structured.path("crew").get(0).path("name").asText());
        assertTrue(structured.path("crew").get(0).path("full_name").isMissingNode(),
                "nested array items must be shaped, not passed through raw");
        assertEquals("Oslo", structured.path("ports").path("NOOSL").asText());
        assertEquals(229, structured.path("dimensions").path("length").asInt());

        JsonNode text = JSON.readTree(result.path("content").get(0).path("text").asText());
        assertEquals(structured, text,
                "the text block must carry the same JSON for clients without structuredContent");
    }

    @Test
    void toolsCallShouldNotReturnStructuredContentForArrayOutput() throws Exception {
        JsonNode result = toolsCall("list-crew");

        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.path("structuredContent").isMissingNode());
        assertEquals("text", result.path("content").get(0).path("type").asText());
    }

    @Test
    void toolsCallShouldNotReturnStructuredContentForMockTool() throws Exception {
        JsonNode result = toolsCall("hello");

        assertTrue(result.path("structuredContent").isMissingNode());
    }

    @Test
    void toolsListShouldAdvertiseOutputSchemaForOrchestratedTool() throws Exception {
        JsonNode outputSchema = findTool(toolsList(), "ship-name").path("outputSchema");

        assertEquals("object", outputSchema.path("type").asText());
        assertEquals("string",
                outputSchema.path("properties").path("name").path("type").get(0).asText());
    }

    @Test
    void toolsCallShouldReturnStructuredContentForOrchestratedTool() throws Exception {
        JsonNode result = toolsCall("ship-name");

        assertFalse(result.path("isError").asBoolean());
        assertEquals("Northern Star", result.path("structuredContent").path("name").asText());
    }

    @Test
    void toolsListShouldAdvertiseOutputSchemaInheritedFromAggregateFlow() throws Exception {
        JsonNode outputSchema = findTool(toolsList(), "get-ship-ref").path("outputSchema");

        assertEquals("object", outputSchema.path("type").asText());
        assertEquals("string",
                outputSchema.path("properties").path("imo").path("type").get(0).asText());
    }

    @Test
    void toolsCallShouldReturnStructuredContentForAggregateRefTool() throws Exception {
        JsonNode result = toolsCall("get-ship-ref");

        assertFalse(result.path("isError").asBoolean());
        assertEquals("IMO-9321483", result.path("structuredContent").path("imo").asText());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private JsonNode toolsList() throws Exception {
        return dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
                .path("result").path("tools");
    }

    private JsonNode toolsCall(String toolName) throws Exception {
        return dispatch("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"" + toolName + "\",\"arguments\":{\"imo\":\"IMO-9321483\"}}}")
                .path("result");
    }

    private JsonNode dispatch(String requestJson) throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree(requestJson);
        ((ObjectNode) request.get("params")).putObject("_meta").put(
                "io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return dispatcher().dispatch(request).responseBody();
    }

    private ProtocolDispatcher dispatcher() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = yaml.readValue(capabilityYaml(), IkanosSpec.class);
        Capability capability = new Capability(spec);
        return new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));
    }

    private static JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("tool not found in tools/list: " + name);
    }

    private String capabilityYaml() {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: registry
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      ship:
                        path: /ships/{{imo_number}}
                        operations:
                          get-ship:
                            method: GET
                            inputParameters:
                              imo_number:
                                in: path
                  exposes:
                  - type: mcp
                    transport: http
                    port: 0
                    namespace: ships
                    tools:
                      get-ship:
                        description: Get ship details
                        inputParameters:
                          imo:
                            type: string
                            required: true
                        call: registry.get-ship
                        with:
                          imo_number: "{{imo}}"
                        outputParameters:
                        - type: object
                          properties:
                            imo:
                              type: string
                              mapping: "$.imo_number"
                            tonnage:
                              type: number
                              mapping: "$.gross_tonnage"
                            specs:
                              type: object
                              properties:
                                length:
                                  type: number
                                  mapping: "$.dimensions.length_overall"
                            crew:
                              type: array
                              mapping: "$.crew"
                              items:
                                type: object
                                properties:
                                  name:
                                    type: string
                                    mapping: "$.full_name"
                            crewIds:
                              type: array
                              mapping: "$.crewIds"
                              items:
                                type: string
                                mapping: "$."
                            ports:
                              type: object
                              mapping: "$.ports"
                              values:
                                type: string
                                mapping: "$.name"
                            dimensions:
                              type: object
                              mapping: "$.dimensions"
                              properties:
                                length:
                                  type: number
                                  mapping: "$.length_overall"
                      list-crew:
                        description: List crew members
                        inputParameters:
                          imo:
                            type: string
                            required: true
                        call: registry.get-ship
                        with:
                          imo_number: "{{imo}}"
                        outputParameters:
                        - type: array
                          mapping: "$.crew"
                          items:
                            type: object
                            properties:
                              name:
                                type: string
                                mapping: "$.full_name"
                      hello:
                        description: Say hello
                        outputParameters:
                          greeting:
                            type: string
                            value: "Hello"
                      ship-name:
                        description: Get the ship name via an orchestrated step
                        inputParameters:
                          imo:
                            type: string
                            required: true
                        steps:
                          fetch-ship:
                            type: call
                            call: registry.get-ship
                            with:
                              imo_number: ships.imo
                        mappings:
                        - target: name
                          value: $.fetch-ship.vessel_name
                        outputParameters:
                          name:
                            type: string
                      get-ship-ref:
                        description: Get ship details through an aggregate flow
                        ref: fleet.get-ship
                  aggregates:
                  - namespace: fleet
                    flows:
                      get-ship:
                        description: Get ship details.
                        inputParameters:
                          imo:
                            type: string
                        call: registry.get-ship
                        with:
                          imo_number: fleet.imo
                        outputParameters:
                        - type: object
                          properties:
                            imo:
                              type: string
                              mapping: "$.imo_number"
                """.formatted(schemaVersion, port);
    }

    private Component createUpstream(int port) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/ships/{imo}", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(
                                new StringRepresentation(upstreamBody, MediaType.APPLICATION_JSON));
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
