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
package io.ikanos.client.graphql;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.language.Document;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.OperationDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.VariableDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.ext.graphql.GraphqlClientAdapter;
import org.restlet.ext.graphql.GraphqlTransportException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import io.ikanos.engine.consumes.http.ConsumedAuthentication;
import io.ikanos.engine.consumes.http.StandardRequestHeaders;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.graphql.GraphqlClientSpec;
import io.ikanos.spec.consumes.graphql.GraphqlOperationSpec;
import io.ikanos.spec.consumes.graphql.GraphqlSelectSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The {@code consumes: type: graphql} adapter: static GraphQL documents sent to one endpoint
 * through Restlet's {@link GraphqlClientAdapter}, with the step's values as variables.
 *
 * <p>When the capability loads, every document is parsed (and validated against the
 * {@code schema} when there is one), and every input is matched to a variable of the documents,
 * by the first segment of its {@code wireName}. A call writes the inputs into the variables
 * ({@link ContractRequest}), keeps only the variables the chosen document declares, sends it, and
 * returns {@code data} as the result document. A response with {@code errors} and no
 * {@code data} (or only null top-level fields) fails with the status of its first error; with
 * some {@code data}, it fails unless {@code partialData: allow}.</p>
 */
public class GraphqlConsumedAdapter extends ContractClientAdapter {

    private static final Logger LOGGER = Logger.getLogger(GraphqlConsumedAdapter.class.getName());
    private static final ObjectMapper JSON = IkanosMappers.json();

    static final AttributeKey<String> ATTR_OPERATION_TYPE =
            AttributeKey.stringKey("graphql.operation.type");
    static final AttributeKey<String> ATTR_OPERATION_NAME =
            AttributeKey.stringKey("graphql.operation.name");

    /** Largest reason taken from an upstream error message. */
    static final int MAX_REASON = 200;

    /** One parsed document of an operation. */
    record ParsedDocument(String key, String text, Document document, String operationName,
            OperationDefinition.Operation type, Map<String, VariableDefinition> variables) {
    }

    /**
     * One operation, ready to call. {@code inputs} lists, per document, the inputs it takes: those
     * whose path starts with one of its variables, and is valid there when there is a schema.
     */
    record Operation(String name, GraphqlOperationSpec spec, Map<String, ParsedDocument> documents,
            Map<String, List<InputParameterSpec>> inputs, GraphqlSelectSpec select,
            ConsumedOperationView view, boolean readOnly) {
    }

    private final String endpoint;
    private final GraphQLSchema schema;
    private final Map<String, Operation> operations;
    private volatile GraphqlClientAdapter client;
    private volatile Client restletClient;

    public GraphqlConsumedAdapter(Capability capability, GraphqlClientSpec spec,
            Map<String, Tunnel> tunnels) {
        super(capability, spec, tunnels);
        this.endpoint = resolveUri("endpoint", spec.getEndpoint());
        if (this.endpoint == null || this.endpoint.isBlank()) {
            throw new IllegalArgumentException(where() + " requires endpoint");
        }
        this.schema = spec.getSchema() != null ? loadSchema(spec.getSchema()) : null;
        Map<String, Operation> built = new LinkedHashMap<>();
        for (GraphqlOperationSpec operation : spec.getOperations().values()) {
            built.put(operation.getName(), buildOperation(operation));
        }
        this.operations = Collections.unmodifiableMap(built);
    }

    /** @return the resolved endpoint */
    public String getEndpoint() {
        return endpoint;
    }

    GraphQLSchema schema() {
        return schema;
    }

    Map<String, Operation> operations() {
        return operations;
    }

    private GraphQLSchema loadSchema(String path) {
        String sdl = new String(readContract("schema", path), StandardCharsets.UTF_8);
        try {
            return UnExecutableSchemaGenerator.makeUnExecutableSchema(new SchemaParser().parse(sdl));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where() + " schema '" + path
                    + "' is not a valid GraphQL SDL: " + bounded(e.getMessage(), 300), e);
        }
    }

    Operation buildOperation(GraphqlOperationSpec spec) {
        String where = where() + " operation '" + spec.getName() + "'";
        Map<String, String> texts = new LinkedHashMap<>();
        boolean single = spec.getDocument() != null && !spec.getDocument().isBlank();
        if (single == !spec.getDocuments().isEmpty()) {
            throw new IllegalArgumentException(where
                    + " requires exactly one of document and documents");
        }
        GraphqlSelectSpec select = spec.getSelect();
        if (single) {
            if (select != null) {
                throw new IllegalArgumentException(where + " declares select without documents");
            }
            texts.put("document", spec.getDocument());
        } else {
            if (select == null || select.getWhen() == null || select.getThen() == null
                    || select.getElse() == null) {
                throw new IllegalArgumentException(where
                        + " requires select { when, then, else } with documents");
            }
            for (String chosen : List.of(select.getThen(), select.getElse())) {
                if (!spec.getDocuments().containsKey(chosen)) {
                    throw new IllegalArgumentException(where + " selects document '" + chosen
                            + "', which it does not declare");
                }
            }
            texts.putAll(spec.getDocuments());
        }
        Map<String, ParsedDocument> documents = new LinkedHashMap<>();
        boolean readOnly = true;
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            ParsedDocument parsed = parse(where, entry.getKey(), entry.getValue(),
                    spec.getOperationName());
            documents.put(entry.getKey(), parsed);
            readOnly &= parsed.type() == OperationDefinition.Operation.QUERY;
        }
        if (select != null && inputNamed(spec, select.getWhen()) == null) {
            throw new IllegalArgumentException(where + " selects on input '" + select.getWhen()
                    + "', which it does not declare");
        }
        Map<String, List<InputParameterSpec>> inputs = checkInputs(where, spec, documents, select);
        checkReadOnly(spec.getName(), readOnly, false);
        boolean effective = spec.getReadOnly() != null ? spec.getReadOnly() : readOnly;
        return new Operation(spec.getName(), spec, Collections.unmodifiableMap(documents), inputs,
                select, view(spec), effective);
    }

    ParsedDocument parse(String where, String key, String text, String operationName) {
        if (text.contains("{{")) {
            throw new IllegalArgumentException(where + " document '" + key + "' contains '{{':"
                    + " documents are static, values travel as variables");
        }
        Document document;
        try {
            document = Parser.parse(ParserEnvironment.newParserEnvironment().document(text)
                    .parserOptions(ParserOptions.getDefaultOperationParserOptions()).build());
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException(where + " document '" + key
                    + "' is not valid GraphQL: " + bounded(e.getMessage(), 300), e);
        }
        List<OperationDefinition> definitions =
                document.getDefinitionsOfType(OperationDefinition.class);
        OperationDefinition chosen = null;
        if (operationName != null) {
            for (OperationDefinition definition : definitions) {
                if (operationName.equals(definition.getName())) {
                    chosen = definition;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException(where + " document '" + key
                        + "' defines no operation named '" + operationName + "'");
            }
        } else if (definitions.size() == 1) {
            chosen = definitions.get(0);
        } else {
            throw new IllegalArgumentException(where + " document '" + key + "' defines "
                    + definitions.size() + " operations: declare operationName");
        }
        if (chosen.getOperation() == OperationDefinition.Operation.SUBSCRIPTION) {
            throw new IllegalArgumentException(where + " document '" + key
                    + "' is a subscription, which consumes type graphql does not run");
        }
        if (schema != null) {
            List<ValidationError> errors =
                    new Validator().validateDocument(schema, document, Locale.ROOT);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(where + " document '" + key
                        + "' does not validate against the schema: "
                        + bounded(errors.get(0).getMessage(), 300));
            }
        }
        Map<String, VariableDefinition> variables = new LinkedHashMap<>();
        for (VariableDefinition variable : chosen.getVariableDefinitions()) {
            variables.put(variable.getName(), variable);
        }
        return new ParsedDocument(key, text, document, chosen.getName(), chosen.getOperation(),
                Collections.unmodifiableMap(variables));
    }

    Map<String, List<InputParameterSpec>> checkInputs(String where, GraphqlOperationSpec spec,
            Map<String, ParsedDocument> documents, GraphqlSelectSpec select) {
        Map<String, List<InputParameterSpec>> inputs = new LinkedHashMap<>();
        Map<String, Set<String>> covered = new LinkedHashMap<>();
        Map<String, UnionChoices> unions = new LinkedHashMap<>();
        documents.keySet().forEach(key -> {
            inputs.put(key, new java.util.ArrayList<>());
            covered.put(key, new LinkedHashSet<>());
            unions.put(key, new UnionChoices(where + " document '" + key + "'"));
        });
        for (InputParameterSpec input : spec.getInputParameters()) {
            List<String> path = ContractRequest.path(input);
            boolean used = false;
            String invalid = null;
            for (ParsedDocument document : documents.values()) {
                VariableDefinition variable = document.variables().get(path.get(0));
                if (variable == null) {
                    continue;
                }
                List<String[]> oneOfs = new java.util.ArrayList<>();
                if (validPath(path, variable, oneOfs)) {
                    for (String[] oneOf : oneOfs) {
                        unions.get(document.key()).choose(input.getName(),
                                path.subList(0, Integer.parseInt(oneOf[0])), oneOf[1], oneOf[2]);
                    }
                    used = true;
                    inputs.get(document.key()).add(input);
                    covered.get(document.key()).add(path.get(0));
                } else {
                    invalid = String.join(".", path);
                }
            }
            boolean selector = select != null && input.getName().equals(select.getWhen());
            if (!used && invalid != null) {
                throw new IllegalArgumentException(where + " input '" + input.getName()
                        + "' targets '" + invalid + "', which does not resolve in the schema");
            }
            if (!used && !selector) {
                throw new IllegalArgumentException(where + " input '" + input.getName()
                        + "' targets '" + path.get(0) + "', which no document declares as a"
                        + " variable");
            }
        }
        for (ParsedDocument document : documents.values()) {
            for (VariableDefinition variable : document.variables().values()) {
                if (variable.getType() instanceof NonNullType && variable.getDefaultValue() == null
                        && !covered.get(document.key()).contains(variable.getName())) {
                    throw new IllegalArgumentException(where + " document '" + document.key()
                            + "' requires variable $" + variable.getName()
                            + ", which no input fills");
                }
            }
        }
        inputs.replaceAll((key, list) -> List.copyOf(list));
        return Collections.unmodifiableMap(inputs);
    }

    /**
     * Whether a nested path exists under a variable's input type; always without a schema. Each
     * field of a {@code @oneOf} input object the path goes through is added to {@code oneOfs} as
     * {index of the segment, input type, field}, for {@link UnionChoices}.
     */
    boolean validPath(List<String> path, VariableDefinition variable, List<String[]> oneOfs) {
        if (schema == null || path.size() == 1) {
            return true;
        }
        GraphQLType type = schema.getType(typeName(variable.getType()));
        for (int i = 1; i < path.size(); i++) {
            String segment = path.get(i);
            GraphQLType unwrapped = unwrap(type);
            if (!(unwrapped instanceof GraphQLInputObjectType object)
                    || object.getField(segment) == null) {
                return false;
            }
            if (object.isOneOf()) {
                oneOfs.add(new String[] {String.valueOf(i), object.getName(), segment});
            }
            GraphQLInputType fieldType = object.getField(segment).getType();
            type = fieldType;
        }
        return true;
    }

    private static GraphQLType unwrap(GraphQLType type) {
        GraphQLType current = type;
        while (current instanceof GraphQLNonNull || current instanceof GraphQLList) {
            current = current instanceof GraphQLNonNull nonNull ? nonNull.getWrappedType()
                    : ((GraphQLList) current).getWrappedType();
        }
        return current;
    }

    private static String typeName(Type<?> type) {
        Type<?> current = type;
        while (!(current instanceof TypeName)) {
            current = current instanceof NonNullType nonNull ? nonNull.getType()
                    : ((ListType) current).getType();
        }
        return ((TypeName) current).getName();
    }

    private static InputParameterSpec inputNamed(GraphqlOperationSpec spec, String name) {
        for (InputParameterSpec input : spec.getInputParameters()) {
            if (input.getName().equals(name)) {
                return input;
            }
        }
        return null;
    }

    @Override
    protected boolean contractReadOnly(String operationName) {
        Operation operation = operations != null ? operations.get(operationName) : null;
        if (operation == null) {
            return false;
        }
        return operation.documents().values().stream()
                .allMatch(d -> d.type() == OperationDefinition.Operation.QUERY);
    }

    @Override
    public void start() throws Exception {
        Client created = newRestletClient(endpoint,
                ConsumedAuthentication.clientContext(getContractSpec().getAuthentication()),
                List.of(Protocol.HTTP, Protocol.HTTPS));
        created.start();
        GraphqlClientAdapter adapter = new GraphqlClientAdapter();
        adapter.setClient(created);
        adapter.setEndpoint(endpoint);
        if (schema != null) {
            adapter.setSchema(schema);
        }
        adapter.setRequestCustomizer(this::customize);
        adapter.start();
        this.restletClient = created;
        this.client = adapter;
    }

    @Override
    public void stop() throws Exception {
        GraphqlClientAdapter adapter = client;
        client = null;
        if (adapter != null) {
            adapter.stop();
        }
        Client created = restletClient;
        restletClient = null;
        if (created != null) {
            created.stop();
        }
    }

    /**
     * Adds the static headers, the credential and the trace context to each request: the declared
     * credential comes after the static headers, so that it owns {@code Authorization}.
     */
    void customize(Request request) {
        staticHeaders().forEach((name, value) -> StandardRequestHeaders.set(request, name, value));
        ConsumedAuthentication.apply(getContractSpec().getAuthentication(), null, request,
                endpoint, bindings());
        OtelRestletBridge.injectContext(request);
    }

    @Override
    public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
        Operation operation = operations.get(operationName);
        if (operation == null) {
            return null;
        }
        String where = getNamespace() + "." + operationName;
        // Every input first, for the required checks and the selector; then the chosen
        // document's own inputs, so that another document's fields are never sent
        ContractRequest all = ContractRequest.build(operation.spec().getInputParameters(),
                parameters, where);
        ParsedDocument document = choose(operation, all);
        ContractRequest request = ContractRequest.build(operation.inputs().get(document.key()),
                parameters, where);
        Map<String, Object> variables = variables(document, request.tree());
        checkRequiredVariables(document, variables, where);
        String type = document.type().name().toLowerCase(Locale.ROOT);
        Map<AttributeKey<String>, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_OPERATION_TYPE, type);
        attributes.put(ATTR_OPERATION_NAME, document.operationName());
        return new ContractInvocation(getNamespace(), operation.view(),
                new ClientSpanDescriptor(type + " " + operationName, endpoint), attributes,
                () -> execute(operation, document, variables));
    }

    static ParsedDocument choose(Operation operation, ContractRequest request) {
        if (operation.select() == null) {
            return operation.documents().values().iterator().next();
        }
        String key = request.present().contains(operation.select().getWhen())
                ? operation.select().getThen() : operation.select().getElse();
        return operation.documents().get(key);
    }

    /** Keeps the variables the document declares: the other document's are not coerced. */
    static Map<String, Object> variables(ParsedDocument document, ObjectNode tree) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (String name : document.variables().keySet()) {
            JsonNode value = tree.get(name);
            if (value != null) {
                variables.put(name, JSON.convertValue(value, Object.class));
            }
        }
        return variables;
    }

    /**
     * Refuses the call before it is sent when a non-null variable without a default has no value:
     * the input that fills it may be optional.
     */
    static void checkRequiredVariables(ParsedDocument document, Map<String, Object> variables,
            String where) {
        for (VariableDefinition variable : document.variables().values()) {
            if (variable.getType() instanceof NonNullType && variable.getDefaultValue() == null
                    && variables.get(variable.getName()) == null) {
                throw new IllegalArgumentException("Missing value for variable $"
                        + variable.getName() + " of " + where);
            }
        }
    }

    ConsumedResult execute(Operation operation, ParsedDocument document,
            Map<String, Object> variables) {
        GraphqlClientAdapter adapter = client;
        if (adapter == null) {
            throw new IllegalStateException(where() + " is not started");
        }
        int timeout = operation.spec().timeoutMillis();
        // Restlet's GraphQL client has no per-call timeout: the call runs on a virtual thread,
        // interrupted when timeoutMs elapses. The call carries the caller's OpenTelemetry
        // context, so that customize() sends its trace context
        Callable<ExecutionResult> call =
                () -> adapter.execute(document.text(), document.operationName(), variables);
        Future<ExecutionResult> future = CALLS.submit(Context.current().wrap(call));
        try {
            ExecutionResult result = future.get(timeout, TimeUnit.MILLISECONDS);
            return toResult(operation, result);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ContractResult.failure(operation.view(), 504,
                    "The GraphQL call timed out after " + timeout + " ms", e, null);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ContractResult.failure(operation.view(), 0, "The GraphQL call was interrupted",
                    e, null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof GraphqlTransportException transport) {
                return transportFailure(operation, transport);
            }
            return ContractResult.failure(operation.view(), 0, "The GraphQL call failed",
                    cause != null ? cause : e, null);
        }
    }

    /** Runs each call on its own virtual thread, so that a timeout can interrupt it. */
    private static final ExecutorService CALLS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ikanos-graphql-call-", 0).factory());

    /**
     * A transport failure: 0 when no response came, the endpoint's status for a 4xx or 5xx, and
     * 502 for a response below 400 that is not a usable GraphQL result (an HTML page from a proxy,
     * a JSON body with neither {@code data} nor {@code errors}, a response over the size limit),
     * which must not read as a success.
     */
    static ContractResult transportFailure(Operation operation, GraphqlTransportException e) {
        Status status = e.getStatus();
        int answered = status == null || status.isConnectorError() ? 0 : status.getCode();
        if (answered == 0) {
            return ContractResult.failure(operation.view(), 0, "No GraphQL response was received",
                    e, null);
        }
        if (answered < 400) {
            return ContractResult.failure(operation.view(), 502, "The GraphQL endpoint answered "
                    + answered + " without a usable GraphQL result: "
                    + bounded(e.getMessage(), MAX_REASON), e, null);
        }
        return ContractResult.failure(operation.view(), answered,
                "The GraphQL endpoint answered " + answered, e, null);
    }

    ContractResult toResult(Operation operation, ExecutionResult result) {
        List<GraphQLError> errors = result.getErrors() != null ? result.getErrors() : List.of();
        Object data = result.getData();
        if (errors.isEmpty() && data != null) {
            return ContractResult.success(operation.view(), JSON.valueToTree(data), false);
        }
        if (errors.isEmpty()) {
            return ContractResult.failure(operation.view(), 502,
                    "The GraphQL response has neither data nor errors", null, null);
        }
        GraphQLError first = errors.get(0);
        boolean hasData = hasFieldData(data);
        if (hasData && operation.spec().allowsPartialData()) {
            LOGGER.warning(() -> "GraphQL operation " + getNamespace() + "." + operation.name()
                    + " returned partial data with " + errors.size() + " error(s): "
                    + bounded(first.getMessage(), MAX_REASON));
            return ContractResult.success(operation.view(), JSON.valueToTree(data), false);
        }
        int status = hasData ? 502 : statusOf(errors);
        return ContractResult.failure(operation.view(), status,
                bounded(first.getMessage(), MAX_REASON), null, codeOf(first));
    }

    /**
     * Whether a response with errors carries partial data: at least one top-level field is not
     * null. {@code {"data": {"field": null}}} is no data, and its errors decide the status.
     */
    static boolean hasFieldData(Object data) {
        if (data instanceof Map<?, ?> fields) {
            return fields.values().stream().anyMatch(Objects::nonNull);
        }
        return data != null;
    }

    static int statusOf(List<GraphQLError> errors) {
        String code = codeOf(errors.get(0));
        if (code == null) {
            return 502;
        }
        return switch (code) {
            case "UNAUTHENTICATED" -> 401;
            case "FORBIDDEN" -> 403;
            case "BAD_USER_INPUT", "GRAPHQL_VALIDATION_FAILED" -> 400;
            default -> 502;
        };
    }

    static String codeOf(GraphQLError error) {
        Map<String, Object> extensions = error.getExtensions();
        Object code = extensions != null ? extensions.get("code") : null;
        return code != null ? code.toString() : null;
    }
}
