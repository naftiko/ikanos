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
package io.ikanos.client.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import com.google.protobuf.DynamicMessage;
import tools.jackson.databind.JsonNode;
import io.ikanos.engine.consumes.ClientAdapterRegistry;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.contract.ContractResult;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcClientSpec;
import io.ikanos.spec.util.IkanosMappers;

/** The gRPC consumed adapter against an in-process grpc-java server. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GrpcConsumedAdapterTest {

    @TempDir
    static Path dir;

    private static OrdersFixture fixture;
    private static int port;
    private static Path descriptorSet;

    @BeforeAll
    static void startUpstream() throws Exception {
        fixture = new OrdersFixture();
        descriptorSet = fixture.writeDescriptorSet(dir);
        port = fixture.start();
    }

    @AfterAll
    static void stopUpstream() throws Exception {
        fixture.stop();
    }

    private static final String PROCEDURES = """
            get-order:
              description: One order.
              rpc: GetOrder
              inputParameters:
                order-id: { type: string, required: true, description: Order id., wireName: id }
            cancel-order:
              description: Cancel an order.
              rpc: CancelOrder
              inputParameters:
                order-id: { type: string, required: true, description: Order id., wireName: id }
                reason: { type: string, description: Why. }
                refund: { type: integer, description: Refund in cents., wireName: refund.amountCents }
            """;

    private static GrpcClientSpec spec(String endpoint, String procedures, String extra)
            throws Exception {
        String yaml = """
                type: grpc
                namespace: orders
                endpoint: "%s"
                descriptorSet: "%s"
                service: test.orders.OrderService
                %s
                procedures:
                %s
                """.formatted(endpoint, descriptorSet.toString().replace('\\', '/'), extra,
                procedures.indent(2).stripTrailing());
        return (GrpcClientSpec) IkanosMappers.yaml().readValue(yaml, ClientSpec.class);
    }

    private static GrpcClientSpec spec() throws Exception {
        return spec("http://localhost:" + port, PROCEDURES, "");
    }

    private static GrpcConsumedAdapter started(GrpcClientSpec spec) throws Exception {
        GrpcConsumedAdapter adapter = new GrpcConsumedAdapter(null, spec, Map.of());
        adapter.start();
        return adapter;
    }

    private static ConsumedResult call(GrpcConsumedAdapter adapter, String procedure,
            Map<String, Object> values) throws Exception {
        return adapter.prepare(procedure, new HashMap<>(values)).invoke();
    }

    @Test
    void prepareShouldCallTheMethodAndReturnTheProto3JsonOfTheResponse() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "42"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            JsonNode order = result.document();
            assertEquals("c-42", order.get("customerId").asString(), "json_name keys");
            assertEquals("1250", order.at("/total/amountCents").asString(), "int64 as text");
            assertEquals(0, order.get("priority").asInt(), "fields without presence printed");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void prepareShouldLeaveOutAnAbsentOptionalInputAndFillNestedFields() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        try {
            ConsumedResult result = call(adapter, "cancel-order",
                    Map.of("order-id", "7", "refund", "300"));

            assertTrue(result.document().get("cancelled").asBoolean());
            DynamicMessage request = fixture.lastCancel.get();
            assertEquals("7", request.getField(
                    request.getDescriptorForType().findFieldByName("id")));
            assertFalse(request.hasField(request.getDescriptorForType()
                    .findFieldByName("reason")), "absent input left out");
            DynamicMessage refund = (DynamicMessage) request.getField(
                    request.getDescriptorForType().findFieldByName("refund"));
            assertEquals(300L, refund.getField(refund.getDescriptorForType()
                    .findFieldByName("amount_cents")));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void statusShouldMapToItsHttpEquivalent() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "missing"));

            assertEquals(404, result.status());
            assertEquals("order missing not found", result.reason());
            assertEquals("NOT_FOUND", ((ContractResult) result).nativeCode());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void timeoutShouldBeTheDeadline() throws Exception {
        GrpcConsumedAdapter adapter = started(spec("http://localhost:" + port,
                PROCEDURES.replace("  rpc: GetOrder\n", "  rpc: GetOrder\n  timeoutMs: 300\n"),
                ""));
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "slow"));

            assertEquals(504, result.status());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void connectionFailureShouldReportNoResponse() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        GrpcConsumedAdapter adapter = started(spec("http://localhost:" + closed, PROCEDURES, ""));
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "1"));

            assertTrue(result.noResponseReceived() || result.status() == 503,
                    "status " + result.status() + " " + result.reason());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void credentialAndStaticHeadersShouldBeSentAsMetadata() throws Exception {
        GrpcConsumedAdapter adapter = started(spec("http://localhost:" + port, PROCEDURES, """
                authentication:
                  type: bearer
                  token: secret-token
                inputParameters:
                  X-Tenant: { in: header, value: acme }
                """));
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "headers"));

            assertEquals("authorization=Bearer secret-token;tenant=acme",
                    result.document().get("status").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void readOnlyShouldComeFromTheIdempotencyLevel() throws Exception {
        GrpcConsumedAdapter adapter = new GrpcConsumedAdapter(null, spec(), Map.of());

        assertFalse(adapter.isWriteOperation("get-order"), "NO_SIDE_EFFECTS reads");
        assertTrue(adapter.isWriteOperation("cancel-order"), "no idempotency level writes");
    }

    @Test
    void readOnlyShouldTrustTheAuthorForGrpc() throws Exception {
        GrpcConsumedAdapter adapter = new GrpcConsumedAdapter(null, spec("http://localhost:"
                + port, PROCEDURES.replace("  rpc: CancelOrder\n",
                        "  rpc: CancelOrder\n  readOnly: true\n"), ""), Map.of());

        assertFalse(adapter.isWriteOperation("cancel-order"),
                "gRPC contracts rarely state it, so readOnly: true is the author's assertion");
    }

    @Test
    void constructorShouldRefuseAnHttpsEndpoint() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("https://localhost:" + port, PROCEDURES,
                        ""), Map.of()));

        assertTrue(e.getMessage().contains("plaintext"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnEndpointWithAPath() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new GrpcConsumedAdapter(null,
                spec("http://localhost:" + port + "/v1", PROCEDURES, ""), Map.of()));
    }

    @Test
    void constructorShouldRefuseACredentialInClearToARemoteHost() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://orders.internal:50051",
                        PROCEDURES, """
                                authentication:
                                  type: bearer
                                  token: secret-token
                                """), Map.of()));

        assertTrue(e.getMessage().contains("tunnel"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnUnknownRpc() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new GrpcConsumedAdapter(null,
                spec("http://localhost:" + port, PROCEDURES.replace("rpc: GetOrder",
                        "rpc: GetOrders"), ""), Map.of()));
    }

    @Test
    void constructorShouldRefuseAStreamingRpc() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("rpc: GetOrder", "rpc: Watch"), ""), Map.of()));

        assertTrue(e.getMessage().contains("unary"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAWireNameTheMessageDoesNotDefine() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("refund.amountCents", "refund.cents"), ""),
                        Map.of()));

        assertTrue(e.getMessage().contains("refund.cents"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnUnknownService() throws Exception {
        GrpcClientSpec spec = spec();
        spec.setService("test.orders.Other");

        assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void factoryShouldBeFoundThroughServiceLoader() {
        assertTrue(ClientAdapterRegistry.getDefault().registeredTypes().contains("grpc"));
    }
}
