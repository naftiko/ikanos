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
    void aCapabilityWithAGuardThatDoesNotParseShouldFailToLoad() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability("order.status =="));

        assertTrue(error.getMessage().contains("step 'reminder' in flow 'billing.remind'"),
                error.getMessage());
    }
}
