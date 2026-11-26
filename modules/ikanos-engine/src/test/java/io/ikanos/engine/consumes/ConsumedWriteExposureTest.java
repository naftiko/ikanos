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
package io.ikanos.engine.consumes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.Aggregate;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.consumes.contract.ContractClientAdapter;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.spec.util.VersionHelper;

class ConsumedWriteExposureTest {

    @Test
    void consumedWriteShouldAskTheAdapterOfTheNamespace() {
        ContractClientAdapter orders = mock(ContractClientAdapter.class);
        when(orders.getNamespace()).thenReturn("orders");
        when(orders.isWriteOperation("cancel-order")).thenReturn(true);

        assertTrue(ConsumedWriteExposure.consumedWrite(List.of(orders), "orders.cancel-order"));
        assertFalse(ConsumedWriteExposure.consumedWrite(List.of(orders), "orders.get-order"));
        assertFalse(ConsumedWriteExposure.consumedWrite(List.of(orders), "other.cancel-order"));
        assertFalse(ConsumedWriteExposure.consumedWrite(List.of(orders), "no-dot"));
    }

    @Test
    void consumedWriteShouldAskAnAdapterOfAnyType() {
        ClientAdapter sql = mock(ClientAdapter.class);
        when(sql.getNamespace()).thenReturn("erp");
        when(sql.isWriteOperation("save")).thenReturn(true);

        assertTrue(ConsumedWriteExposure.consumedWrite(List.of(sql), "erp.save"),
                "a SQL write procedure is a write too");
        assertFalse(ConsumedWriteExposure.consumedWrite(List.of(sql), "erp.read"));
    }

    @Test
    void writeReachedShouldFindAWriteInASingletonCall() {
        ServerCallSpec call = new ServerCallSpec();
        call.setOperation("orders.cancel-order");

        assertEquals("orders.cancel-order", ConsumedWriteExposure.writeReached(
                mock(Capability.class), null, call, null, "orders.cancel-order"::equals));
    }

    @Test
    void writeReachedShouldFindAWriteInASteps() {
        OperationStepCallSpec step = new OperationStepCallSpec();
        step.setCall("orders.cancel-order");
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("cancel", step);

        assertEquals("orders.cancel-order", ConsumedWriteExposure.writeReached(
                mock(Capability.class), null, null, steps, "orders.cancel-order"::equals));
    }

    @Test
    void writeReachedShouldReturnNullWithoutWrite() {
        ServerCallSpec call = new ServerCallSpec();
        call.setOperation("orders.get-order");
        Capability capability = mock(Capability.class);
        when(capability.lookupFlow("orders.get-order"))
                .thenThrow(new IllegalArgumentException("no flow"));

        assertNull(ConsumedWriteExposure.writeReached(capability, null, call, null,
                target -> false));
    }

    // ── validate ──

    private static final String FLOWS = """
              aggregates:
                - namespace: billing
                  flows:
                    settle:
                      description: Settles an order.
                      %s
                      steps:
                        cancel:
                          type: call
                          call: orders.cancel-order
            """;

    /** The capability section of a spec with the given flow semantics and exposes block. */
    private static CapabilitySpec spec(String semantics, String exposes) throws Exception {
        String yaml = "ikanos: \"" + VersionHelper.getSchemaVersion() + "\"\n"
                + "capability:\n"
                + FLOWS.formatted(semantics)
                + "  exposes:\n" + exposes.indent(4);
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class).getCapability();
    }

    /** A capability whose flows are those of the spec, and an orders adapter that writes. */
    private static void validate(CapabilitySpec spec) {
        List<Aggregate> aggregates = new ArrayList<>();
        for (AggregateSpec aggregate : spec.getAggregates().values()) {
            aggregates.add(new Aggregate(aggregate, null));
        }
        Capability capability = mock(Capability.class);
        when(capability.getAggregates()).thenReturn(aggregates);
        when(capability.lookupFlow(anyString())).thenAnswer(invocation -> {
            String ref = invocation.getArgument(0);
            for (Aggregate aggregate : aggregates) {
                for (AggregateFlow flow : aggregate.getFlows()) {
                    if (flow.getRef().equals(ref)) {
                        return flow;
                    }
                }
            }
            throw new IllegalArgumentException("Unknown aggregate flow ref: '" + ref + "'");
        });
        ContractClientAdapter orders = mock(ContractClientAdapter.class);
        when(orders.getNamespace()).thenReturn("orders");
        when(orders.isWriteOperation("cancel-order")).thenReturn(true);
        ConsumedWriteExposure.validate(spec, capability, List.of(orders));
    }

    private static String mcp(String serverHints, String tool) {
        return """
                - type: mcp
                  port: 0
                  namespace: shop
                %s
                  tools:
                %s
                """.formatted(serverHints.indent(2).stripTrailing(),
                tool.indent(4).stripTrailing());
    }

    @Test
    void validateShouldRefuseAToolDeclaringReadOnlyThatReachesAWrite() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validate(spec("", mcp("", """
                        cancel:
                          description: Cancels an order.
                          call: orders.cancel-order
                          hints: { readOnly: true }
                        """))));

        assertTrue(e.getMessage().contains("MCP tool 'cancel'")
                && e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }

    @Test
    void validateShouldRefuseAToolInheritingReadOnlyThatReachesAWriteThroughAFlow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validate(spec("", mcp("hints: { readOnly: true }", """
                        settle:
                          description: Settles an order.
                          ref: billing.settle
                        """))));

        assertTrue(e.getMessage().contains("MCP tool 'settle'")
                && e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }

    @Test
    void validateShouldAcceptAToolThatIsNotReadOnly() {
        assertDoesNotThrow(() -> validate(spec("", mcp("hints: { readOnly: true }", """
                cancel:
                  description: Cancels an order.
                  call: orders.cancel-order
                  hints: { readOnly: false }
                """))));
    }

    @Test
    void validateShouldRefuseAFlowDeclaredSafeThatReachesAWrite() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validate(spec("semantics: { safe: true }", mcp("", """
                        settle:
                          description: Settles an order.
                          ref: billing.settle
                          hints: { readOnly: false }
                        """))));

        assertTrue(e.getMessage().contains("billing.settle")
                && e.getMessage().contains("semantics.safe")
                && e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }

    @Test
    void validateShouldAcceptAFlowNotDeclaredSafe() {
        assertDoesNotThrow(() -> validate(spec("semantics: { safe: false }", mcp("", """
                settle:
                  description: Settles an order.
                  ref: billing.settle
                """))));
    }

    private static String mcpResource(String target) {
        return """
                - type: mcp
                  port: 0
                  namespace: shop
                  resources:
                    order:
                      name: order
                      uri: shop://order
                      description: The current order.
                      %s
                """.formatted(target);
    }

    @Test
    void validateShouldRefuseAResourceThatReachesAWrite() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validate(spec("", mcpResource("call: orders.cancel-order"))));

        assertTrue(e.getMessage().contains("MCP resource 'order'")
                && e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }

    @Test
    void validateShouldAcceptAResourceThatOnlyReads() {
        assertDoesNotThrow(() -> validate(spec("", mcpResource("call: orders.get-order"))));
    }

    private static String rest(String method, String target) {
        return """
                - type: rest
                  address: localhost
                  port: 0
                  namespace: shop-rest
                  resources:
                    orders:
                      path: /orders
                      operations:
                        settle:
                          method: %s
                          %s
                """.formatted(method, target);
    }

    @Test
    void validateShouldRefuseASafeRestOperationThatReachesAWrite() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validate(spec("", rest("GET", "call: orders.cancel-order"))));

        assertTrue(e.getMessage().contains("REST operation 'settle'")
                && e.getMessage().contains("GET /orders")
                && e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }

    @Test
    void validateShouldRefuseASafeRestOperationThatReachesAWriteThroughAFlow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validate(spec("", rest("HEAD", "ref: billing.settle"))));

        assertTrue(e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }

    @Test
    void validateShouldAcceptAnUnsafeRestOperationThatReachesAWrite() {
        assertDoesNotThrow(() -> validate(spec("", rest("POST", "call: orders.cancel-order"))));
    }
}
