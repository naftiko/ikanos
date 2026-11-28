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
package io.ikanos.spec.openapi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.headers.Header;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.OperationStepSpec;
import java.util.HashSet;
import java.util.Set;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.servers.Server;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.aggregates.RefInputs;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.ikanos.spec.exposes.rest.RestPaths;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Builds an OpenAPI {@link OpenAPI} POJO from a Ikanos capability spec's REST adapter.
 */
public class OasExportBuilder {

    /**
     * A REST exposed parameter cannot declare {@code required} in the schema, so the export keeps
     * treating it as required. Only MCP tool inputs are optional by default (#977).
     */
    static final boolean REST_PARAMETER_REQUIRED_BY_DEFAULT = true;

    /**
     * Build an OpenAPI document from an Ikanos specification's REST adapter.
     * Defaults to OAS 3.0.3.
     *
     * @param IkanosSpec the full Ikanos specification
     * @param adapterNamespace the namespace of the REST adapter to export (null for first)
     * @return export result containing the OpenAPI POJO and any warnings
     */
    public OasExportResult build(IkanosSpec IkanosSpec, String adapterNamespace) {
        return build(IkanosSpec, adapterNamespace, SpecVersion.V30);
    }

    /**
     * Build an OpenAPI document from an Ikanos specification's REST adapter.
     *
     * @param IkanosSpec the full Ikanos specification
     * @param adapterNamespace the namespace of the REST adapter to export (null for first)
     * @param specVersion the OAS version to produce (V30 or V31)
     * @return export result containing the OpenAPI POJO and any warnings
     */
    public OasExportResult build(IkanosSpec IkanosSpec, String adapterNamespace,
            SpecVersion specVersion) {
        List<String> warnings = new ArrayList<>();
        flowSteps = flowSteps(IkanosSpec);
        flowCalls = flowCalls(IkanosSpec);
        askingTools = askingTools(IkanosSpec);

        RestServerSpec restServer = findRestServer(IkanosSpec, adapterNamespace, warnings);
        if (restServer == null) {
            return new OasExportResult(new OpenAPI(), warnings);
        }

        OpenAPI openApi = new OpenAPI(specVersion);
        if (specVersion == SpecVersion.V31) {
            openApi.setOpenapi("3.1.0");
        } else {
            openApi.setOpenapi("3.0.3");
        }

        // Info
        openApi.setInfo(buildInfo(IkanosSpec, warnings));

        // Server
        openApi.setServers(buildServers(restServer));

        // Paths
        openApi.setPaths(buildPaths(restServer, flowsOf(IkanosSpec.getCapability()), warnings));

        // Security
        buildSecurity(restServer, openApi, warnings);

        return new OasExportResult(openApi, warnings);
    }

    RestServerSpec findRestServer(IkanosSpec IkanosSpec, String adapterNamespace,
            List<String> warnings) {
        CapabilitySpec capability = IkanosSpec.getCapability();
        if (capability == null || capability.getExposes() == null) {
            warnings.add("No capability or exposes section found");
            return null;
        }

        for (ServerSpec server : capability.getExposes()) {
            if (server instanceof RestServerSpec rest) {
                if (adapterNamespace == null || adapterNamespace.equals(rest.getNamespace())) {
                    return rest;
                }
            }
        }

        warnings.add("No REST adapter found"
                + (adapterNamespace != null ? " with namespace '" + adapterNamespace + "'" : ""));
        return null;
    }

    Info buildInfo(IkanosSpec IkanosSpec, List<String> warnings) {
        Info info = new Info();
        if (IkanosSpec.getInfo() != null) {
            if (IkanosSpec.getInfo().getDisplay() != null) {
                info.setTitle(IkanosSpec.getInfo().getDisplay());
            }
            if (IkanosSpec.getInfo().getDescription() != null) {
                info.setDescription(IkanosSpec.getInfo().getDescription());
            }
        }
        if (info.getTitle() == null) {
            info.setTitle("ikanos Capability");
            warnings.add("No info.display found; using default title");
        }
        info.setVersion("1.0.0");
        return info;
    }

    List<Server> buildServers(RestServerSpec restServer) {
        String address = restServer.getAddress();
        int port = restServer.getPort();

        // Normalize 0.0.0.0 to localhost
        if ("0.0.0.0".equals(address)) {
            address = "localhost";
        }
        if (address == null) {
            address = "localhost";
        }

        String url = "http://" + address + (port > 0 ? ":" + port : "");
        return List.of(new Server().url(url));
    }

    /**
     * The aggregate flows declared inline in a capability, keyed by their {@code ref}
     * ({@code namespace.name}). An imported aggregate is not resolved by the export, so its flows
     * are absent.
     */
    static Map<String, AggregateFlowSpec> flowsOf(CapabilitySpec capability) {
        Map<String, AggregateFlowSpec> flows = new LinkedHashMap<>();
        if (capability == null || capability.getAggregates() == null) {
            return flows;
        }
        for (AggregateSpec aggregate : capability.getAggregates().values()) {
            if (aggregate == null || aggregate.getFlows() == null) {
                continue;
            }
            for (AggregateFlowSpec flow : aggregate.getFlows().values()) {
                if (flow != null) {
                    flows.putIfAbsent(aggregate.getNamespace() + "." + flow.getName(), flow);
                }
            }
        }
        return flows;
    }

    Paths buildPaths(RestServerSpec restServer, List<String> warnings) {
        return buildPaths(restServer, Map.of(), warnings);
    }

    /**
     * @param flows the aggregate flows by {@code ref}, so that a {@code ref} operation that
     *        declares no inputs, nor its resource, exports its flow's inputs (#1039)
     */
    Paths buildPaths(RestServerSpec restServer, Map<String, AggregateFlowSpec> flows,
            List<String> warnings) {
        Paths paths = new Paths();

        for (RestServerResourceSpec resource : restServer.getResources().values()) {
            String basePath = pathKeyOf(resource);

            for (RestServerOperationSpec opSpec : resource.getOperations().values()) {
                List<InputParameterSpec> inherited = RefInputs.forRestOperation(resource, opSpec,
                        () -> {
                            AggregateFlowSpec flow = flows.get(opSpec.getRef());
                            if (flow == null) {
                                warnings.add("REST operation '" + opSpec.getName()
                                        + "' uses 'ref: " + opSpec.getRef()
                                        + "', whose flow is not declared inline: its inputs are"
                                        + " not exported");
                                return List.of();
                            }
                            return flow.getInputParameters().values();
                        });
                Operation operation = buildOperation(resource, opSpec, inherited);
                documentCacheControl(restServer, opSpec, operation);
                String pathKey = basePath;

                PathItem pathItem = paths.get(pathKey);
                if (pathItem == null) {
                    pathItem = new PathItem();
                    paths.addPathItem(pathKey, pathItem);
                }

                String method = opSpec.getMethod();
                if (method != null) {
                    setOperationOnPathItem(pathItem, method, operation);
                }
            }
        }

        return paths;
    }

    /**
     * Document the {@code Cache-Control} header the engine sends on the successful answers of a
     * {@code GET} operation whose {@code ttlMs} (or its adapter's) is set (#1021). An operation
     * that may ask its caller answers in rounds instead: its {@code 202} is always
     * {@code no-store}, whatever {@code ttlMs} and {@code cacheScope} say, and the ordinary policy
     * is never sent.
     */
    void documentCacheControl(RestServerSpec restServer, RestServerOperationSpec opSpec,
            Operation operation) {
        if (operation.getResponses() == null) {
            return;
        }
        if (operationMayAsk(opSpec)) {
            ApiResponse accepted = operation.getResponses().get("202");
            if (accepted != null) {
                accepted.addHeaderObject("Cache-Control", new Header()
                        .description("Always `no-store`, whatever `ttlMs` and `cacheScope` say:"
                                + " the `Location` handle is bound to this caller. Every answer"
                                + " on the handle is `no-store` too.")
                        .schema(new StringSchema()));
            }
            return;
        }
        if (!"GET".equalsIgnoreCase(opSpec.getMethod())) {
            return;
        }
        Integer ttlMs = opSpec.getTtlMs() != null ? opSpec.getTtlMs() : restServer.getTtlMs();
        if (ttlMs == null) {
            return;
        }
        String scope = opSpec.getCacheScope() != null ? opSpec.getCacheScope()
                : restServer.getCacheScope() != null ? restServer.getCacheScope() : "private";
        String value;
        if (ttlMs == 0) {
            value = "`no-store`.";
        } else {
            String maxAge = "max-age=" + (long) Math.ceil(ttlMs / 1000.0);
            value = "auto".equals(scope)
                    ? "`public, " + maxAge + "` or `private, " + maxAge + "`, derived by the"
                            + " engine: private when the adapter authenticates its callers or the"
                            + " operation reads an input from outside the URL."
                    : "`" + scope + ", " + maxAge + "`.";
        }
        Header header = new Header()
                .description("Sent by the engine on successful answers: " + value)
                .schema(new StringSchema());
        operation.getResponses().forEach((status, response) -> {
            if (status.startsWith("2")) {
                response.addHeaderObject("Cache-Control", header);
            }
        });
    }

    /** The OpenAPI path of a resource, with Mustache segments {{var}} turned into {var}. */
    private static String pathKeyOf(RestServerResourceSpec resource) {
        String basePath = resource.getPath();
        if (basePath == null) {
            basePath = "/" + resource.getName();
        }
        return toOpenApiPath(basePath);
    }

    Operation buildOperation(RestServerResourceSpec resource, RestServerOperationSpec opSpec) {
        return buildOperation(resource, opSpec, List.of());
    }

    /**
     * @param inherited the flow inputs a {@code ref} operation inherits, already placed
     *        ({@link RefInputs#forRestOperation}); exported after the operation's own inputs
     */
    Operation buildOperation(RestServerResourceSpec resource, RestServerOperationSpec opSpec,
            List<InputParameterSpec> inherited) {
        Operation operation = new Operation();

        if (opSpec.getName() != null) {
            operation.setOperationId(opSpec.getName());
        }
        if (opSpec.getDescription() != null) {
            operation.setDescription(opSpec.getDescription());
        }
        if (resource.getName() != null) {
            operation.setTags(List.of(resource.getName()));
        }

        // Parameters
        List<Parameter> params = new ArrayList<>();
        RequestBody requestBody = null;
        List<String> bodyRequired = new ArrayList<>();
        List<InputParameterSpec> inputs = effectiveInputs(resource, opSpec);
        inputs.addAll(inherited);
        for (InputParameterSpec inputParam : inputs) {
            if ("body".equals(inputParam.getIn())) {
                if (requestBody == null) {
                    requestBody = new RequestBody();
                    Schema<?> bodySchema = typedSchema("object");
                    bodySchema.setProperties(new LinkedHashMap<>());
                    requestBody.setContent(new Content().addMediaType("application/json",
                            new MediaType().schema(bodySchema)));
                }
                // Add property to body schema
                Schema<?> propSchema = buildInputSchema(inputParam);
                @SuppressWarnings("unchecked")
                Schema<Object> bodySchema = (Schema<Object>) requestBody.getContent()
                        .get("application/json").getSchema();
                bodySchema.getProperties().put(inputParam.getName(), propSchema);
                if (inputParam.isRequiredOrDefault(REST_PARAMETER_REQUIRED_BY_DEFAULT)) {
                    bodyRequired.add(inputParam.getName());
                }
            } else {
                params.add(buildParameter(inputParam));
            }
        }
        addPathParameters(resource, params);

        if (!params.isEmpty()) {
            operation.setParameters(params);
        }
        if (requestBody != null) {
            if (!bodyRequired.isEmpty()) {
                @SuppressWarnings("unchecked")
                Schema<Object> bodySchema = (Schema<Object>) requestBody.getContent()
                        .get("application/json").getSchema();
                bodySchema.setRequired(bodyRequired);
            }
            asMultipartWhenItTakesFiles(requestBody, inputs);
            operation.setRequestBody(requestBody);
        }

        // Responses
        ApiResponses responses = new ApiResponses();
        if (opSpec.hasBinaryResponse()) {
            // Binary REST response contract (capability-binary-content.md §7.4): emit each declared
            // status code with its media types, mapping `binary: true` to
            // `schema: { type: string, format: binary }` and preserving inline structured schemas.
            buildBinaryResponses(opSpec, responses);
        } else if (opSpec.getOutputParameters() != null && !opSpec.getOutputParameters().isEmpty()) {
            Schema<?> responseSchema = buildOutputSchema(opSpec.getOutputParameters());
            responses.addApiResponse("200", new ApiResponse()
                    .description("Successful response")
                    .content(new Content().addMediaType("application/json",
                            new MediaType().schema(responseSchema))));
        } else {
            responses.addApiResponse("204", new ApiResponse()
                    .description("No content"));
        }
        if (operationMayAsk(opSpec)) {
            responses.addApiResponse("202", inputRequiredResponse());
        }
        operation.setResponses(responses);

        return operation;
    }

    /**
     * A body with a file input ({@code type: file}) is read from {@code multipart/form-data}, as
     * the REST adapter reads it: the same members, each a part, and each file a binary part whose
     * {@code encoding} lists the media types it accepts.
     */
    static void asMultipartWhenItTakesFiles(RequestBody requestBody,
            List<InputParameterSpec> inputs) {
        Map<String, Encoding> encodings = new LinkedHashMap<>();
        boolean takesFiles = false;
        for (InputParameterSpec input : inputs) {
            if ("body".equals(input.getIn()) && input.isFile()) {
                takesFiles = true;
                List<String> mediaTypes = input.getAccept().stream()
                        .filter(accepted -> !accepted.startsWith(".")).toList();
                if (!mediaTypes.isEmpty()) {
                    encodings.put(input.getName(),
                            new Encoding().contentType(String.join(", ", mediaTypes)));
                }
            }
        }
        if (!takesFiles) {
            return;
        }
        MediaType json = requestBody.getContent().get("application/json");
        MediaType multipart = new MediaType().schema(json.getSchema());
        if (!encodings.isEmpty()) {
            multipart.setEncoding(encodings);
        }
        requestBody.setContent(new Content().addMediaType("multipart/form-data", multipart));
    }

    /** Whether an operation may ask its caller for input, and so answers in rounds. */
    private boolean operationMayAsk(RestServerOperationSpec opSpec) {
        return opSpec.getRef() != null ? mayAsk(opSpec.getRef(), null, new HashSet<>())
                : targetAsks(callTarget(opSpec.getCall()), new HashSet<>())
                        || mayAsk(null, opSpec.getSteps(), new HashSet<>());
    }

    Parameter buildParameter(InputParameterSpec inputParam) {
        Parameter param = new Parameter();
        param.setName(inputParam.getName());
        param.setIn(inputParam.getIn() != null ? inputParam.getIn() : "query");
        // OpenAPI requires every path parameter to be required
        param.setRequired("path".equals(param.getIn())
                || inputParam.isRequiredOrDefault(REST_PARAMETER_REQUIRED_BY_DEFAULT));
        if ("query".equals(param.getIn()) && inputParam.isJsonInQuery()) {
            // One JSON value, as the adapter reads it, not form-encoded members (#1078)
            param.setContent(new Content().addMediaType("application/json",
                    new MediaType().schema(buildInputSchema(inputParam))));
        } else {
            param.setSchema(buildInputSchema(inputParam));
        }
        if (inputParam.getDescription() != null) {
            param.setDescription(inputParam.getDescription());
        }
        return param;
    }

    /**
     * Converts Mustache segments {@code {{var}}} to the OpenAPI form {@code {var}}, with the
     * placeholder rule the REST adapter routes with ({@link RestPaths}).
     */
    static String toOpenApiPath(String path) {
        return RestPaths.toUriTemplate(path);
    }

    /**
     * Adds a path parameter for each variable of the resource path that the operation does not
     * declare (#978): OpenAPI requires one per path template variable. The path may use the
     * Mustache form {@code {{name}}} or the URI template form {@code {name}}, read with
     * {@link RestPaths#placeholders}, the rule that places the path inputs a {@code ref}
     * operation inherits. A resource-level {@code in: path} declaration gives its type and
     * description; otherwise the parameter is a required string.
     */
    void addPathParameters(RestServerResourceSpec resource, List<Parameter> params) {
        if (resource.getPath() == null) {
            return;
        }
        for (String name : RestPaths.placeholders(resource.getPath())) {
            boolean present = params.stream()
                    .anyMatch(p -> "path".equals(p.getIn()) && name.equals(p.getName()));
            if (present) {
                continue;
            }
            InputParameterSpec declared = resource.getInputParameters().stream()
                    .filter(p -> "path".equals(p.getIn()) && name.equals(p.getName()))
                    .findFirst().orElse(null);
            if (declared != null) {
                params.add(buildParameter(declared));
            } else {
                params.add(new Parameter().name(name).in("path").required(true)
                        .schema(typedSchema("string")));
            }
        }
    }

    /** The steps of every flow of the exported capability, by {@code namespace.flow}. */
    private Map<String, Map<String, OperationStepSpec>> flowSteps = Map.of();

    /** The target of every flow's singleton {@code call:}, by {@code namespace.flow}. */
    private Map<String, String> flowCalls = Map.of();

    /** The consumed MCP tools declared {@code mayElicit: true}, as {@code namespace.tool}. */
    private Set<String> askingTools = Set.of();

    private static Map<String, String> flowCalls(IkanosSpec spec) {
        Map<String, String> calls = new LinkedHashMap<>();
        CapabilitySpec capability = spec.getCapability();
        if (capability == null || capability.getAggregates() == null) {
            return calls;
        }
        for (AggregateSpec aggregate : capability.getAggregates().values()) {
            if (aggregate == null || aggregate.getFlows() == null) {
                continue;
            }
            aggregate.getFlows().forEach((name, flow) -> {
                String target = flow != null ? callTarget(flow.getCall()) : null;
                if (target != null) {
                    calls.put(aggregate.getNamespace() + "." + name, target);
                }
            });
        }
        return calls;
    }

    private static Set<String> askingTools(IkanosSpec spec) {
        Set<String> tools = new HashSet<>();
        CapabilitySpec capability = spec.getCapability();
        if (capability == null || capability.getConsumes() == null) {
            return tools;
        }
        for (ClientSpec client : capability.getConsumes()) {
            if (client instanceof McpClientSpec mcp && mcp.getTools() != null) {
                mcp.getTools().forEach((name, tool) -> {
                    if (tool != null && tool.mayElicit()) {
                        tools.add(mcp.getNamespace() + "." + name);
                    }
                });
            }
        }
        return tools;
    }

    private static String callTarget(ServerCallSpec call) {
        return call != null ? call.getOperation() : null;
    }

    private static Map<String, Map<String, OperationStepSpec>> flowSteps(IkanosSpec spec) {
        Map<String, Map<String, OperationStepSpec>> steps = new LinkedHashMap<>();
        CapabilitySpec capability = spec.getCapability();
        if (capability == null || capability.getAggregates() == null) {
            return steps;
        }
        for (AggregateSpec aggregate : capability.getAggregates().values()) {
            if (aggregate == null || aggregate.getFlows() == null) {
                continue;
            }
            aggregate.getFlows().forEach((name, flow) -> {
                if (flow != null && flow.getSteps() != null) {
                    steps.put(aggregate.getNamespace() + "." + name, flow.getSteps());
                }
            });
        }
        return steps;
    }

    /**
     * Whether an operation may ask its caller for input: its steps, the flow it references, or
     * the target of its singleton {@code call:}, hold an {@code elicit} step or call a consumed
     * MCP tool declared {@code mayElicit: true}, directly or through the flows they call.
     */
    private boolean mayAsk(String ref, Map<String, OperationStepSpec> steps, Set<String> seen) {
        if (ref != null) {
            return seen.add(ref) && (targetAsks(flowCalls.get(ref), seen)
                    || mayAsk(null, flowSteps.get(ref), seen));
        }
        if (steps == null) {
            return false;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepElicitSpec) {
                return true;
            }
            if (step instanceof OperationStepCallSpec call
                    && targetAsks(call.getCall(), seen)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a call to {@code target}, a consumed MCP tool or a flow, may ask. */
    private boolean targetAsks(String target, Set<String> seen) {
        if (target == null) {
            return false;
        }
        return askingTools.contains(target)
                || ((flowSteps.containsKey(target) || flowCalls.containsKey(target))
                        && mayAsk(target, null, seen));
    }

    /**
     * The {@code 202} of an operation that asks its caller: the questions, as MCP's
     * {@code inputRequests}, and the {@code Location} to {@code POST} the answers to.
     */
    private ApiResponse inputRequiredResponse() {
        Schema<?> body = typedSchema("object");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("status", typedSchema("string").description("Always input_required"));
        properties.put("inputRequests", typedSchema("object").description(
                "One elicitation/create request per question, keyed by the step's path"));
        body.setProperties(properties);
        return new ApiResponse()
                .description("Input required: POST the answers to the Location, as "
                        + "{\"inputResponses\": {<key>: {\"action\": \"accept\", "
                        + "\"content\": {...}}}}")
                .addHeaderObject("Location", new Header()
                        .description("Where to POST the answers")
                        .schema(typedSchema("string")))
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(body)));
    }

    /**
     * Populate {@code responses} from a REST operation's declared response contract, mapping each
     * {@code content.<mediaType>.binary: true} to an OpenAPI {@code type: string, format: binary}
     * schema and copying any inline structured {@code schema} verbatim. Declared media types are
     * preserved exactly and binary responses are never collapsed to {@code application/json}
     * (§7.4).
     */
    void buildBinaryResponses(RestServerOperationSpec opSpec, ApiResponses responses) {
        for (Map.Entry<String, io.ikanos.spec.exposes.rest.RestResponseSpec> statusEntry
                : opSpec.getEffectiveResponses().entrySet()) {
            io.ikanos.spec.exposes.rest.RestResponseSpec responseSpec = statusEntry.getValue();
            ApiResponse apiResponse = new ApiResponse();
            apiResponse.setDescription(responseSpec.getDescription() != null
                    ? responseSpec.getDescription() : "Successful response");

            Map<String, io.ikanos.spec.exposes.rest.RestResponseContentSpec> contentMap =
                    responseSpec.getContent();
            if (contentMap != null && !contentMap.isEmpty()) {
                Content content = new Content();
                for (Map.Entry<String, io.ikanos.spec.exposes.rest.RestResponseContentSpec>
                        mediaEntry : contentMap.entrySet()) {
                    io.ikanos.spec.exposes.rest.RestResponseContentSpec contentSpec =
                            mediaEntry.getValue();
                    Schema<?> schema;
                    if (contentSpec != null && contentSpec.isBinary()) {
                        schema = typedSchema("string").format("binary");
                    } else if (contentSpec != null && contentSpec.getSchema() != null) {
                        schema = mapInlineSchema(contentSpec.getSchema());
                    } else {
                        schema = new Schema<>();
                    }
                    content.addMediaType(mediaEntry.getKey(), new MediaType().schema(schema));
                }
                apiResponse.setContent(content);
            }

            responses.addApiResponse(statusEntry.getKey(), apiResponse);
        }
    }

    /**
     * Best-effort mapping of an inline JSON-schema map (from a non-binary
     * {@code content.<mediaType>.schema}) into a Swagger {@link Schema}. Only the {@code type} and
     * {@code format} keywords are interpreted; all other keywords are ignored, as richer schema
     * mapping is out of scope for this blueprint phase.
     */
    Schema<?> mapInlineSchema(Map<String, Object> inline) {
        Schema<?> schema = new Schema<>();
        Object type = inline.get("type");
        if (type instanceof String typeStr) {
            setSchemaType(schema, typeStr);
        }
        Object format = inline.get("format");
        if (format instanceof String formatStr) {
            schema.setFormat(formatStr);
        }
        return schema;
    }

    /**
     * The inputs an operation accepts: its resource's inputs, which every operation of the resource
     * inherits, then its own. An operation input with the same name and location replaces the
     * resource input, so the operation's declaration wins.
     */
    static List<InputParameterSpec> effectiveInputs(RestServerResourceSpec resource,
            RestServerOperationSpec opSpec) {
        Map<String, InputParameterSpec> inputs = new LinkedHashMap<>();
        for (InputParameterSpec input : resource.getInputParameters()) {
            inputs.put(inputKey(input), input);
        }
        for (InputParameterSpec input : opSpec.getInputParameters()) {
            String key = inputKey(input);
            inputs.remove(key);
            inputs.put(key, input);
        }
        return new ArrayList<>(inputs.values());
    }

    private static String inputKey(InputParameterSpec input) {
        String in = input.getIn() != null ? input.getIn() : "query";
        return in + ":" + input.getName();
    }

    /**
     * The schema of an input parameter, with the JSON Schema subset an input can declare (an
     * aggregate flow input, which a {@code ref} operation inherits, declares the most): type,
     * {@code items}, {@code properties} and their {@code required} names,
     * {@code additionalProperties}, {@code enum}, {@code format}, {@code minimum},
     * {@code maximum} and {@code default}. An array always gets {@code items}, which OpenAPI 3.0
     * requires: the declared ones, or an empty schema (#1078).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    Schema<?> buildInputSchema(InputParameterSpec inputParam) {
        Schema schema = new Schema<>();
        String type = mapIkanosTypeToOas(inputParam.getType());
        setSchemaType(schema, type);
        if (inputParam.isFile()) {
            // A file part of a multipart body
            schema.setFormat("binary");
        }
        if (inputParam.getDescription() != null) {
            schema.setDescription(inputParam.getDescription());
        }
        if ("array".equals(type)) {
            schema.setItems(inputParam.getItems() != null
                    ? buildInputSchema(inputParam.getItems()) : new Schema<>());
        }
        if (!inputParam.getProperties().isEmpty()) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            for (InputParameterSpec property : inputParam.getProperties()) {
                properties.put(property.getName(), buildInputSchema(property));
            }
            schema.setProperties(properties);
        }
        if (!inputParam.getRequired().isEmpty()) {
            schema.setRequired(new ArrayList<>(inputParam.getRequired()));
        }
        if (inputParam.getAdditionalProperties() != null) {
            schema.setAdditionalProperties(inputParam.getAdditionalProperties());
        }
        if (!inputParam.getEnumeration().isEmpty()) {
            schema.setEnum(inputParam.typedEnumeration());
        }
        if (inputParam.getFormat() != null) {
            schema.setFormat(inputParam.getFormat());
        }
        if (inputParam.getMinimum() != null) {
            schema.setMinimum(new BigDecimal(inputParam.getMinimum().toString()));
        }
        if (inputParam.getMaximum() != null) {
            schema.setMaximum(new BigDecimal(inputParam.getMaximum().toString()));
        }
        if (inputParam.getDefaultValue() != null) {
            // The value tools/list advertises, which a REST operation applies too
            schema.setDefault(IkanosMappers.json().convertValue(inputParam.getDefaultValue(),
                    Object.class));
        }
        return schema;
    }

    @SuppressWarnings("rawtypes")
    Schema<?> buildOutputSchema(List<OutputParameterSpec> outputParams) {
        Schema<?> schema = typedSchema("object");
        Map<String, Schema> properties = new LinkedHashMap<>();

        for (OutputParameterSpec outParam : outputParams) {
            Schema<?> propSchema = buildOutputPropertySchema(outParam);
            String name = outParam.getName() != null ? outParam.getName() : "value";
            properties.put(name, propSchema);
        }

        schema.setProperties(properties);
        return schema;
    }

    @SuppressWarnings("rawtypes")
    Schema<?> buildOutputPropertySchema(OutputParameterSpec outParam) {
        Schema<?> schema = new Schema<>();

        if ("object".equals(outParam.getType()) && outParam.getProperties() != null
                && !outParam.getProperties().isEmpty()) {
            setSchemaType(schema, "object");
            Map<String, Schema> nested = new LinkedHashMap<>();
            for (OutputParameterSpec child : outParam.getProperties()) {
                String name = child.getName() != null ? child.getName() : "value";
                nested.put(name, buildOutputPropertySchema(child));
            }
            schema.setProperties(nested);
        } else if ("array".equals(outParam.getType())) {
            setSchemaType(schema, "array");
            if (outParam.getItems() != null) {
                schema.setItems(buildOutputPropertySchema(outParam.getItems()));
            }
        } else {
            setSchemaType(schema, mapIkanosTypeToOas(outParam.getType()));
        }

        if (outParam.getDescription() != null) {
            schema.setDescription(outParam.getDescription());
        }

        return schema;
    }

    void buildSecurity(RestServerSpec restServer, OpenAPI openApi, List<String> warnings) {
        AuthenticationSpec auth = restServer.getAuthentication();
        if (auth == null) {
            return;
        }

        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }

        String schemeName;
        SecurityScheme securityScheme = new SecurityScheme();
        List<String> requiredScopes = List.of();

        if (auth instanceof BearerAuthenticationSpec) {
            schemeName = "bearerAuth";
            securityScheme.setType(SecurityScheme.Type.HTTP);
            securityScheme.setScheme("bearer");
        } else if (auth instanceof BasicAuthenticationSpec) {
            schemeName = "basicAuth";
            securityScheme.setType(SecurityScheme.Type.HTTP);
            securityScheme.setScheme("basic");
        } else if (auth instanceof DigestAuthenticationSpec) {
            schemeName = "digestAuth";
            securityScheme.setType(SecurityScheme.Type.HTTP);
            securityScheme.setScheme("digest");
        } else if (auth instanceof ApiKeyAuthenticationSpec apiKey) {
            schemeName = "apiKeyAuth";
            securityScheme.setType(SecurityScheme.Type.APIKEY);
            securityScheme.setName(apiKey.getKey());
            // A header unless the placement says query, as the engine reads it (#840)
            securityScheme.setIn(apiKey.isQueryPlacement()
                    ? SecurityScheme.In.QUERY : SecurityScheme.In.HEADER);
        } else if (auth instanceof OAuth2AuthenticationSpec oauth2) {
            String tokenUrl = oauth2.getTokenEndpoint();
            if (tokenUrl == null || tokenUrl.isBlank()) {
                tokenUrl = oauth2.getAuthorizationServerUri();
                if (tokenUrl != null && !tokenUrl.isBlank()) {
                    warnings.add("No tokenEndpoint set — falling back to authorizationServerUri "
                            + "as tokenUrl. This may be semantically incorrect; "
                            + "authorizationServerUri is the issuer URI, not the token endpoint.");
                }
            }
            if (tokenUrl == null || tokenUrl.isBlank()) {
                warnings.add("OAuth2 authentication requires tokenEndpoint or "
                        + "authorizationServerUri but both are missing or blank "
                        + "— skipping security scheme export");
                return;
            }
            schemeName = "oauth2Auth";
            securityScheme.setType(SecurityScheme.Type.OAUTH2);
            OAuthFlows flows = new OAuthFlows();
            OAuthFlow clientCredentials = new OAuthFlow();
            clientCredentials.setTokenUrl(tokenUrl);
            if (oauth2.getScopes() != null) {
                Scopes oasScopes = new Scopes();
                oauth2.getScopes().forEach(scope -> oasScopes.addString(scope, ""));
                clientCredentials.setScopes(oasScopes);
            }
            // The adapter's scopes are only the ones it recognizes; a call requires the scopes of
            // its 'authorization', exported per operation below when narrower than the adapter's (#999)
            if (restServer.getAuthorization() != null) {
                requiredScopes = restServer.getAuthorization().getScopes();
            }
            flows.setClientCredentials(clientCredentials);
            securityScheme.setFlows(flows);
        } else {
            warnings.add("Unsupported authentication type for export: " + auth.getClass().getSimpleName());
            return;
        }

        components.addSecuritySchemes(schemeName, securityScheme);
        openApi.setSecurity(List.of(new SecurityRequirement().addList(schemeName, requiredScopes)));
        if (auth instanceof OAuth2AuthenticationSpec) {
            buildOperationSecurity(restServer, openApi, schemeName);
        }
    }

    /**
     * Sets {@code security} on each operation whose resource or operation declares its own
     * {@code authorization}, with the scopes of the nearest one, so the export reads the same
     * inheritance as the engine. Required claims have no OpenAPI equivalent and are not exported.
     */
    void buildOperationSecurity(RestServerSpec restServer, OpenAPI openApi, String schemeName) {
        if (openApi.getPaths() == null) {
            return;
        }
        for (RestServerResourceSpec resource : restServer.getResources().values()) {
            PathItem pathItem = openApi.getPaths().get(pathKeyOf(resource));
            if (pathItem == null) {
                continue;
            }
            for (RestServerOperationSpec opSpec : resource.getOperations().values()) {
                AuthorizationSpec requirement = AuthorizationSpec.nearest(
                        restServer.getAuthorization(), resource.getAuthorization(), opSpec.getAuthorization());
                if (requirement == null || requirement == restServer.getAuthorization()
                        || opSpec.getMethod() == null) {
                    continue;
                }
                Operation operation = pathItem.readOperationsMap().get(
                        PathItem.HttpMethod.valueOf(opSpec.getMethod().toUpperCase()));
                if (operation != null) {
                    operation.setSecurity(List.of(new SecurityRequirement()
                            .addList(schemeName, requirement.getScopes())));
                }
            }
        }
    }

    private void setOperationOnPathItem(PathItem pathItem, String method, Operation operation) {
        switch (method.toUpperCase()) {
            case "GET" -> pathItem.setGet(operation);
            case "POST" -> pathItem.setPost(operation);
            case "PUT" -> pathItem.setPut(operation);
            case "DELETE" -> pathItem.setDelete(operation);
            case "PATCH" -> pathItem.setPatch(operation);
            case "HEAD" -> pathItem.setHead(operation);
            case "OPTIONS" -> pathItem.setOptions(operation);
        }
    }

    /**
     * Create a schema of the given type that serializes under both OAS 3.0 and 3.1.
     */
    private static Schema<Object> typedSchema(String type) {
        Schema<Object> schema = new Schema<>();
        setSchemaType(schema, type);
        return schema;
    }

    /**
     * Set a schema's type for both OAS 3.0 and 3.1. The 3.1 serializer writes {@code types} and
     * ignores {@code type}, so setting only {@code type} drops it from a 3.1 export.
     */
    private static void setSchemaType(Schema<?> schema, String type) {
        schema.setType(type);
        schema.setTypes(new LinkedHashSet<>(List.of(type)));
    }

    private String mapIkanosTypeToOas(String ikanosType) {
        if (ikanosType == null) {
            return "string";
        }
        return switch (ikanosType) {
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

}
