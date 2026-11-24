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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.data.Protocol;
import org.restlet.ext.grpc.GrpcClientAdapter;
import com.google.protobuf.DynamicMessage;
import io.grpc.Status;
import tools.jackson.databind.JsonNode;
import io.ikanos.engine.consumes.ClientAdapterRegistry;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.contract.ContractResult;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcClientSpec;
import io.ikanos.spec.consumes.http.tunnel.ZitiTunnelConfigSpec;
import io.ikanos.spec.util.IkanosMappers;

/** The gRPC consumed adapter against an in-process grpc-java server. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GrpcConsumedAdapterTest {

    // Under the working directory, the capability directory of an adapter built without a
    // capability: contract files outside it are refused.
    static final Path dir = Path.of("target", "grpc-contracts");

    private static OrdersFixture fixture;
    private static int port;
    private static Path descriptorSet;

    @BeforeAll
    static void startUpstream() throws Exception {
        fixture = new OrdersFixture();
        java.nio.file.Files.createDirectories(dir);
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

            assertTrue(result.noResponseReceived(),
                    "status " + result.status() + " " + result.reason());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void httpStatusShouldReportNoResponseWhenUnavailableComesFromAConnectionFailure() {
        assertEquals(0, GrpcConsumedAdapter.httpStatus(
                Status.UNAVAILABLE.withCause(new ConnectException("Connection refused"))));
    }

    @Test
    void httpStatusShouldMapAnUnavailableWithoutConnectionFailureTo503() {
        assertEquals(503, GrpcConsumedAdapter.httpStatus(
                Status.UNAVAILABLE.withDescription("draining")));
    }

    /** A tunnel that dials the test server whatever the host, and records the hosts it dials. */
    static final class LoopbackTunnel implements Tunnel {

        final List<String> hosts = new CopyOnWriteArrayList<>();

        @Override
        public String type() {
            return "ziti";
        }

        @Override
        public AsynchronousSocketChannel connect(String host, int target) throws IOException {
            hosts.add(host);
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
            try {
                channel.connect(new InetSocketAddress("localhost", port)).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.close();
                throw new IOException(e);
            } catch (ExecutionException e) {
                channel.close();
                throw new IOException(e.getCause());
            }
            return channel;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    @Test
    void tunneledCallShouldGoThroughTheTunnelOnTheHostName() throws Exception {
        GrpcClientSpec spec = spec("http://orders.tunnel.invalid:50051", PROCEDURES, """
                authentication:
                  type: bearer
                  token: secret-token
                """);
        spec.setTunnel(new ZitiTunnelConfigSpec("orders", "/etc/ziti/app.json"));
        LoopbackTunnel tunnel = new LoopbackTunnel();
        GrpcConsumedAdapter adapter = new GrpcConsumedAdapter(null, spec,
                Map.of("ziti", tunnel));
        adapter.start();
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "headers"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            assertTrue(result.document().get("status").asString()
                    .startsWith("authorization=Bearer secret-token;"),
                    "the credential crosses the tunnel");
            assertFalse(tunnel.hosts.isEmpty(), "the call should be dialed through the tunnel");
            assertTrue(tunnel.hosts.stream().allMatch("orders.tunnel.invalid"::equals),
                    "the tunnel is dialed on the host name: " + tunnel.hosts);
            assertEquals("orders.tunnel.invalid:50051", fixture.lastAuthority.get(),
                    "the server sees the endpoint's authority, not the placeholder address");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void responseShouldPrintAnAnyWithTheTypesOfTheDescriptorSet() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "any"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            JsonNode details = result.document().get("details");
            assertEquals("type.googleapis.com/test.orders.Money",
                    details.get("@type").asString());
            assertEquals("1250", details.get("amountCents").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void responseShouldFailWith502WhenAnAnyPacksAnUnknownType() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        try {
            ConsumedResult result = call(adapter, "get-order", Map.of("order-id", "unknown-any"));

            assertEquals(502, result.status(), String.valueOf(result.reason()));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void startShouldKeepTheClientOfTheFirstStart() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        try {
            Client first = adapter.client();
            adapter.start();

            assertSame(first, adapter.client());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void startShouldStopTheClientWhenTheChannelFailsToStart() throws Exception {
        AtomicReference<Client> created = new AtomicReference<>();
        AtomicBoolean startedBefore = new AtomicBoolean();
        GrpcConsumedAdapter adapter = new GrpcConsumedAdapter(null, spec(), Map.of()) {
            @Override
            protected Client createClient(Context context, List<Protocol> protocols) {
                Client client = super.createClient(context, protocols);
                created.set(client);
                return client;
            }

            @Override
            GrpcClientAdapter newChannelAdapter(Client client) {
                startedBefore.set(client.isStarted());
                throw new IllegalStateException("channel refused");
            }
        };

        assertThrows(IllegalStateException.class, adapter::start);
        assertTrue(startedBefore.get(), "the client was started before the channel");
        assertTrue(created.get().isStopped(), "the started client is stopped, not leaked");
        assertNull(adapter.client());
    }

    @Test
    void stopShouldStopTheClient() throws Exception {
        GrpcConsumedAdapter adapter = started(spec());
        Client client = adapter.client();

        adapter.stop();

        assertTrue(client.isStopped());
        assertNull(adapter.client());
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
    void wireNameShouldGoThroughAStringMapKeyIntoItsMessageValue() throws Exception {
        GrpcConsumedAdapter adapter = started(spec("http://localhost:" + port, PROCEDURES
                .replace("wireName: refund.amountCents", "wireName: refunds.EUR.amountCents"),
                ""));
        try {
            call(adapter, "cancel-order", Map.of("order-id", "8", "refund", "450"));

            DynamicMessage request = fixture.lastCancel.get();
            java.util.List<?> entries = (java.util.List<?>) request.getField(
                    request.getDescriptorForType().findFieldByName("refunds"));
            assertEquals(1, entries.size());
            DynamicMessage entry = (DynamicMessage) entries.get(0);
            assertEquals("EUR", entry.getField(entry.getDescriptorForType()
                    .findFieldByName("key")));
            DynamicMessage money = (DynamicMessage) entry.getField(entry.getDescriptorForType()
                    .findFieldByName("value"));
            assertEquals(450L, money.getField(money.getDescriptorForType()
                    .findFieldByName("amount_cents")));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void wireNameShouldEndOnAStringMapKeyAndReachAOneofMember() throws Exception {
        assertDoesNotThrow(() -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                PROCEDURES.replace("reason: { type: string, description: Why. }",
                        "reason: { type: string, description: Why., wireName: labels.reason }\n"
                                + "    card: { type: string, description: Card., wireName: byCard }"),
                ""), Map.of()));
    }

    @Test
    void constructorShouldRefuseAMapWhoseKeysAreNotStrings() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("wireName: refund.amountCents", "wireName: slots.1"),
                        ""), Map.of()));

        assertTrue(e.getMessage().contains("keys are not strings"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAPathThroughATimestamp() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("wireName: refund.amountCents",
                                "wireName: placedAt.seconds"), ""), Map.of()));

        assertTrue(e.getMessage().contains("google.protobuf.Timestamp"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAPathThroughAWrapper() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("wireName: refund.amountCents", "wireName: note.value"),
                        ""), Map.of()));

        assertTrue(e.getMessage().contains("google.protobuf.StringValue"), e.getMessage());
    }

    @Test
    void wireNameShouldEndOnAWellKnownType() throws Exception {
        assertDoesNotThrow(() -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                PROCEDURES.replace("wireName: refund.amountCents", "wireName: placedAt")
                        .replace("reason: { type: string, description: Why. }",
                                "reason: { type: string, description: Why., wireName: note }"),
                ""), Map.of()));
    }

    @Test
    void wireNameShouldSetAStructKey() throws Exception {
        GrpcConsumedAdapter adapter = started(spec("http://localhost:" + port, PROCEDURES
                .replace("reason: { type: string, description: Why. }",
                        "reason: { type: string, description: Why., wireName: meta.channel }"),
                ""));
        try {
            call(adapter, "cancel-order", Map.of("order-id", "9", "reason", "web"));

            DynamicMessage request = fixture.lastCancel.get();
            DynamicMessage meta = (DynamicMessage) request.getField(
                    request.getDescriptorForType().findFieldByName("meta"));
            List<?> fields = (List<?>) meta.getField(meta.getDescriptorForType()
                    .findFieldByName("fields"));
            assertEquals(1, fields.size());
            DynamicMessage entry = (DynamicMessage) fields.get(0);
            assertEquals("channel", entry.getField(entry.getDescriptorForType()
                    .findFieldByName("key")));
            DynamicMessage value = (DynamicMessage) entry.getField(entry.getDescriptorForType()
                    .findFieldByName("value"));
            assertEquals("web", value.getField(value.getDescriptorForType()
                    .findFieldByName("string_value")));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void constructorShouldRefuseAPathThroughAStructKey() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("wireName: refund.amountCents",
                                "wireName: meta.channel.web"), ""), Map.of()));

        assertTrue(e.getMessage().contains("ends on a Struct key"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseTwoMembersOfOneOneof() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GrpcConsumedAdapter(null, spec("http://localhost:" + port,
                        PROCEDURES.replace("reason: { type: string, description: Why. }",
                                "card: { type: string, description: Card., wireName: by_card }\n"
                                        + "    voucher: { type: string, description: Voucher.,"
                                        + " wireName: byVoucher }"),
                        ""), Map.of()));

        assertTrue(e.getMessage().contains("'card' and 'voucher'"), e.getMessage());
        assertTrue(e.getMessage().contains("union 'payment'"), e.getMessage());
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
