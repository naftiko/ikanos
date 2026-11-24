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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodOptions.IdempotencyLevel;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.opentelemetry.api.common.AttributeKey;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Header;
import org.restlet.data.Protocol;
import org.restlet.engine.header.HeaderConstants;
import org.restlet.util.Series;
import org.restlet.ext.grpc.GrpcClientAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedInvocation.ClientSpanDescriptor;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.contract.ContractClientAdapter;
import io.ikanos.engine.consumes.contract.ContractInvocation;
import io.ikanos.engine.consumes.contract.ContractRequest;
import io.ikanos.engine.consumes.contract.ContractResult;
import io.ikanos.engine.consumes.contract.UnionChoices;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.grpc.GrpcClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcProcedureSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The {@code consumes: type: grpc} adapter: unary methods of one gRPC service, described by a
 * local protobuf descriptor set and called with binary protobuf through Restlet's
 * {@link GrpcClientAdapter}, on a borrowed HTTP/2 Restlet client.
 *
 * <p>When the capability loads, the descriptor set is resolved, each procedure's {@code rpc} is
 * found in the service and must be unary, and each input's {@code wireName} must resolve in the
 * request message (fields, keys of string-keyed maps, {@code oneof} members, at most one of each
 * {@code oneof}). A call writes the inputs into a JSON tree ({@link ContractRequest}),
 * parses it into the request message with the proto3 JSON mapping (unknown fields refused), runs
 * the call with {@code timeoutMs} as its deadline, and prints the response message in the proto3
 * JSON mapping, fields without presence included, as the result document.</p>
 *
 * <p>Restlet 3.0.0-m2's gRPC client is plaintext only (HTTP/2 prior knowledge): an
 * {@code https://} endpoint is refused, and so is a credential sent in clear to a host that is
 * neither a loopback address nor reached through a {@code tunnel}. A tunneled endpoint's host
 * name is never resolved: grpc-java is given a placeholder address, and each request is routed on
 * the host name, so through the tunnel.</p>
 */
public class GrpcConsumedAdapter extends ContractClientAdapter {

    private static final ObjectMapper JSON = IkanosMappers.json();

    static final AttributeKey<String> ATTR_RPC_SYSTEM = AttributeKey.stringKey("rpc.system");
    static final AttributeKey<String> ATTR_RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    static final AttributeKey<String> ATTR_RPC_METHOD = AttributeKey.stringKey("rpc.method");

    /** Largest reason taken from a status description. */
    static final int MAX_REASON = 200;

    /**
     * The address grpc-java dials for a tunneled endpoint, so that it resolves no host name:
     * {@link MetadataClient} puts the endpoint's host name back on each request before the
     * connector routes it to the tunnel. RFC 5737 TEST-NET-1, never routed.
     */
    static final String TUNNEL_PLACEHOLDER = "192.0.2.1";

    /**
     * The well-known types whose proto3 JSON is not an object of their fields: an input path can
     * end on one of them, not go through it.
     */
    static final Set<String> OPAQUE_TYPES = Set.of("google.protobuf.Any",
            "google.protobuf.Duration", "google.protobuf.FieldMask", "google.protobuf.ListValue",
            "google.protobuf.Timestamp", "google.protobuf.Value", "google.protobuf.BoolValue",
            "google.protobuf.BytesValue", "google.protobuf.DoubleValue",
            "google.protobuf.FloatValue", "google.protobuf.Int32Value",
            "google.protobuf.Int64Value", "google.protobuf.StringValue",
            "google.protobuf.UInt32Value", "google.protobuf.UInt64Value");

    /** A message printed as a JSON object of any keys: like a {@code map<string, Value>}. */
    static final String STRUCT = "google.protobuf.Struct";

    /** One procedure, ready to call. */
    record Procedure(String name, GrpcProcedureSpec spec, MethodDescriptor method,
            io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> call,
            ConsumedOperationView view) {
    }

    private final String endpoint;
    private final String host;
    private final int port;
    private final ServiceDescriptor service;
    private final Map<String, Procedure> procedures;
    private final JsonFormat.Printer printer;
    private final JsonFormat.Parser parser;
    private volatile GrpcClientAdapter channelAdapter;
    private volatile Client restletClient;

    public GrpcConsumedAdapter(Capability capability, GrpcClientSpec spec,
            Map<String, Tunnel> tunnels) {
        super(capability, spec, tunnels);
        this.endpoint = resolve("endpoint", spec.getEndpoint());
        URI uri = parseEndpoint(this.endpoint);
        this.host = uri.getHost();
        this.port = uri.getPort();
        if (spec.getAuthentication() != null && !isLoopback(host) && tunnel() == null) {
            throw new IllegalArgumentException(where() + " sends a credential over plaintext"
                    + " HTTP/2 to '" + host + "': reach it on a loopback address or through a"
                    + " tunnel, since Restlet 3.0.0-m2's gRPC client does not support TLS");
        }
        if (spec.getAuthentication() != null) {
            credentialHeaders();
        }
        Map<String, FileDescriptor> files = loadDescriptors(spec.getDescriptorSet());
        this.service = findService(files, spec.getService());
        JsonFormat.TypeRegistry types = typeRegistry(files.values());
        this.printer = JsonFormat.printer().usingTypeRegistry(types)
                .alwaysPrintFieldsWithNoPresence();
        this.parser = JsonFormat.parser().usingTypeRegistry(types);
        Map<String, Procedure> built = new LinkedHashMap<>();
        for (GrpcProcedureSpec procedure : spec.getProcedures().values()) {
            built.put(procedure.getName(), buildProcedure(procedure));
        }
        this.procedures = Collections.unmodifiableMap(built);
    }

    URI parseEndpoint(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(where() + " requires endpoint");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where() + " endpoint is not a valid URI", e);
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(where() + " endpoint uses https, but Restlet"
                    + " 3.0.0-m2's gRPC client is plaintext only (HTTP/2 prior knowledge): use"
                    + " http:// on a loopback address, inside a mesh, or through a tunnel");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getPort() < 0) {
            throw new IllegalArgumentException(where() + " endpoint must be http://host:port");
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                && !"/".equals(uri.getRawPath()) || uri.getRawQuery() != null) {
            throw new IllegalArgumentException(where() + " endpoint must be an authority without"
                    + " a path: the gRPC path is /package.Service/Method");
        }
        return uri;
    }

    /** Builds the file descriptors of a descriptor set, resolving their imports. */
    Map<String, FileDescriptor> loadDescriptors(String path) {
        FileDescriptorSet set;
        try {
            set = FileDescriptorSet.parseFrom(readContract("descriptorSet", path));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(where() + " descriptorSet '" + path
                    + "' is not a binary FileDescriptorSet", e);
        }
        Map<String, FileDescriptorProto> protos = new HashMap<>();
        set.getFileList().forEach(proto -> protos.put(proto.getName(), proto));
        Map<String, FileDescriptor> built = new LinkedHashMap<>();
        for (FileDescriptorProto proto : set.getFileList()) {
            build(proto.getName(), protos, built, new java.util.HashSet<>());
        }
        return built;
    }

    private FileDescriptor build(String name, Map<String, FileDescriptorProto> protos,
            Map<String, FileDescriptor> built, java.util.Set<String> visiting) {
        FileDescriptor done = built.get(name);
        if (done != null) {
            return done;
        }
        FileDescriptorProto proto = protos.get(name);
        if (proto == null) {
            FileDescriptor wellKnown = WellKnownTypes.file(name);
            if (wellKnown == null) {
                throw new IllegalArgumentException(where() + " descriptorSet lacks '" + name
                        + "': build it with --include_imports");
            }
            built.put(name, wellKnown);
            return wellKnown;
        }
        if (!visiting.add(name)) {
            throw new IllegalArgumentException(where() + " descriptorSet has an import cycle at '"
                    + name + "'");
        }
        FileDescriptor[] dependencies = new FileDescriptor[proto.getDependencyCount()];
        for (int i = 0; i < dependencies.length; i++) {
            dependencies[i] = build(proto.getDependency(i), protos, built, visiting);
        }
        try {
            FileDescriptor file = FileDescriptor.buildFrom(proto, dependencies);
            built.put(name, file);
            return file;
        } catch (DescriptorValidationException e) {
            throw new IllegalArgumentException(where() + " descriptorSet file '" + name
                    + "' is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * The message types of the descriptor set and of its imports, so that the proto3 JSON of a
     * {@code google.protobuf.Any} can name and print the message it packs.
     */
    static JsonFormat.TypeRegistry typeRegistry(Collection<FileDescriptor> files) {
        JsonFormat.TypeRegistry.Builder builder = JsonFormat.TypeRegistry.newBuilder();
        for (FileDescriptor file : files) {
            builder.add(file.getMessageTypes());
        }
        return builder.build();
    }

    ServiceDescriptor findService(Map<String, FileDescriptor> files, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(where() + " requires service");
        }
        for (FileDescriptor file : files.values()) {
            for (ServiceDescriptor candidate : file.getServices()) {
                if (candidate.getFullName().equals(name)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException(where() + " descriptorSet defines no service '" + name
                + "'");
    }

    Procedure buildProcedure(GrpcProcedureSpec spec) {
        String where = where() + " procedure '" + spec.getName() + "'";
        MethodDescriptor method = spec.getRpc() != null ? service.findMethodByName(spec.getRpc())
                : null;
        if (method == null) {
            throw new IllegalArgumentException(where + " calls rpc '" + spec.getRpc()
                    + "', which service '" + service.getFullName() + "' does not define");
        }
        if (method.isClientStreaming() || method.isServerStreaming()) {
            throw new IllegalArgumentException(where + " calls a streaming rpc: consumes type"
                    + " grpc only calls unary methods");
        }
        UnionChoices unions = new UnionChoices(where);
        for (InputParameterSpec input : spec.getInputParameters()) {
            checkPath(where, input, method.getInputType(), unions);
        }
        checkReadOnly(spec.getName(), noSideEffects(method), true);
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> call =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(
                                service.getFullName(), method.getName()))
                        .setRequestMarshaller(new DynamicMarshaller(method.getInputType()))
                        .setResponseMarshaller(new DynamicMarshaller(method.getOutputType()))
                        .setSafe(noSideEffects(method))
                        .build();
        return new Procedure(spec.getName(), spec, method, call, view(spec));
    }

    /**
     * Checks that an input's path resolves in the request message: each segment is a field, by
     * name or JSON name, or the key of the {@code map<string, ...>} or
     * {@code google.protobuf.Struct} field before it (a {@code Struct} key ends the path). A path
     * can end on a well-known type whose proto3 JSON is not an object of fields (such as a
     * {@code Timestamp} or a wrapper), not go through it. A {@code oneof} field the path sets is
     * reported to {@code unions}.
     */
    void checkPath(String where, InputParameterSpec input, Descriptor message,
            UnionChoices unions) {
        Descriptor current = message;
        List<String> path = ContractRequest.path(input);
        // The path in canonical field names (and map keys), so a JSON name alias is the same place
        List<String> place = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            FieldDescriptor field = field(current, path.get(i));
            if (field == null) {
                throw new IllegalArgumentException(where + " input '" + input.getName()
                        + "' targets '" + String.join(".", path)
                        + "', which does not resolve in message '" + message.getFullName()
                        + "'");
            }
            OneofDescriptor oneof = field.getRealContainingOneof();
            if (oneof != null) {
                unions.choose(input.getName(), List.copyOf(place), oneof.getName(),
                        field.getName());
            }
            place.add(field.getName());
            if (i == path.size() - 1) {
                break;
            }
            FieldDescriptor next = field;
            if (field.isMapField()) {
                Descriptor entry = field.getMessageType();
                if (entry.findFieldByName("key").getJavaType() != FieldDescriptor.JavaType.STRING) {
                    throw new IllegalArgumentException(where + " input '" + input.getName()
                            + "' goes through map '" + path.get(i)
                            + "', whose keys are not strings");
                }
                i++;
                place.add(path.get(i));
                if (i == path.size() - 1) {
                    break;
                }
                next = entry.findFieldByName("value");
            } else if (field.isRepeated()) {
                next = null;
            }
            if (next == null || next.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                throw new IllegalArgumentException(where + " input '" + input.getName()
                        + "' goes through '" + path.get(i) + "', which is not a message");
            }
            current = next.getMessageType();
            if (STRUCT.equals(current.getFullName())) {
                i++;
                if (i < path.size() - 1) {
                    throw new IllegalArgumentException(where + " input '" + input.getName()
                            + "' goes through key '" + path.get(i) + "' of Struct '"
                            + path.get(i - 1) + "', whose value is any JSON value: a path ends"
                            + " on a Struct key");
                }
                break;
            }
            if (OPAQUE_TYPES.contains(current.getFullName())) {
                throw new IllegalArgumentException(where + " input '" + input.getName()
                        + "' goes through '" + path.get(i) + "', a " + current.getFullName()
                        + " whose proto3 JSON is not an object of fields: a path can end on it,"
                        + " not go through it");
            }
        }
    }

    static FieldDescriptor field(Descriptor message, String name) {
        FieldDescriptor field = message.findFieldByName(name);
        if (field != null) {
            return field;
        }
        for (FieldDescriptor candidate : message.getFields()) {
            if (candidate.getJsonName().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    static boolean noSideEffects(MethodDescriptor method) {
        return method.getOptions().getIdempotencyLevel() == IdempotencyLevel.NO_SIDE_EFFECTS;
    }

    @Override
    protected boolean contractReadOnly(String operationName) {
        Procedure procedure = procedures != null ? procedures.get(operationName) : null;
        return procedure != null && noSideEffects(procedure.method());
    }

    /** Starts the Restlet client and the channel on it; a second start keeps the first. */
    @Override
    public synchronized void start() throws Exception {
        if (restletClient != null) {
            return;
        }
        Context context = new Context();
        // ext.grpc requires HTTP/2 with prior knowledge, and redirects left to grpc-java
        context.getParameters().set("httpClientTransportMode", "HTTP2");
        context.getParameters().set("followRedirects", "false");
        Client created = newRestletClient(endpoint, context, List.of(Protocol.HTTP));
        GrpcClientAdapter adapter;
        try {
            created.start();
            adapter = newChannelAdapter(created);
            adapter.start();
        } catch (Exception e) {
            try {
                created.stop();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        this.restletClient = created;
        this.channelAdapter = adapter;
    }

    /** @return the channel adapter on {@code client}, configured and not started */
    GrpcClientAdapter newChannelAdapter(Client client) {
        GrpcClientAdapter adapter = new GrpcClientAdapter();
        adapter.setClient(client);
        adapter.setTarget(channelTarget());
        adapter.setPlaintext(true);
        adapter.setMaxCallDuration(Duration.ofMillis(maxTimeoutMillis()));
        adapter.getInterceptors().add(new HeadersInterceptor());
        return adapter;
    }

    /**
     * The grpc-java target: the endpoint's host or, for a tunneled endpoint, the
     * {@link #TUNNEL_PLACEHOLDER}, since ext.grpc requests the address grpc-java resolved while
     * the tunnel is routed on the host name, which may resolve nowhere but in the tunnel.
     */
    String channelTarget() {
        return "dns:///" + (tunnel() != null ? TUNNEL_PLACEHOLDER : host) + ":" + port;
    }

    /** @return the started Restlet client, or {@code null} */
    Client client() {
        return restletClient;
    }

    @Override
    protected Client createClient(Context context, List<Protocol> protocols) {
        return new MetadataClient(context, protocols, tunnel() != null ? host : null);
    }

    /**
     * A Restlet client that sends every gRPC metadata entry. Restlet 3.0.0-m2's
     * {@code org.restlet.ext.grpc} copies the metadata into {@code Request.getHeaders()}, from
     * which the client connector drops standard headers such as {@code Authorization}: the
     * credential would never leave. This client moves them to
     * {@link HeaderConstants#ATTRIBUTE_RAW_REQUEST_HEADERS}, which the connector writes as is.
     *
     * <p>ext.grpc also requests the address grpc-java resolved, not the host name. For a tunneled
     * endpoint, this client puts the host name back, since the connector picks the tunnel by the
     * request's host.</p>
     */
    static final class MetadataClient extends Client {

        private final String routeHost;

        /**
         * @param routeHost the host name every request is routed on, or {@code null} to keep
         *        the request's host
         */
        MetadataClient(Context context, List<Protocol> protocols, String routeHost) {
            super(context, protocols);
            this.routeHost = routeHost;
        }

        @Override
        public void handle(Request request, Response response) {
            if (routeHost != null) {
                request.getResourceRef().setHostDomain(routeHost);
                // ext.grpc sets the base reference to grpc-java's authority (the placeholder
                // address), and Restlet writes Host, sent as :authority, from it
                org.restlet.data.Reference base = request.getResourceRef().getBaseRef();
                if (base != null) {
                    base.setHostDomain(routeHost);
                }
            }
            Series<Header> raw = new Series<>(Header.class);
            for (Header header : request.getHeaders()) {
                raw.add(header.getName(), header.getValue());
            }
            if (!raw.isEmpty()) {
                request.getAttributes().put(HeaderConstants.ATTRIBUTE_RAW_REQUEST_HEADERS, raw);
            }
            super.handle(request, response);
        }
    }

    long maxTimeoutMillis() {
        long max = 1;
        for (Procedure procedure : procedures.values()) {
            max = Math.max(max, procedure.spec().timeoutMillis());
        }
        return max;
    }

    @Override
    public synchronized void stop() throws Exception {
        GrpcClientAdapter adapter = channelAdapter;
        channelAdapter = null;
        Client created = restletClient;
        restletClient = null;
        try {
            if (adapter != null) {
                adapter.stop();
            }
        } finally {
            if (created != null) {
                created.stop();
            }
        }
    }

    /** Sends the static headers, the credential and the trace context as metadata. */
    final class HeadersInterceptor implements ClientInterceptor {
        @Override
        public <Q, S> ClientCall<Q, S> interceptCall(io.grpc.MethodDescriptor<Q, S> method,
                CallOptions options, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(
                    next.newCall(method, options)) {
                @Override
                public void start(Listener<S> listener, Metadata headers) {
                    put(headers, staticHeaders());
                    put(headers, credentialHeaders());
                    put(headers, traceHeaders());
                    super.start(listener, headers);
                }
            };
        }
    }

    static void put(Metadata metadata, Map<String, String> headers) {
        headers.forEach((name, value) -> {
            Metadata.Key<String> key = Metadata.Key.of(name.toLowerCase(java.util.Locale.ROOT),
                    Metadata.ASCII_STRING_MARSHALLER);
            metadata.removeAll(key);
            metadata.put(key, value);
        });
    }

    @Override
    public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
        Procedure procedure = procedures.get(operationName);
        if (procedure == null) {
            return null;
        }
        String where = getNamespace() + "." + operationName;
        ContractRequest request = ContractRequest.build(procedure.spec().getInputParameters(),
                parameters, where);
        boundNumbers(request.tree(), procedure.method().getInputType(), "", where);
        DynamicMessage.Builder builder =
                DynamicMessage.newBuilder(procedure.method().getInputType());
        try {
            parser.merge(JSON.writeValueAsString(request.tree()), builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("The inputs of " + where + " do not fit message '"
                    + procedure.method().getInputType().getFullName() + "': " + e.getMessage(),
                    e);
        }
        DynamicMessage message = builder.build();
        Map<AttributeKey<String>, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_RPC_SYSTEM, "grpc");
        attributes.put(ATTR_RPC_SERVICE, service.getFullName());
        attributes.put(ATTR_RPC_METHOD, procedure.method().getName());
        return new ContractInvocation(getNamespace(), procedure.view(),
                new ClientSpanDescriptor(service.getFullName() + "/" + procedure.method().getName(),
                        endpoint),
                attributes, () -> execute(procedure, message));
    }

    /**
     * Refuses a number with too many digits for the exact conversions of protobuf's JSON parser,
     * wherever the input message gives a numeric type: a field, a map key, a list item or a
     * numeric wrapper. proto3 JSON gives a 64-bit integer (and any map key) as text, which the
     * parser converts with {@code toBigIntegerExact}, so the bound of
     * {@link ContractRequest#boundedNumber} must hold there; a {@code string} or {@code bytes}
     * field keeps any text. A numeric value is bounded whole, since the parser also reads a
     * number from a one-element array, and a key two fields share is bounded for both, since the
     * parser keeps the last. Inside a {@code google.protobuf.Any}, whose packed type is only
     * known to the parser, every number and every text that reads as one is bounded.
     *
     * @param node a value of the request tree
     * @param message the message type it fills
     * @param path its wire path, for the error message
     * @param where the procedure, for the error message
     * @throws IllegalArgumentException for a number with too many digits
     */
    static void boundNumbers(JsonNode node, Descriptor message, String path, String where) {
        String type = message.getFullName();
        if (NUMERIC_WRAPPERS.contains(type)) {
            boundAll(node, path, where);
            return;
        }
        if (type.equals("google.protobuf.Any")) {
            boundAll(node, path, where);
            return;
        }
        if (!node.isObject() || OPAQUE_TYPES.contains(type) || type.equals(STRUCT)) {
            return; // no exact integer conversion: text, a Struct, a Value or a ListValue
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            // None: the parser refuses a field the message does not define
            for (FieldDescriptor field : fieldsOf(message, entry.getKey())) {
                boundField(entry.getValue(), field,
                        path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), where);
            }
        }
    }

    private static void boundField(JsonNode value, FieldDescriptor field, String fieldPath,
            String where) {
        if (field.isMapField()) {
            FieldDescriptor key = field.getMessageType().findFieldByName("key");
            FieldDescriptor mapValue = field.getMessageType().findFieldByName("value");
            for (Map.Entry<String, JsonNode> item : value.properties()) {
                String itemPath = fieldPath + "." + item.getKey();
                if (isNumeric(key)) {
                    boundNumber(JSON.getNodeFactory().stringNode(item.getKey()), itemPath,
                            where);
                }
                boundValue(item.getValue(), mapValue, itemPath, where);
            }
        } else if (field.isRepeated() && value.isArray()) {
            int index = 0;
            for (JsonNode element : value) {
                boundValue(element, field, fieldPath + "[" + index++ + "]", where);
            }
        } else {
            boundValue(value, field, fieldPath, where);
        }
    }

    /** The integer and floating types, and their wrappers, that the parser converts. */
    static final Set<String> NUMERIC_WRAPPERS = Set.of("google.protobuf.DoubleValue",
            "google.protobuf.FloatValue", "google.protobuf.Int32Value",
            "google.protobuf.Int64Value", "google.protobuf.UInt32Value",
            "google.protobuf.UInt64Value");

    private static void boundValue(JsonNode value, FieldDescriptor field, String path,
            String where) {
        if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            boundNumbers(value, field.getMessageType(), path, where);
        } else if (isNumeric(field)) {
            // All of it: the parser reads a number from a one-element array (Gson getAsString)
            boundAll(value, path, where);
        }
    }

    private static boolean isNumeric(FieldDescriptor field) {
        return switch (field.getJavaType()) {
            case INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    /**
     * The fields a proto3 JSON key may name: by json_name or proto name. Two fields can share a
     * key in a proto2 descriptor, and the parser keeps the last; bounding against each is safe.
     */
    private static List<FieldDescriptor> fieldsOf(Descriptor message, String key) {
        List<FieldDescriptor> fields = new ArrayList<>(1);
        for (FieldDescriptor field : message.getFields()) {
            if (field.getJsonName().equals(key) || field.getName().equals(key)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** Every number, text that reads as one, and object key, at any depth. */
    private static void boundAll(JsonNode node, String path, String where) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String entryPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                boundNumber(JSON.getNodeFactory().stringNode(entry.getKey()), entryPath, where);
                boundAll(entry.getValue(), entryPath, where);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node) {
                boundAll(element, path + "[" + index++ + "]", where);
            }
        } else {
            boundNumber(node, path, where);
        }
    }

    private static void boundNumber(JsonNode node, String path, String where) {
        if (!node.isNumber() && !node.isString()) {
            return;
        }
        BigDecimal value;
        try {
            value = node.isNumber() ? node.decimalValue()
                    : new BigDecimal(node.asString().trim());
        } catch (RuntimeException notANumber) {
            return; // not a number, or infinite: the parser refuses it on its own
        }
        try {
            ContractRequest.boundedNumber(value);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The input '" + path + "' of " + where
                    + " has more than " + ContractRequest.MAX_NUMBER_DIGITS + " digits", e);
        }
    }

    ConsumedResult execute(Procedure procedure, DynamicMessage request) throws IOException {
        GrpcClientAdapter adapter = channelAdapter;
        if (adapter == null) {
            throw new IllegalStateException(where() + " is not started");
        }
        try {
            DynamicMessage response = ClientCalls.blockingUnaryCall(adapter.getChannel(),
                    procedure.call(), CallOptions.DEFAULT.withDeadlineAfter(
                            procedure.spec().timeoutMillis(), TimeUnit.MILLISECONDS),
                    request);
            return print(procedure, response);
        } catch (StatusRuntimeException e) {
            return failure(procedure.view(), e.getStatus(), e);
        }
    }

    /**
     * @return the proto3 JSON of a response, or a 502 failure when it cannot be printed (such as
     *         a {@code google.protobuf.Any} packing a type the descriptor set does not define)
     */
    ContractResult print(Procedure procedure, DynamicMessage response) {
        String json;
        try {
            json = printer.print(response);
        } catch (InvalidProtocolBufferException e) {
            return ContractResult.failure(procedure.view(), 502, "The response of "
                    + procedure.method().getFullName() + " has no proto3 JSON: "
                    + bounded(String.valueOf(e.getMessage()), MAX_REASON), e, null);
        }
        return ContractResult.success(procedure.view(), JSON.readTree(json), false);
    }

    static ContractResult failure(ConsumedOperationView view, Status status, Throwable cause) {
        int code = httpStatus(status);
        String description = status.getDescription();
        String reason = description != null && !description.isBlank()
                ? bounded(description, MAX_REASON)
                : "gRPC status " + status.getCode().name();
        return ContractResult.failure(view, code, reason, cause, status.getCode().name());
    }

    /** The AIP-193 mapping of a gRPC status to HTTP, with 0 when no connection was made. */
    static int httpStatus(Status status) {
        return switch (status.getCode()) {
            case OK -> 200;
            case CANCELLED -> 499;
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> 400;
            case UNAUTHENTICATED -> 401;
            case PERMISSION_DENIED -> 403;
            case NOT_FOUND -> 404;
            case ALREADY_EXISTS, ABORTED -> 409;
            case RESOURCE_EXHAUSTED -> 429;
            case UNIMPLEMENTED -> 501;
            case UNAVAILABLE -> connectionFailure(status) ? 0 : 503;
            case DEADLINE_EXCEEDED -> 504;
            default -> 500;
        };
    }

    static boolean connectionFailure(Status status) {
        for (Throwable t = status.getCause(); t != null; t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    /** Reads and writes dynamic messages of one type, as binary protobuf. */
    static final class DynamicMarshaller
            implements io.grpc.MethodDescriptor.Marshaller<DynamicMessage> {

        private final Descriptor type;

        DynamicMarshaller(Descriptor type) {
            this.type = type;
        }

        @Override
        public InputStream stream(DynamicMessage value) {
            return value.toByteString().newInput();
        }

        @Override
        public DynamicMessage parse(InputStream stream) {
            try {
                return DynamicMessage.parseFrom(type, stream);
            } catch (IOException e) {
                throw Status.INTERNAL.withDescription("Invalid " + type.getFullName()
                        + " message").withCause(e).asRuntimeException();
            }
        }
    }
}
