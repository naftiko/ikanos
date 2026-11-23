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
package io.ikanos.client.smithy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import io.opentelemetry.api.common.AttributeKey;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.data.Protocol;
import org.restlet.ext.smithy.SmithyClientAdapter;
import software.amazon.smithy.aws.traits.auth.SigV4Trait;
import software.amazon.smithy.java.auth.api.identity.IdentityResolver;
import software.amazon.smithy.java.aws.auth.api.identity.AwsCredentialsIdentity;
import software.amazon.smithy.java.aws.client.auth.scheme.sigv4.SigV4AuthScheme;
import software.amazon.smithy.java.aws.client.core.settings.RegionSetting;
import software.amazon.smithy.java.client.core.ClientProtocol;
import software.amazon.smithy.java.client.core.ClientProtocolFactory;
import software.amazon.smithy.java.client.core.ProtocolSettings;
import software.amazon.smithy.java.client.core.RequestOverrideConfig;
import software.amazon.smithy.java.client.core.auth.scheme.AuthSchemeOption;
import software.amazon.smithy.java.client.core.auth.scheme.AuthSchemeResolver;
import software.amazon.smithy.java.client.core.error.TransportException;
import software.amazon.smithy.java.client.core.error.TransportSocketTimeout;
import software.amazon.smithy.java.client.core.interceptors.ClientInterceptor;
import software.amazon.smithy.java.client.core.interceptors.RequestHook;
import software.amazon.smithy.java.client.core.interceptors.ResponseHook;
import software.amazon.smithy.java.client.http.HttpContext;
import software.amazon.smithy.java.core.error.CallException;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.Schema;
import software.amazon.smithy.java.core.schema.TraitKey;
import software.amazon.smithy.java.core.serde.document.Document;
import software.amazon.smithy.java.dynamicclient.DocumentException;
import software.amazon.smithy.java.dynamicclient.DynamicClient;
import software.amazon.smithy.java.dynamicschemas.SchemaConverter;
import software.amazon.smithy.java.dynamicschemas.StructDocument;
import software.amazon.smithy.java.http.api.HttpRequest;
import software.amazon.smithy.java.http.api.HttpResponse;
import software.amazon.smithy.java.http.api.ModifiableHttpRequest;
import software.amazon.smithy.java.retries.api.RetryStrategy;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.DefaultTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpErrorTrait;
import software.amazon.smithy.model.traits.ReadonlyTrait;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.Trait;
import tools.jackson.databind.JsonNode;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedInvocation.ClientSpanDescriptor;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.contract.ContractClientAdapter;
import io.ikanos.engine.consumes.contract.ContractInvocation;
import io.ikanos.engine.consumes.contract.ContractRequest;
import io.ikanos.engine.consumes.contract.ContractResult;
import io.ikanos.engine.consumes.http.ConsumedAuthentication;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.util.Secrets;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.AwsSigV4AuthenticationSpec;
import io.ikanos.spec.consumes.smithy.SmithyClientSpec;
import io.ikanos.spec.consumes.smithy.SmithyOperationSpec;

/**
 * The {@code consumes: type: smithy} adapter: operations of a service described by a local
 * Smithy model, called with smithy-java's {@link DynamicClient} (no generated code) in the
 * service's own protocol, through Restlet's {@link SmithyClientAdapter} transport.
 *
 * <p>When the capability loads, the model files are assembled (with the prelude and the trait
 * definitions on the class path), the service, its protocol and every operation are resolved, and
 * every input is matched to a member of the operation's input structure by its {@code wireName}
 * path; a required input member that no input fills is refused. {@code baseUri} is the endpoint:
 * any endpoint rule set of the model is ignored.</p>
 *
 * <p>A call writes the inputs into the input structure ({@link ContractRequest}), by member name,
 * and returns the output structure as the result document. A modeled error fails with its
 * {@code @httpError} status (400 for a client error without one, 502 for a server error), an
 * unmodeled error with the HTTP status received.</p>
 */
public class SmithyConsumedAdapter extends ContractClientAdapter {

    static final AttributeKey<String> ATTR_RPC_SYSTEM = AttributeKey.stringKey("rpc.system");
    static final AttributeKey<String> ATTR_RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    static final AttributeKey<String> ATTR_RPC_METHOD = AttributeKey.stringKey("rpc.method");
    static final AttributeKey<String> ATTR_PROTOCOL = AttributeKey.stringKey("smithy.protocol");

    /** Largest reason taken from an upstream error message. */
    static final int MAX_REASON = 200;

    /** The client protocols on the class path, by protocol trait. */
    private static final Map<ShapeId, ClientProtocolFactory<Trait>> PROTOCOLS = protocols();

    /**
     * One operation, ready to call: {@code inputSchema} guides the conversion of the request tree.
     */
    record Operation(String name, SmithyOperationSpec spec, OperationShape shape,
            Schema inputSchema, ConsumedOperationView view, boolean readOnly) {
    }

    /** An {@code awsSigV4} credential, resolved from the bindings. */
    record SigV4(String accessKeyId, String secretAccessKey, String sessionToken, String region,
            String service) {

        @Override
        public String toString() {
            return "SigV4[region=" + region + ", service=" + service + "]";
        }
    }

    private final String baseUri;
    private final Model model;
    private final ServiceShape service;
    private final ShapeId protocolId;
    private final ClientProtocol<?, ?> protocol;
    private final SigV4 sigV4;
    private final Map<String, Operation> operations;
    private volatile DynamicClient client;
    private volatile Client restletClient;

    public SmithyConsumedAdapter(Capability capability, SmithyClientSpec spec,
            Map<String, Tunnel> tunnels) {
        super(capability, spec, tunnels);
        this.baseUri = resolveUri("baseUri", spec.getBaseUri());
        if (this.baseUri == null || this.baseUri.isBlank()) {
            throw new IllegalArgumentException(where() + " requires baseUri");
        }
        this.model = loadModel(spec.getModel());
        this.service = findService(spec.getService());
        this.protocolId = findProtocol(spec.getProtocol());
        SchemaConverter converter = new SchemaConverter(model);
        this.protocol = PROTOCOLS.get(protocolId).createProtocol(ProtocolSettings.builder()
                .service(service.getId()).serviceVersion(service.getVersion())
                .serviceSchema(converter.getSchema(service)).build(),
                ServiceIndex.of(model).getProtocols(service).get(protocolId));
        this.sigV4 = credentials(spec.getAuthentication());
        Map<String, Operation> built = new LinkedHashMap<>();
        for (SmithyOperationSpec operation : spec.getOperations().values()) {
            built.put(operation.getName(), buildOperation(operation, converter));
        }
        this.operations = Collections.unmodifiableMap(built);
    }

    /** @return the resolved base URI */
    public String getBaseUri() {
        return baseUri;
    }

    Model model() {
        return model;
    }

    ServiceShape service() {
        return service;
    }

    ShapeId protocolId() {
        return protocolId;
    }

    Map<String, Operation> operations() {
        return operations;
    }

    SigV4 sigV4() {
        return sigV4;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<ShapeId, ClientProtocolFactory<Trait>> protocols() {
        Map<ShapeId, ClientProtocolFactory<Trait>> factories = new HashMap<>();
        for (ClientProtocolFactory factory : ServiceLoader.load(ClientProtocolFactory.class,
                ClientProtocolFactory.class.getClassLoader())) {
            factories.putIfAbsent(factory.id(), factory);
        }
        return Collections.unmodifiableMap(factories);
    }

    /** Assembles the model files, with the prelude and the trait definitions on the class path. */
    private Model loadModel(List<String> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException(where() + " requires model");
        }
        ClassLoader loader = SmithyConsumedAdapter.class.getClassLoader();
        ModelAssembler assembler = Model.assembler(loader).discoverModels(loader);
        for (String file : files) {
            Path path = contractPath("model", file);
            String text = new String(readContract("model", file), StandardCharsets.UTF_8);
            String name = path.toString();
            // The assembler tells IDL from JSON AST by the extension
            if (!name.endsWith(".json") && !name.endsWith(".smithy")) {
                name = text.stripLeading().startsWith("{") ? name + ".json" : name + ".smithy";
            }
            assembler.addUnparsedModel(name, text);
        }
        try {
            return assembler.assemble().unwrap();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where() + " model " + files
                    + " is not a valid Smithy model: " + bounded(e.getMessage(), 300), e);
        }
    }

    private ServiceShape findService(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(where() + " requires service");
        }
        ShapeId shapeId;
        try {
            shapeId = ShapeId.from(id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where() + " service '" + id
                    + "' is not a shape ID (namespace#Name)", e);
        }
        return model.getShape(shapeId).flatMap(Shape::asServiceShape)
                .orElseThrow(() -> new IllegalArgumentException(where()
                        + " model defines no service '" + id + "'"));
    }

    /**
     * The declared protocol, or the service's only one, which must have a client codec on the
     * class path. A protocol is declared by shape ID or by name ({@code restJson1}).
     */
    ShapeId findProtocol(String declared) {
        Set<ShapeId> traits = ServiceIndex.of(model).getProtocols(service).keySet();
        ShapeId chosen = null;
        if (declared != null && !declared.isBlank()) {
            for (ShapeId trait : traits) {
                if (trait.toString().equals(declared) || trait.getName().equals(declared)) {
                    chosen = trait;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException(where() + " service '" + service.getId()
                        + "' declares no protocol '" + declared + "' (it declares "
                        + new TreeSet<>(traits) + ")");
            }
        } else if (traits.size() == 1) {
            chosen = traits.iterator().next();
        } else if (traits.isEmpty()) {
            throw new IllegalArgumentException(where() + " service '" + service.getId()
                    + "' declares no protocol trait");
        } else {
            throw new IllegalArgumentException(where() + " service '" + service.getId()
                    + "' declares several protocols " + new TreeSet<>(traits)
                    + ": declare protocol");
        }
        if (!PROTOCOLS.containsKey(chosen)) {
            throw new IllegalArgumentException(where() + " protocol '" + chosen
                    + "' has no smithy-java client codec on the class path");
        }
        return chosen;
    }

    /** Resolves an {@code awsSigV4} credential; checks that another one can be sent as headers. */
    private SigV4 credentials(AuthenticationSpec authentication) {
        if (authentication == null) {
            return null;
        }
        if (!(authentication instanceof AwsSigV4AuthenticationSpec spec)) {
            credentialHeaders();
            return null;
        }
        String prefix = "authentication.";
        String accessKeyId = required(prefix + "accessKeyId", spec.getAccessKeyId());
        String secret = required(prefix + "secretAccessKey",
                Secrets.password(spec.getSecretAccessKey()));
        String token = spec.getSessionToken() != null
                ? resolve(prefix + "sessionToken", spec.getSessionToken()) : null;
        String region = required(prefix + "region", spec.getRegion());
        String name = spec.getService();
        if (name == null || name.isBlank()) {
            // The signing name of the model's @sigv4 trait, else the HTTP adapter's default
            name = service.getTrait(SigV4Trait.class).map(SigV4Trait::getName)
                    .orElse(AwsSigV4AuthenticationSpec.DEFAULT_SERVICE);
        }
        return new SigV4(accessKeyId, secret, token != null && !token.isBlank() ? token : null,
                region, resolve(prefix + "service", name));
    }

    private String required(String field, String template) {
        String value = template != null ? resolve(field, template) : null;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(where() + " " + field + " is empty");
        }
        return value;
    }

    Operation buildOperation(SmithyOperationSpec spec, SchemaConverter converter) {
        String where = where() + " operation '" + spec.getName() + "'";
        OperationShape shape = findOperation(where, spec.getOperation());
        StructureShape input = model.expectShape(shape.getInputShape(), StructureShape.class);
        checkInputs(where, spec, input);
        boolean readOnly = shape.hasTrait(ReadonlyTrait.class);
        checkReadOnly(spec.getName(), readOnly, false);
        boolean effective = spec.getReadOnly() != null ? spec.getReadOnly() : readOnly;
        return new Operation(spec.getName(), spec, shape, converter.getSchema(input), view(spec),
                effective);
    }

    /** An operation of the service, by shape name or by full shape ID. */
    OperationShape findOperation(String where, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(where + " requires operation");
        }
        Set<OperationShape> contained = TopDownIndex.of(model).getContainedOperations(service);
        for (OperationShape operation : contained) {
            if (reference.contains("#") ? operation.getId().toString().equals(reference)
                    : operation.getId().getName().equals(reference)) {
                return operation;
            }
        }
        throw new IllegalArgumentException(where + " targets '" + reference
                + "', which is no operation of service '" + service.getId() + "'");
    }

    /**
     * Checks that each input's {@code wireName} path names members of the input structure
     * (through nested structures, unions and map values), and that each required member without
     * a default is filled by an input.
     */
    void checkInputs(String where, SmithyOperationSpec spec, StructureShape input) {
        Set<String> covered = new LinkedHashSet<>();
        for (InputParameterSpec parameter : spec.getInputParameters()) {
            List<String> path = ContractRequest.path(parameter);
            if (!validPath(input, path)) {
                throw new IllegalArgumentException(where + " input '" + parameter.getName()
                        + "' targets '" + String.join(".", path) + "', which the input structure '"
                        + input.getId() + "' does not define");
            }
            covered.add(path.get(0));
        }
        List<String> uncovered = new ArrayList<>();
        for (MemberShape member : input.members()) {
            if (member.hasTrait(RequiredTrait.class) && !member.hasTrait(DefaultTrait.class)
                    && !covered.contains(member.getMemberName())) {
                uncovered.add(member.getMemberName());
            }
        }
        if (!uncovered.isEmpty()) {
            throw new IllegalArgumentException(where + " requires member(s) " + uncovered
                    + " of '" + input.getId() + "', which no input fills");
        }
    }

    boolean validPath(StructureShape input, List<String> path) {
        Shape current = input;
        for (String segment : path) {
            MemberShape member;
            if (current instanceof StructureShape || current instanceof UnionShape) {
                member = current.getMember(segment).orElse(null);
            } else if (current instanceof MapShape map) {
                member = map.getValue();
            } else {
                member = null;
            }
            if (member == null) {
                return false;
            }
            current = model.expectShape(member.getTarget());
        }
        return true;
    }

    @Override
    protected boolean contractReadOnly(String operationName) {
        Operation operation = operations != null ? operations.get(operationName) : null;
        return operation != null && operation.shape().hasTrait(ReadonlyTrait.class);
    }

    @Override
    public void start() throws Exception {
        Context context = ConsumedAuthentication.clientContext(
                getContractSpec().getAuthentication());
        // smithy-java answers redirects itself: a followed one would lose the signature
        context.getParameters().set("followRedirects", "false");
        Client created = newRestletClient(baseUri, context,
                List.of(Protocol.HTTP, Protocol.HTTPS));
        created.start();
        try {
            DynamicClient.Builder builder = DynamicClient.builder().model(model)
                    .serviceId(service.getId()).protocol(protocol)
                    .transport(new SmithyClientAdapter(created)).endpoint(baseUri)
                    // One attempt per call, within timeoutMs, as for the other consumes types
                    .retryStrategy(RetryStrategy.noRetries());
            if (sigV4 != null) {
                AwsCredentialsIdentity identity = sigV4.sessionToken() != null
                        ? AwsCredentialsIdentity.create(sigV4.accessKeyId(),
                                sigV4.secretAccessKey(), sigV4.sessionToken())
                        : AwsCredentialsIdentity.create(sigV4.accessKeyId(),
                                sigV4.secretAccessKey());
                builder.putSupportedAuthSchemes(new SigV4AuthScheme(sigV4.service()))
                        .addIdentityResolver(IdentityResolver.of(identity))
                        .putConfig(RegionSetting.REGION, sigV4.region())
                        // Signed whatever auth traits the model declares
                        .authSchemeResolver(params -> List.of(new AuthSchemeOption(SigV4Trait.ID)));
            } else {
                // The credential travels as headers, added to each request
                builder.authSchemeResolver(AuthSchemeResolver.NO_AUTH);
            }
            this.client = builder.build();
            this.restletClient = created;
        } catch (RuntimeException e) {
            created.stop();
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        DynamicClient dynamic = client;
        client = null;
        if (dynamic != null) {
            dynamic.close();
        }
        Client created = restletClient;
        restletClient = null;
        if (created != null) {
            created.stop();
        }
    }

    @Override
    public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
        Operation operation = operations.get(operationName);
        if (operation == null) {
            return null;
        }
        String where = getNamespace() + "." + operationName;
        ContractRequest request = ContractRequest.build(operation.spec().getInputParameters(),
                parameters, where);
        Document input = input(operation, request.tree(), where);
        String method = operation.shape().getId().getName();
        Map<AttributeKey<String>, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_RPC_SYSTEM, "smithy");
        attributes.put(ATTR_RPC_SERVICE, service.getId().toString());
        attributes.put(ATTR_RPC_METHOD, method);
        attributes.put(ATTR_PROTOCOL, protocolId.toString());
        return new ContractInvocation(getNamespace(), operation.view(),
                new ClientSpanDescriptor(method, baseUri), attributes,
                () -> execute(operation, input));
    }

    /** The input structure, typed by its schema; a value of the wrong type is refused here. */
    Document input(Operation operation, JsonNode tree, String where) {
        try {
            return StructDocument.of(operation.inputSchema(),
                    SmithyDocuments.toDocument(tree, operation.inputSchema()), service.getId());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid input for " + where + ": "
                    + bounded(e.getMessage(), MAX_REASON), e);
        }
    }

    ConsumedResult execute(Operation operation, Document input) {
        DynamicClient dynamic = client;
        if (dynamic == null) {
            throw new IllegalStateException(where() + " is not started");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (sigV4 == null) {
            headers.putAll(credentialHeaders());
        }
        headers.putAll(staticHeaders());
        headers.putAll(traceHeaders());
        CallHeaders call = new CallHeaders(headers);
        int timeout = operation.spec().timeoutMillis();
        // The transport aborts the request when no response head arrives within timeoutMs
        RequestOverrideConfig override = RequestOverrideConfig.builder().addInterceptor(call)
                .putConfig(HttpContext.HTTP_REQUEST_TIMEOUT, Duration.ofMillis(timeout)).build();
        try {
            Document output = dynamic.call(operation.shape().getId().getName(), input, override);
            return ContractResult.success(operation.view(), SmithyDocuments.toJson(output), true);
        } catch (ModeledException e) {
            return modeledFailure(operation, e);
        } catch (TransportSocketTimeout e) {
            return ContractResult.failure(operation.view(), 504,
                    "The Smithy call timed out after " + timeout + " ms", e, null);
        } catch (TransportException e) {
            return ContractResult.failure(operation.view(), 0, "No Smithy response was received",
                    e, null);
        } catch (CallException e) {
            int status = call.status() > 0 ? call.status() : 502;
            String reason = e.getMessage() != null && !e.getMessage().isBlank()
                    ? bounded(e.getMessage(), MAX_REASON)
                    : "The Smithy service answered " + status;
            return ContractResult.failure(operation.view(), status, reason, e, null);
        } catch (RuntimeException e) {
            int status = call.status() > 0 ? 502 : 0;
            return ContractResult.failure(operation.view(), status,
                    "The Smithy call failed: " + bounded(e.getMessage(), MAX_REASON), e, null);
        }
    }

    static ContractResult modeledFailure(Operation operation, ModeledException e) {
        Schema schema = e.schema();
        String reason = e.getMessage();
        if (e instanceof DocumentException document) {
            for (String name : List.of("message", "Message")) {
                Document member = document.getContents().getMember(name);
                if (member != null && member.type() == ShapeType.STRING) {
                    reason = member.asString();
                    break;
                }
            }
        }
        return ContractResult.failure(operation.view(), statusOf(schema),
                bounded(reason, MAX_REASON), e, schema.id().getName());
    }

    /** {@code @httpError}, else 400 for a client error and 502 for a server error. */
    static int statusOf(Schema schema) {
        HttpErrorTrait http = schema.getTrait(TraitKey.HTTP_ERROR_TRAIT);
        if (http != null) {
            return http.getCode();
        }
        ErrorTrait error = schema.getTrait(TraitKey.ERROR_TRAIT);
        return error != null && error.isClientError() ? 400 : 502;
    }

    /**
     * Adds the credential, the static headers and the trace context to the request of one call,
     * before it is signed, and records the HTTP status received.
     */
    static final class CallHeaders implements ClientInterceptor {

        private final Map<String, String> headers;
        private volatile int status;

        CallHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        int status() {
            return status;
        }

        @Override
        public <R> R modifyBeforeSigning(RequestHook<?, ?, R> hook) {
            if (headers.isEmpty() || !(hook.request() instanceof HttpRequest request)) {
                return hook.request();
            }
            ModifiableHttpRequest modifiable = request.toModifiable();
            headers.forEach(modifiable::setHeader);
            return hook.asRequestType(modifiable);
        }

        @Override
        public void readAfterTransmit(ResponseHook<?, ?, ?, ?> hook) {
            if (hook.response() instanceof HttpResponse response) {
                status = response.statusCode();
            }
        }
    }
}
