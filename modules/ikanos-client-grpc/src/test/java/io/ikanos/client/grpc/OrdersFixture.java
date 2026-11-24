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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;

/**
 * A small order service: its descriptor set, written to a file as {@code protoc} would, and an
 * in-process grpc-java server implementing it.
 */
final class OrdersFixture {

    static final String SERVICE = "test.orders.OrderService";

    final FileDescriptor file;
    final ServiceDescriptor service;
    final AtomicReference<Metadata> lastHeaders = new AtomicReference<>();
    final AtomicReference<DynamicMessage> lastCancel = new AtomicReference<>();
    private Server server;

    /** The well-known files orders.proto imports, left out of its descriptor set. */
    static final List<String> IMPORTS = List.of("google/protobuf/any.proto",
            "google/protobuf/struct.proto", "google/protobuf/timestamp.proto",
            "google/protobuf/wrappers.proto");

    OrdersFixture() throws Exception {
        FileDescriptor[] imports = new FileDescriptor[IMPORTS.size()];
        for (int i = 0; i < imports.length; i++) {
            imports[i] = WellKnownTypes.file(IMPORTS.get(i));
        }
        file = FileDescriptor.buildFrom(proto(), imports);
        service = file.findServiceByName("OrderService");
    }

    static FileDescriptorProto proto() {
        return FileDescriptorProto.newBuilder().setName("orders.proto")
                .setPackage("test.orders").setSyntax("proto3").addAllDependency(IMPORTS)
                .addMessageType(message("GetOrderRequest", field("id", 1,
                        FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(message("Money",
                        field("amount_cents", 1, FieldDescriptorProto.Type.TYPE_INT64),
                        field("currency", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("Order")
                        .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("status", 2, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("customer_id", 3, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("total", 4, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".test.orders.Money"))
                        .addField(field("priority", 5, FieldDescriptorProto.Type.TYPE_INT32))
                        .addField(field("details", 6, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Any")))
                .addMessageType(DescriptorProto.newBuilder().setName("CancelOrderRequest")
                        .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("reason", 2, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("refund", 3, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".test.orders.Money"))
                        // map<string, Money> refunds, map<string, string> labels,
                        // map<int32, string> slots, and oneof payment { by_card, by_voucher }
                        .addField(mapField("refunds", 4, "RefundsEntry"))
                        .addField(mapField("labels", 5, "LabelsEntry"))
                        .addField(mapField("slots", 6, "SlotsEntry"))
                        .addOneofDecl(OneofDescriptorProto.newBuilder().setName("payment"))
                        .addField(field("by_card", 7, FieldDescriptorProto.Type.TYPE_STRING)
                                .setOneofIndex(0))
                        .addField(field("by_voucher", 8, FieldDescriptorProto.Type.TYPE_STRING)
                                .setOneofIndex(0))
                        // Well-known types: Timestamp placed_at, StringValue note, Struct meta
                        .addField(field("placed_at", 9, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Timestamp"))
                        .addField(field("note", 10, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.StringValue"))
                        .addField(field("meta", 11, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Struct"))
                        .addNestedType(entry("RefundsEntry", FieldDescriptorProto.Type.TYPE_STRING,
                                field("value", 2, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                        .setTypeName(".test.orders.Money")))
                        .addNestedType(entry("LabelsEntry", FieldDescriptorProto.Type.TYPE_STRING,
                                field("value", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                        .addNestedType(entry("SlotsEntry", FieldDescriptorProto.Type.TYPE_INT32,
                                field("value", 2, FieldDescriptorProto.Type.TYPE_STRING))))
                .addMessageType(message("CancelOrderResponse", field("cancelled", 1,
                        FieldDescriptorProto.Type.TYPE_BOOL)))
                .addService(ServiceDescriptorProto.newBuilder().setName("OrderService")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("GetOrder")
                                .setInputType(".test.orders.GetOrderRequest")
                                .setOutputType(".test.orders.Order")
                                .setOptions(MethodOptions.newBuilder().setIdempotencyLevel(
                                        MethodOptions.IdempotencyLevel.NO_SIDE_EFFECTS)))
                        .addMethod(MethodDescriptorProto.newBuilder().setName("CancelOrder")
                                .setInputType(".test.orders.CancelOrderRequest")
                                .setOutputType(".test.orders.CancelOrderResponse"))
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Watch")
                                .setInputType(".test.orders.GetOrderRequest")
                                .setOutputType(".test.orders.Order")
                                .setServerStreaming(true)))
                .build();
    }

    private static DescriptorProto message(String name, FieldDescriptorProto.Builder... fields) {
        DescriptorProto.Builder builder = DescriptorProto.newBuilder().setName(name);
        for (FieldDescriptorProto.Builder field : fields) {
            builder.addField(field);
        }
        return builder.build();
    }

    /** A map field, as protoc writes it: a repeated field of a nested map entry message. */
    private static FieldDescriptorProto.Builder mapField(String name, int number, String entry) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .setTypeName(".test.orders.CancelOrderRequest." + entry);
    }

    private static DescriptorProto entry(String name, FieldDescriptorProto.Type key,
            FieldDescriptorProto.Builder value) {
        return DescriptorProto.newBuilder().setName(name)
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(field("key", 1, key)).addField(value).build();
    }

    private static FieldDescriptorProto.Builder field(String name, int number,
            FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    /** Writes the descriptor set to {@code dir/orders.binpb}. */
    Path writeDescriptorSet(Path dir) throws Exception {
        Path target = dir.resolve("orders.binpb");
        Files.write(target, FileDescriptorSet.newBuilder().addFile(proto()).build().toByteArray());
        return target;
    }

    /** Starts the server on a free port and returns the port. */
    int start() throws Exception {
        MethodDescriptor get = service.findMethodByName("GetOrder");
        MethodDescriptor cancel = service.findMethodByName("CancelOrder");
        ServerServiceDefinition definition = ServerServiceDefinition.builder(SERVICE)
                .addMethod(method(get), ServerCalls.asyncUnaryCall((request, out) -> {
                    String id = (String) request.getField(field(get.getInputType(), "id"));
                    if ("missing".equals(id)) {
                        out.onError(Status.NOT_FOUND.withDescription("order " + id
                                + " not found").asRuntimeException());
                        return;
                    }
                    if ("slow".equals(id)) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    Descriptor order = get.getOutputType();
                    Descriptor money = order.findFieldByName("total").getMessageType();
                    Metadata headers = lastHeaders.get();
                    String status = "headers".equals(id) && headers != null
                            ? "authorization=" + headers.get(key("authorization")) + ";tenant="
                                    + headers.get(key("x-tenant"))
                            : "open";
                    DynamicMessage total = DynamicMessage.newBuilder(money)
                            .setField(money.findFieldByName("amount_cents"), 1250L)
                            .setField(money.findFieldByName("currency"), "EUR").build();
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(order)
                            .setField(order.findFieldByName("id"), id)
                            .setField(order.findFieldByName("status"), status)
                            .setField(order.findFieldByName("customer_id"), "c-" + id)
                            .setField(order.findFieldByName("total"), total);
                    if ("any".equals(id) || "unknown-any".equals(id)) {
                        // details: an Any packing the total, or a type no descriptor defines
                        Descriptor any = order.findFieldByName("details").getMessageType();
                        reply.setField(order.findFieldByName("details"), DynamicMessage
                                .newBuilder(any)
                                .setField(any.findFieldByName("type_url"), "any".equals(id)
                                        ? "type.googleapis.com/test.orders.Money"
                                        : "type.googleapis.com/test.orders.Unknown")
                                .setField(any.findFieldByName("value"), total.toByteString())
                                .build());
                    }
                    out.onNext(reply.build());
                    out.onCompleted();
                }))
                .addMethod(method(cancel), ServerCalls.asyncUnaryCall((request, out) -> {
                    lastCancel.set(request);
                    Descriptor response = cancel.getOutputType();
                    out.onNext(DynamicMessage.newBuilder(response)
                            .setField(response.findFieldByName("cancelled"), true).build());
                    out.onCompleted();
                }))
                .build();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call,
                    Metadata headers, ServerCallHandler<Q, S> next) {
                lastHeaders.set(headers);
                return Contexts.interceptCall(io.grpc.Context.current(), call, headers, next);
            }
        };
        server = NettyServerBuilder.forPort(0).addService(definition).intercept(capture).build()
                .start();
        return server.getPort();
    }

    void stop() throws Exception {
        if (server != null) {
            server.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    private static com.google.protobuf.Descriptors.FieldDescriptor field(Descriptor type,
            String name) {
        return type.findFieldByName(name);
    }

    private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> method(
            MethodDescriptor method) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE,
                        method.getName()))
                .setRequestMarshaller(new GrpcConsumedAdapter.DynamicMarshaller(
                        method.getInputType()))
                .setResponseMarshaller(new GrpcConsumedAdapter.DynamicMarshaller(
                        method.getOutputType()))
                .build();
    }
}
