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
package io.ikanos.engine.consumes.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ResourceHandler;
import io.ikanos.engine.exposes.rest.ResourceRestlet;
import io.ikanos.engine.exposes.rest.RestServerAdapter;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Acceptance test for the consumed invocation carrier ({@code consumed-invocation-carrier.md} §9).
 *
 * <p>The capability below consumes only a {@code type: fake} adapter, registered through
 * {@code META-INF/services} in test resources. That adapter returns canned bodies and uses no
 * Restlet or HTTP type. Every exposure path the engine supports must work through it: a call step
 * in an aggregate flow, a later lookup step, a single-call MCP tool, an MCP tool {@code ref}, a
 * single-call REST operation, a dynamic MCP resource, and binary results on both MCP and REST.</p>
 */
class FakeAdapterAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Capability capability;

    @BeforeEach
    void setUp() throws Exception {
        FakeClientAdapter.CALLS.clear();
        capability = capabilityFromYaml(capabilityYaml());
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        return new Capability(YAML.readValue(yaml, IkanosSpec.class));
    }

    private static String capabilityYaml() {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: fake
                      namespace: canned
                      operations:
                        get-user:
                          outputParameters:
                            - name: user-id
                              type: string
                              mapping: $.id
                            - name: team
                              type: string
                              mapping: $.team
                        list-teams:
                          outputParameters: []
                        get-avatar:
                          outputRawFormat: binary
                        get-missing:
                          outputParameters: []
                      responses:
                        get-user: '{"id":"{{userId}}","name":"Alice","team":"t-2"}'
                        list-teams: '[{"id":"t-1","label":"Ops"},{"id":"t-2","label":"Core"}]'
                        get-avatar: 'PNGDATA'
                        get-missing: '{"error":"gone"}'
                      mediaTypes:
                        get-avatar: image/png
                      statuses:
                        get-missing: 404
                  aggregates:
                    - namespace: people
                      flows:
                        user-with-team:
                          description: User and the label of their team
                          inputParameters:
                            user-id:
                              type: string
                              description: User id
                          steps:
                            user:
                              type: call
                              call: canned.get-user
                              with:
                                userId: people.user-id
                            teams:
                              type: call
                              call: canned.list-teams
                            team:
                              type: lookup
                              index: teams
                              match: id
                              lookupValue: $.user.team
                              outputParameters:
                                - label
                          mappings:
                            - target: id
                              value: $.user.user-id
                            - target: team
                              value: $.team.label
                          outputParameters:
                            id:
                              type: string
                            team:
                              type: string
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: people-mcp
                      description: Fake-backed MCP surface
                      tools:
                        get-user:
                          description: Get a user
                          inputParameters:
                            user-id:
                              type: string
                              description: User id
                          call: canned.get-user
                          with:
                            userId: people-mcp.user-id
                          outputParameters:
                            - type: object
                              properties:
                                id:
                                  type: string
                                  mapping: $.id
                                name:
                                  type: string
                                  mapping: $.name
                        user-with-team:
                          description: User and team
                          ref: people.user-with-team
                        get-avatar:
                          description: Avatar bytes
                          call: canned.get-avatar
                        get-missing:
                          description: Always 404
                          call: canned.get-missing
                      resources:
                        user-card:
                          uri: people://users/{userId}
                          description: User card
                          mimeType: application/json
                          call: canned.get-user
                    - type: rest
                      address: localhost
                      port: 0
                      namespace: people-rest
                      resources:
                        - path: /users
                          name: users
                          operations:
                            - method: GET
                              name: get-user
                              call: canned.get-user
                              with:
                                userId: u-rest
                        - path: /avatar
                          name: avatar
                          operations:
                            - method: GET
                              name: get-avatar
                              call: canned.get-avatar
                        - path: /missing
                          name: missing
                          operations:
                            - method: GET
                              name: get-missing
                              call: canned.get-missing
                """.formatted(VersionHelper.getSchemaVersion());
    }

    private McpServerAdapter mcp() {
        return (McpServerAdapter) capability.getServerAdapters().get(0);
    }

    private Response restGet(String path) {
        RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(1);
        RestServerSpec spec = (RestServerSpec) adapter.getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, spec,
                spec.getResources().values().stream()
                        .filter(r -> path.equals(r.getPath())).findFirst().orElseThrow());
        Request request = new Request(Method.GET, "http://localhost" + path);
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }

    @Test
    void capabilityShouldInstantiateTheRegisteredFakeAdapter() {
        assertEquals(1, capability.getClientAdapters().size());
        assertInstanceOf(FakeClientAdapter.class, capability.getClientAdapters().get(0));
    }

    @Test
    void aggregateFlowShouldRunCallAndLookupStepsThroughFakeAdapter() throws Exception {
        McpSchema.CallToolResult result = mcp().getToolHandler()
                .handleToolCall("user-with-team", Map.of("user-id", "u-7"));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode payload = JSON.readTree(
                ((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals("u-7", payload.path("id").asText());
        assertEquals("Core", payload.path("team").asText());
        assertEquals("u-7", FakeClientAdapter.CALLS.get(0).get("userId"));
    }

    @Test
    void singleCallMcpToolShouldMapFakeResultAndAdvertiseStructuredContent() throws Exception {
        McpSchema.CallToolResult result = mcp().getToolHandler()
                .handleToolCall("get-user", Map.of("user-id", "u-1"));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertNotNull(structured);
        assertEquals("u-1", structured.get("id"));
        assertEquals("Alice", structured.get("name"));
    }

    @Test
    void mcpToolShouldReportErrorWhenFakeOutcomeIsNotSuccess() throws Exception {
        McpSchema.CallToolResult result = mcp().getToolHandler()
                .handleToolCall("get-missing", Map.of());

        assertTrue(Boolean.TRUE.equals(result.isError()));
    }

    @Test
    void mcpToolShouldReturnImageContentForBinaryFakeResult() throws Exception {
        McpSchema.CallToolResult result = mcp().getToolHandler()
                .handleToolCall("get-avatar", Map.of());

        McpSchema.ImageContent image =
                assertInstanceOf(McpSchema.ImageContent.class, result.content().get(0));
        assertEquals("image/png", image.mimeType());
        assertEquals(Base64.getEncoder().encodeToString("PNGDATA".getBytes()), image.data());
    }

    @Test
    void dynamicMcpResourceShouldReadThroughFakeAdapter() throws Exception {
        List<ResourceHandler.ResourceContent> contents =
                mcp().getResourceHandler().read("people://users/u-9");

        assertEquals(1, contents.size());
        assertEquals("u-9", JSON.readTree(contents.get(0).text).path("id").asText());
    }

    @Test
    void restOperationShouldForwardUnmappedFakeBodyAndStatus() throws Exception {
        Response response = restGet("/users");

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("u-rest", JSON.readTree(response.getEntity().getText()).path("id").asText());
    }

    @Test
    void restOperationShouldCopyNonSuccessStatusFromFakeResult() {
        Response response = restGet("/missing");

        assertEquals(404, response.getStatus().getCode());
    }

    @Test
    void restOperationShouldReturnBinaryFakeBodyWithItsMediaType() throws Exception {
        Response response = restGet("/avatar");

        assertEquals("image/png", response.getEntity().getMediaType().getName());
        assertEquals("PNGDATA", response.getEntity().getText());
    }

    @Test
    void callToUnknownOperationInKnownNamespaceShouldNameBoth() {
        OperationStepExecutor executor = new OperationStepExecutor(capability);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("canned", "nope", Map.of()));

        assertEquals("Consumed namespace 'canned' has no operation 'nope'", error.getMessage());
    }

    @Test
    void unregisteredConsumesTypeShouldFailAtLoadNamingTheType() {
        String yaml = capabilityYaml().replace("- type: fake", "- type: carrier-pigeon");

        Exception error = assertThrows(Exception.class, () -> capabilityFromYaml(yaml));

        assertTrue(error.getMessage().contains("Unknown consumes type 'carrier-pigeon'"),
                "error should name the unknown type: " + error.getMessage());
    }
}
