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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * The memo's Customer 360 case through a whole capability: profile, tickets and billing read at
 * the same time, then merged; one of the tracks is a sequence of two calls.
 */
class ParallelTracksAcceptanceTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static String capabilityYaml(int billingStatus) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: fake
                      namespace: crm
                      operations:
                        get-profile:
                          outputParameters: []
                        list-tickets:
                          outputParameters: []
                        get-account:
                          outputParameters: []
                        get-invoices:
                          outputParameters: []
                      responses:
                        get-profile: '{"id":"{{customerId}}","name":"Ada"}'
                        list-tickets: '[{"id":"t-1"},{"id":"t-2"}]'
                        get-account: '{"account":"a-9"}'
                        get-invoices: '{"open":3}'
                      statuses:
                        get-invoices: %d
                  aggregates:
                    - namespace: customers
                      flows:
                        overview:
                          description: Everything about a customer
                          inputParameters:
                            customer-id:
                              type: string
                              description: Customer id
                          steps:
                            - name: customer
                              type: parallel
                              tracks:
                                - name: profile
                                  type: call
                                  what: crm.get-profile
                                  with:
                                    customerId: customers.customer-id
                                - name: tickets
                                  type: call
                                  what: crm.list-tickets
                                - name: billing
                                  type: sequence
                                  steps:
                                    - name: account
                                      type: call
                                      what: crm.get-account
                                    - name: invoices
                                      type: call
                                      what: crm.get-invoices
                          mappings:
                            - target: name
                              value: $.profile.name
                            - target: tickets
                              value: $.tickets
                            - target: open
                              value: $.billing.open
                          outputParameters:
                            name:
                              type: string
                            tickets:
                              type: array
                            open:
                              type: number
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: customers-mcp
                      description: Customer tools
                      tools:
                        overview:
                          description: Everything about a customer
                          ref: customers.overview
                """.formatted(IKANOS, billingStatus);
    }

    private static McpSchema.CallToolResult overview(int billingStatus) throws Exception {
        Capability capability = new Capability(IkanosMappers.lenientYaml().readValue(
                capabilityYaml(billingStatus), IkanosSpec.class));
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);
        return mcp.getToolHandler().handleToolCall("overview", Map.of("customer-id", "c-7"));
    }

    @BeforeEach
    void setUp() {
        FakeClientAdapter.CALLS.clear();
    }

    @Test
    void aParallelStepShouldRunEveryTrackAndLetLaterMappingsReadEachByName() throws Exception {
        McpSchema.CallToolResult result = overview(200);

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(4, FakeClientAdapter.CALLS.size());
        JsonNode payload = IkanosMappers.json().readTree(
                ((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals("Ada", payload.path("name").asString());
        assertEquals(2, payload.path("tickets").size());
        assertEquals(3, payload.path("open").asInt(), "a sequence track gives its last output");
    }

    @Test
    void aFailingTrackShouldFailTheParallelStep() throws Exception {
        McpSchema.CallToolResult result = overview(500);

        assertTrue(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
    }

    /**
     * An MCP tool whose last step is a parallel step and that has no mappings answers the
     * group's output, {@code {track: output}}, rather than failing with no response.
     */
    @Test
    void aToolEndingOnAParallelStepWithoutMappingsShouldAnswerTheGroupOutput() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: fake
                      namespace: crm
                      operations:
                        get-profile:
                          outputParameters: []
                        list-tickets:
                          outputParameters: []
                      responses:
                        get-profile: '{"name":"Ada"}'
                        list-tickets: '[{"id":"t-1"}]'
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: customers-mcp
                      description: Customer tools
                      tools:
                        overview:
                          description: Everything about a customer
                          steps:
                            - name: customer
                              type: parallel
                              tracks:
                                - name: profile
                                  type: call
                                  what: crm.get-profile
                                - name: tickets
                                  type: call
                                  what: crm.list-tickets
                """.formatted(IKANOS);
        Capability capability = new Capability(
                IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpSchema.CallToolResult result =
                mcp.getToolHandler().handleToolCall("overview", Map.of());

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        JsonNode payload = IkanosMappers.json().readTree(
                ((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals("Ada", payload.path("profile").path("name").asString(), payload.toString());
        assertEquals(1, payload.path("tickets").size(), payload.toString());
    }
}
