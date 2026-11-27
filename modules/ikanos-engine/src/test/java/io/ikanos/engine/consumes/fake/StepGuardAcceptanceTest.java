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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/**
 * A step's {@code onlyIf} guard through a whole capability: an MCP tool calls a flow whose last
 * step only runs for an overdue order.
 */
class StepGuardAcceptanceTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static String capabilityYaml(String onlyIf) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: fake
                      namespace: shop
                      operations:
                        get-order:
                          outputParameters: []
                        send-reminder:
                          outputParameters: []
                      responses:
                        get-order: '{"id":"{{orderId}}","status":"{{status}}"}'
                        send-reminder: '{"sent":true}'
                  aggregates:
                    - namespace: billing
                      flows:
                        remind:
                          description: Remind the customer of an overdue order
                          inputParameters:
                            order-id:
                              type: string
                              description: Order id
                            status:
                              type: string
                              description: Order status
                          steps:
                            - name: order
                              type: call
                              what: shop.get-order
                              with:
                                orderId: billing.order-id
                                status: billing.status
                            - name: reminder
                              type: call
                              what: shop.send-reminder
                              onlyIf: "%s"
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: billing-mcp
                      description: Billing tools
                      tools:
                        remind:
                          description: Remind the customer of an overdue order
                          ref: billing.remind
                """.formatted(IKANOS, onlyIf);
    }

    private static Capability capability(String onlyIf) throws Exception {
        return new Capability(IkanosMappers.lenientYaml().readValue(capabilityYaml(onlyIf),
                IkanosSpec.class));
    }

    @BeforeEach
    void setUp() {
        FakeClientAdapter.CALLS.clear();
    }

    private static McpSchema.CallToolResult remind(Capability capability, String status)
            throws Exception {
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);
        return mcp.getToolHandler().handleToolCall("remind",
                Map.of("order-id", "o-1", "status", status));
    }

    @Test
    void aGuardedStepShouldRunWhenItsGuardHolds() throws Exception {
        McpSchema.CallToolResult result =
                remind(capability("order.status == 'overdue'"), "overdue");

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(2, FakeClientAdapter.CALLS.size());
    }

    @Test
    void aGuardedStepShouldBeSkippedWhenItsGuardDoesNotHold() throws Exception {
        McpSchema.CallToolResult result =
                remind(capability("order.status == 'overdue'"), "paid");

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(1, FakeClientAdapter.CALLS.size(),
                "Only the order is read; no reminder is sent");
    }

    @Test
    void aGuardOverTheControlPortLimitShouldFailTheCall() throws Exception {
        String yaml = capabilityYaml("[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].exists(a,"
                + " [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].exists(b, a * b == 1000))") + """
                    - type: control
                      address: localhost
                      port: 0
                      management:
                        guards:
                          evaluationLimit: 50
                """;
        Capability capability = new Capability(
                IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> remind(capability, "overdue"));

        assertTrue(error.getMessage().startsWith("The onlyIf of step 'reminder' evaluated more"
                + " than 50 sub-expressions"), error.getMessage());
        assertEquals(1, FakeClientAdapter.CALLS.size(), "the reminder is not sent");
    }

    /**
     * A flow whose {@code reminder} step only runs for an overdue order, followed by a step
     * guarded on it, a step whose {@code with} reads it, and a mapping that reads it.
     */
    private static final String READS_A_SKIPPED_STEP = """
            ikanos: "%s"
            capability:
              consumes:
                - type: fake
                  namespace: shop
                  operations:
                    get-order:
                      outputParameters: []
                    send-reminder:
                      outputParameters: []
                    escalate:
                      outputParameters: []
                    note:
                      outputParameters: []
                  responses:
                    get-order: '{"status":"{{status}}"}'
                    send-reminder: '{"sent":true}'
                    escalate: '{"escalated":true}'
                    note: '{"seen":"{{sent}}"}'
              aggregates:
                - namespace: billing
                  flows:
                    remind:
                      description: Remind the customer of an overdue order
                      inputParameters:
                        status:
                          type: string
                          description: Order status
                      steps:
                        - name: order
                          type: call
                          what: shop.get-order
                          with:
                            status: billing.status
                        - name: reminder
                          type: call
                          what: shop.send-reminder
                          onlyIf: "order.status == 'overdue'"
                        - name: escalation
                          type: call
                          what: shop.escalate
                          onlyIf: reminder.sent
                        - name: note
                          type: call
                          what: shop.note
                          with:
                            sent: "{{reminder.sent}}"
                      mappings:
                        - target: sent
                          value: $.reminder.sent
                        - target: seen
                          value: $.note.seen
                      outputParameters:
                        sent:
                          type: boolean
                        seen:
                          type: string
              exposes:
                - type: mcp
                  address: localhost
                  port: 0
                  namespace: billing-mcp
                  description: Billing tools
                  tools:
                    remind:
                      description: Remind the customer of an overdue order
                      ref: billing.remind
            """;

    private static tools.jackson.databind.JsonNode remindReadingASkippedStep(String status)
            throws Exception {
        Capability capability = new Capability(IkanosMappers.lenientYaml().readValue(
                READS_A_SKIPPED_STEP.formatted(IKANOS), IkanosSpec.class));
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpSchema.CallToolResult result =
                mcp.getToolHandler().handleToolCall("remind", Map.of("status", status));
        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        return IkanosMappers.json().readTree(
                ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void aGuardOnASkippedStepShouldNotHold() throws Exception {
        remindReadingASkippedStep("paid");

        assertEquals(2, FakeClientAdapter.CALLS.size(),
                "the order and the note: no reminder, no escalation");
    }

    @Test
    void aWithTemplateAndAMappingShouldSeeASkippedStepAsAbsent() throws Exception {
        tools.jackson.databind.JsonNode payload = remindReadingASkippedStep("paid");

        assertTrue(payload.path("sent").isMissingNode() || payload.path("sent").isNull(),
                payload.toString());
        assertEquals("", payload.path("seen").asString(), payload.toString());
    }

    @Test
    void laterStepsShouldReadAStepWhoseGuardHeld() throws Exception {
        tools.jackson.databind.JsonNode payload = remindReadingASkippedStep("overdue");

        assertTrue(payload.path("sent").asBoolean(), payload.toString());
        assertEquals("true", payload.path("seen").asString(), payload.toString());
        assertEquals(4, FakeClientAdapter.CALLS.size(),
                "the order, the reminder, the escalation and the note");
    }

    @Test
    void aCapabilityWithAGuardThatDoesNotParseShouldFailToLoad() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability("order.status =="));

        assertTrue(error.getMessage().contains("step 'reminder' in flow 'billing.remind'"),
                error.getMessage());
    }
}
