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
package io.ikanos.engine.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.fake.FakeClientAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Aggregate flows calling other aggregate flows with {@code type: call} (#794).
 *
 * <p>Every capability here consumes only the test {@code type: fake} adapter, which returns
 * canned bodies in-process, so no test opens a network connection.</p>
 */
class FlowCompositionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @BeforeEach
    void setUp() {
        FakeClientAdapter.CALLS.clear();
    }

    private static Capability capability(String capabilityBody) throws Exception {
        String yaml = "ikanos: \"%s\"\ncapability:\n%s".formatted(IKANOS, capabilityBody);
        return new Capability(YAML.readValue(yaml, IkanosSpec.class));
    }

    /** A fake consumed adapter with a charge operation and an echo operation. */
    private static String cannedConsumes(int chargeStatus) {
        return """
                  consumes:
                    - type: fake
                      namespace: canned
                      operations:
                        charge:
                          outputParameters: []
                        echo:
                          outputParameters: []
                        files.read:
                          outputParameters: []
                      responses:
                        charge: '{"id":"pi-{{amount}}","status":"ok"}'
                        echo: '{"value":"{{value}}"}'
                        files.read: '{"content":"hello"}'
                      statuses:
                        charge: %d
                """.formatted(chargeStatus);
    }

    private static final String MCP_EXPOSES = """
              exposes:
                - type: mcp
                  address: localhost
                  port: 0
                  namespace: shop-mcp
                  tools:
                    checkout:
                      description: Vendor-neutral checkout
                      ref: payments.checkout
            """;

    /** A vendor shim flow (stripe.charge) and a vendor-neutral flow calling it. */
    private static String shimAggregates() {
        return """
                  aggregates:
                    - namespace: stripe
                      flows:
                        charge:
                          description: Charge with the vendor
                          inputParameters:
                            amount:
                              type: number
                              description: Amount
                          steps:
                            charge-card:
                              type: call
                              call: canned.charge
                              with:
                                amount: stripe.amount
                          mappings:
                            - target: id
                              value: $.charge-card.id
                          outputParameters:
                            id:
                              type: string
                    - namespace: payments
                      flows:
                        checkout:
                          description: Vendor-neutral checkout
                          inputParameters:
                            amount:
                              type: number
                              description: Amount
                          steps:
                            charge:
                              type: call
                              call: stripe.charge
                              with:
                                amount: payments.amount
                          mappings:
                            - target: payment-id
                              value: $.charge.id
                          outputParameters:
                            payment-id:
                              type: string
                """;
    }

    @Test
    void callStepShouldInvokeAnotherFlowAndExposeItsOutput() throws Exception {
        Capability capability = capability(cannedConsumes(200) + shimAggregates() + MCP_EXPOSES);

        FlowResult result = capability.lookupFlow("payments.checkout")
                .execute(Map.of("amount", 42));

        assertEquals("pi-42", JSON.readTree(result.mappedOutput).path("payment-id").asText());
        assertEquals(42, FakeClientAdapter.CALLS.get(0).get("amount"));
    }

    @Test
    void mcpToolRefShouldReturnTheOutputOfComposedFlows() throws Exception {
        Capability capability = capability(cannedConsumes(200) + shimAggregates() + MCP_EXPOSES);
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpSchema.CallToolResult result =
                mcp.getToolHandler().handleToolCall("checkout", Map.of("amount", 7));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertNotNull(structured);
        assertEquals("pi-7", structured.get("payment-id"));
    }

    @Test
    void flowLevelCallShouldTargetAnotherFlow() throws Exception {
        String aggregates = shimAggregates() + """
                    - namespace: alias
                      flows:
                        pay:
                          description: Pure alias of the vendor shim
                          inputParameters:
                            amount:
                              type: number
                              description: Amount
                          call: stripe.charge
                          outputParameters:
                            - type: object
                              properties:
                                reference:
                                  type: string
                                  mapping: $.id
                """;
        Capability capability = capability(cannedConsumes(200) + aggregates + MCP_EXPOSES);

        FlowResult result = capability.lookupFlow("alias.pay").execute(Map.of("amount", 3));

        assertEquals("pi-3", JSON.readTree(result.mappedOutput).path("reference").asText());
    }

    @Test
    void calledMockFlowShouldFeedItsMockOutputToTheCaller() throws Exception {
        String aggregates = """
                  aggregates:
                    - namespace: fixed
                      flows:
                        greeting:
                          description: Mock greeting
                          inputParameters:
                            name:
                              type: string
                              description: Name
                          outputParameters:
                            message:
                              type: string
                              value: Hello, {{name}}!
                    - namespace: payments
                      flows:
                        checkout:
                          description: Uses the mock
                          inputParameters:
                            name:
                              type: string
                              description: Name
                          steps:
                            greet:
                              type: call
                              call: fixed.greeting
                          mappings:
                            - target: message
                              value: $.greet.message
                          outputParameters:
                            message:
                              type: string
                """;
        Capability capability = capability(cannedConsumes(200) + aggregates + MCP_EXPOSES);

        FlowResult result = capability.lookupFlow("payments.checkout")
                .execute(Map.of("name", "Ada"));

        assertEquals("Hello, Ada!", JSON.readTree(result.mappedOutput).path("message").asText());
    }

    @Test
    void callTargetShouldSplitOnTheFirstDotSoToolNamesMayContainDots() throws Exception {
        String aggregates = """
                  aggregates:
                    - namespace: payments
                      flows:
                        checkout:
                          description: Reads a dotted tool name
                          steps:
                            read:
                              type: call
                              call: canned.files.read
                          mappings:
                            - target: content
                              value: $.read.content
                          outputParameters:
                            content:
                              type: string
                """;
        Capability capability = capability(cannedConsumes(200) + aggregates + MCP_EXPOSES);

        FlowResult result = capability.lookupFlow("payments.checkout").execute(Map.of());

        assertEquals("hello", JSON.readTree(result.mappedOutput).path("content").asText());
    }

    @Test
    void failedStepInsideCalledFlowShouldFailTheCallerNamingTheChain() throws Exception {
        Capability capability = capability(cannedConsumes(402) + shimAggregates() + MCP_EXPOSES);
        AggregateFlow checkout = capability.lookupFlow("payments.checkout");

        OperationStepExecutor.StepFailedException error = assertThrows(
                OperationStepExecutor.StepFailedException.class,
                () -> checkout.execute(Map.of("amount", 1)));

        assertEquals("Step 'charge' failed calling 'stripe.charge': "
                + "Step 'charge-card' failed with HTTP 402", error.getMessage());
        assertEquals(402, error.getStatusCode());
        assertEquals("charge", error.getStepName());
    }

    @Test
    void nonSuccessStatusFromCalledSingleCallFlowShouldFailTheCallingStep() throws Exception {
        String aggregates = """
                  aggregates:
                    - namespace: stripe
                      flows:
                        charge:
                          description: Single call, maps the upstream answer
                          call: canned.charge
                          outputParameters:
                            - type: object
                              properties:
                                id:
                                  type: string
                                  mapping: $.id
                    - namespace: payments
                      flows:
                        checkout:
                          description: Calls the single-call flow
                          steps:
                            charge:
                              type: call
                              call: stripe.charge
                          mappings:
                            - target: payment-id
                              value: $.charge.id
                          outputParameters:
                            payment-id:
                              type: string
                """;
        Capability capability = capability(cannedConsumes(500) + aggregates + MCP_EXPOSES);
        AggregateFlow checkout = capability.lookupFlow("payments.checkout");

        OperationStepExecutor.StepFailedException error = assertThrows(
                OperationStepExecutor.StepFailedException.class,
                () -> checkout.execute(Map.of()));

        assertEquals("Step 'charge' failed with HTTP 500", error.getMessage());
    }

    @Test
    void namespaceDeclaredInConsumesAndAggregatesShouldFailAtLoad() {
        String aggregates = """
                  aggregates:
                    - namespace: canned
                      flows:
                        charge:
                          description: Collides with the consumed namespace
                          outputParameters:
                            id:
                              type: string
                              value: x
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(cannedConsumes(200) + aggregates + MCP_EXPOSES));

        assertEquals("Namespace 'canned' is declared more than once (capability.consumes[0] "
                + "and capability.aggregates). Namespaces must be unique across consumes, "
                + "exposes, aggregates and binds.", error.getMessage());
    }

    @Test
    void namespaceDeclaredInConsumesAndExposesShouldFailAtLoad() {
        String exposes = MCP_EXPOSES.replace("namespace: shop-mcp", "namespace: canned");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(cannedConsumes(200) + shimAggregates() + exposes));

        assertTrue(error.getMessage().startsWith("Namespace 'canned' is declared more than once"),
                error.getMessage());
    }

    @Test
    void flowCallCycleShouldFailAtLoadNamingTheCycle() {
        String aggregates = """
                  aggregates:
                    - namespace: a
                      flows:
                        x:
                          description: Calls b.y
                          steps:
                            next:
                              type: call
                              call: b.y
                    - namespace: b
                      flows:
                        y:
                          description: Calls a.x back
                          call: a.x
                    - namespace: payments
                      flows:
                        checkout:
                          description: Unrelated
                          outputParameters:
                            id:
                              type: string
                              value: x
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(cannedConsumes(200) + aggregates + MCP_EXPOSES));

        assertEquals("Aggregate flows call each other in a cycle: a.x -> b.y -> a.x",
                error.getMessage());
    }

    @Test
    void callToUnknownFlowInKnownAggregateShouldFailAtLoad() {
        String aggregates = shimAggregates().replace("call: stripe.charge", "call: stripe.refund");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(cannedConsumes(200) + aggregates + MCP_EXPOSES));

        assertEquals("Flow 'payments.checkout' calls 'stripe.refund', but aggregate 'stripe' "
                + "has no flow 'refund'", error.getMessage());
    }

    @Test
    void exposedToolCallingAFlowShouldFailAtLoadSuggestingRef() {
        String exposes = MCP_EXPOSES.replace("ref: payments.checkout", "call: payments.checkout");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(cannedConsumes(200) + shimAggregates() + exposes));

        assertEquals("MCP tool 'checkout' calls aggregate flow 'payments.checkout' with 'call:'. "
                + "Use 'ref: payments.checkout' to expose an aggregate flow.", error.getMessage());
    }

    @Test
    void callDepthBeyondTheConfiguredLimitShouldFailNamingTheChain() throws Exception {
        String aggregates = """
                  aggregates:
                    - namespace: d1
                      flows:
                        f:
                          description: First
                          call: d2.f
                    - namespace: d2
                      flows:
                        f:
                          description: Second
                          call: d3.f
                    - namespace: d3
                      flows:
                        f:
                          description: Third
                          outputParameters:
                            id:
                              type: string
                              value: deep
                """;
        String exposes = MCP_EXPOSES + """
                    - type: control
                      address: localhost
                      port: 0
                      management:
                        flows:
                          maxCallDepth: 2
                """;
        Capability capability = capability(cannedConsumes(200) + aggregates
                + exposes.replace("ref: payments.checkout", "ref: d3.f"));
        AggregateFlow first = capability.lookupFlow("d1.f");

        FlowCallDepthExceededException error = assertThrows(
                FlowCallDepthExceededException.class, () -> first.execute(Map.of()));

        assertEquals("Flow call depth exceeds the limit of 2: d1.f -> d2.f -> d3.f",
                error.getMessage());
    }

    @Test
    void callChainShouldBeResetAfterADepthFailure() throws Exception {
        String aggregates = """
                  aggregates:
                    - namespace: d1
                      flows:
                        f:
                          description: First
                          call: d2.f
                    - namespace: d2
                      flows:
                        f:
                          description: Second
                          outputParameters:
                            id:
                              type: string
                              value: deep
                """;
        String exposes = MCP_EXPOSES.replace("ref: payments.checkout", "ref: d2.f") + """
                    - type: control
                      address: localhost
                      port: 0
                      management:
                        flows:
                          maxCallDepth: 1
                """;
        Capability capability = capability(cannedConsumes(200) + aggregates + exposes);
        assertThrows(FlowCallDepthExceededException.class,
                () -> capability.lookupFlow("d1.f").execute(Map.of()));

        FlowResult result = capability.lookupFlow("d2.f").execute(Map.of());

        assertEquals("deep", result.mockOutput.path("id").asText());
    }

    @Test
    void callDepthShouldDefaultToEight() throws Exception {
        Capability capability = capability(cannedConsumes(200) + shimAggregates() + MCP_EXPOSES);

        assertEquals(8, capability.lookupFlow("payments.checkout").getMaxCallDepth());
    }

    /**
     * Regression test for #320: one executor is shared by every aggregate flow. Each flow binds
     * {@code with:} values against its own namespace, including when flows of different
     * namespaces run concurrently and when one flow calls another.
     */
    @Test
    void concurrentAndNestedFlowsShouldBindParametersAgainstTheirOwnNamespace() throws Exception {
        String aggregates = """
                  aggregates:
                    - namespace: alpha
                      flows:
                        run:
                          description: Calls beta, then echoes its own input
                          inputParameters:
                            input:
                              type: string
                              description: Input
                          steps:
                            nested:
                              type: call
                              call: beta.run
                              with:
                                input: alpha.input
                            own:
                              type: call
                              call: canned.echo
                              with:
                                value: alpha.input
                          mappings:
                            - target: value
                              value: $.own.value
                          outputParameters:
                            value:
                              type: string
                    - namespace: beta
                      flows:
                        run:
                          description: Echoes its input
                          inputParameters:
                            input:
                              type: string
                              description: Input
                          steps:
                            own:
                              type: call
                              call: canned.echo
                              with:
                                value: beta.input
                          mappings:
                            - target: value
                              value: $.own.value
                          outputParameters:
                            value:
                              type: string
                """;
        Capability capability = capability(cannedConsumes(200) + aggregates
                + MCP_EXPOSES.replace("ref: payments.checkout", "ref: alpha.run"));
        AggregateFlow alpha = capability.lookupFlow("alpha.run");
        AggregateFlow beta = capability.lookupFlow("beta.run");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String[]>> results = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                AggregateFlow flow = i % 2 == 0 ? alpha : beta;
                String input = "in-" + i;
                Callable<String[]> task = () -> new String[] {input,
                        JSON.readTree(flow.execute(Map.of("input", input)).mappedOutput)
                                .path("value").asText()};
                results.add(pool.submit(task));
            }
            for (Future<String[]> result : results) {
                String[] pair = result.get();
                assertEquals(pair[0], pair[1]);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void flowInvocationShouldReportTheCalledFlowNamespace() throws Exception {
        Capability capability = capability(cannedConsumes(200) + shimAggregates() + MCP_EXPOSES);
        OperationStepExecutor executor = new OperationStepExecutor(capability);

        FlowInvocation invocation = assertInstanceOf(FlowInvocation.class,
                executor.findClientRequestFor("stripe", "charge", Map.of("amount", 5)));

        assertEquals("stripe", invocation.namespace());
        JsonNode document = invocation.invoke().document();
        assertEquals("pi-5", document.path("id").asText());
    }

    @Test
    void callToUnknownFlowAtRuntimeShouldNameTheAggregateAndFlow() throws Exception {
        Capability capability = capability(cannedConsumes(200) + shimAggregates() + MCP_EXPOSES);
        OperationStepExecutor executor = new OperationStepExecutor(capability);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("stripe", "refund", Map.of()));

        assertEquals("Aggregate namespace 'stripe' has no flow 'refund'", error.getMessage());
    }
}
