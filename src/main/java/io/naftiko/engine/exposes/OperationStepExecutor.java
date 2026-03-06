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
package io.naftiko.engine.exposes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.naftiko.Capability;
import io.naftiko.engine.LookupExecutor;
import io.naftiko.engine.Resolver;
import io.naftiko.engine.StepExecutionContext;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.engine.consumes.HttpClientAdapter;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.consumes.HttpClientOperationSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.exposes.ApiServerOperationSpec;
import io.naftiko.spec.exposes.ApiServerResourceSpec;
import io.naftiko.spec.exposes.ApiServerSpec;
import io.naftiko.spec.exposes.OperationStepSpec;
import io.naftiko.spec.exposes.OperationStepCallSpec;
import io.naftiko.spec.exposes.OperationStepLookupSpec;

/**
 * Executor for orchestrated operation steps.
 * 
 * Handles the common logic of executing a sequence of operation steps:
 * - Resolving and merging parameters at each step
 * - Finding the appropriate client adapter and operation
 * - Executing the client request
 * 
 * Used by both ApiResourceRestlet and McpToolHandler to avoid duplication.
 */
public class OperationStepExecutor {

    private final Capability capability;
    private final ObjectMapper mapper;

    public OperationStepExecutor(Capability capability) {
        this.capability = capability;
        this.mapper = new ObjectMapper();
    }

    /**
     * Build a map of input parameter values for a given request and operation by evaluating
     * server-level, resource-level and operation-level InputParameterSpec entries.
     */
    public Map<String, Object> resolveInputParametersFromRequest(Request request,
            ApiServerSpec serverSpec, ApiServerResourceSpec resourceSpec,
            ApiServerOperationSpec serverOp) {
        Map<String, Object> params = new ConcurrentHashMap<>();
        JsonNode tmpRoot = null;

        // Read request body once (may be null)
        try {
            if ((request.getEntity() != null) && !request.getEntity().isEmpty()) {
                tmpRoot = mapper.readTree(request.getEntity().getReader());
            }
        } catch (Exception e) {
            tmpRoot = null;
        }

        final JsonNode root = tmpRoot;

        // Server-level input parameters
        if (serverSpec != null && serverSpec.getInputParameters() != null) {
            for (InputParameterSpec spec : serverSpec.getInputParameters()) {
                Object val = Resolver.resolveInputParameterFromRequest(spec, request, root, mapper);

                if (val != null) {
                    params.put(spec.getName(), val);
                }
            }
        }

        // Resource-level input parameters
        if (resourceSpec != null && resourceSpec.getInputParameters() != null) {
            for (InputParameterSpec spec : resourceSpec.getInputParameters()) {
                Object v = Resolver.resolveInputParameterFromRequest(spec, request, root, mapper);
                if (v != null) {
                    params.put(spec.getName(), v);
                }
            }
        }

        // Operation-level input parameters override resource-level
        if (serverOp != null && serverOp.getInputParameters() != null) {
            for (InputParameterSpec spec : serverOp.getInputParameters()) {
                Object v = Resolver.resolveInputParameterFromRequest(spec, request, root, mapper);
                if (v != null) {
                    params.put(spec.getName(), v);
                }
            }
        }

        return params;
    }

    /**
     * Execute a sequence of orchestrated operation steps.
     * 
     * @param steps the list of operation steps to execute
     * @param baseParameters the base parameters for template resolution
     * @return the final HandlingContext from the last executed step, or null if no steps executed
     * @throws IllegalArgumentException if step execution fails
     */
    public StepExecutionResult executeSteps(List<OperationStepSpec> steps,
            Map<String, Object> baseParameters) {
        HandlingContext lastContext = null;
        StepExecutionContext stepContext = new StepExecutionContext();
        Map<String, Object> runtimeParameters = new ConcurrentHashMap<>();

        if (baseParameters != null) {
            runtimeParameters.putAll(baseParameters);
        }

        if (steps == null || steps.isEmpty()) {
            return new StepExecutionResult(lastContext, stepContext);
        }

        for (OperationStepSpec step : steps) {
            switch (step) {
                case OperationStepCallSpec callStep -> {
                    lastContext = executeCallStep(callStep, runtimeParameters);

                    if (lastContext == null) {
                        throw new IllegalArgumentException("Invalid call format: "
                                + (callStep.getCall() != null ? callStep.getCall() : "null"));
                    }

                    try {
                        lastContext.handle();
                    } catch (Exception e) {
                        throw new RuntimeException("Error while handling an HTTP client call", e);
                    }

                    // Store call output for lookup references when response is valid JSON
                    if (lastContext.clientResponse != null
                            && lastContext.clientResponse.getEntity() != null) {
                        try {
                            if (!(lastContext.clientResponse
                                    .getEntity() instanceof StringRepresentation)) {
                                lastContext.clientResponse.setEntity(new StringRepresentation(
                                        lastContext.clientResponse.getEntity().getText(),
                                        lastContext.clientResponse.getEntity().getMediaType()));
                            }

                            JsonNode rawOutput = mapper
                                    .readTree(lastContext.clientResponse.getEntity().getReader());
                            JsonNode stepOutput = resolveStepOutput(lastContext, rawOutput);
                            stepContext.storeStepOutput(callStep.getName(), stepOutput);
                            addStepOutputToParameters(runtimeParameters, callStep.getName(),
                                    stepOutput);
                        } catch (Exception ignoreJsonParseError) {
                            // Ignore non-JSON call output for lookup indexing
                        }
                    }
                }
                case OperationStepLookupSpec lookupStep -> {
                    JsonNode indexData = stepContext.getStepOutput(lookupStep.getIndex());

                    if (indexData == null) {
                        throw new IllegalArgumentException(
                                "Lookup step references non-existent step: "
                                        + lookupStep.getIndex());
                    }

                    String resolvedLookupValue = Resolver.resolveMustacheTemplate(
                            lookupStep.getLookupValue(), runtimeParameters);

                    JsonNode lookupResult = LookupExecutor.executeLookup(indexData,
                            lookupStep.getMatch(), resolvedLookupValue,
                            lookupStep.getOutputParameters());

                    if (lookupResult != null) {
                        stepContext.storeStepOutput(lookupStep.getName(), lookupResult);
                    }
                }
                default -> {
                    // Ignore unsupported step types
                }
            }
        }

        return new StepExecutionResult(lastContext, stepContext);
    }

    /**
     * Execute a single call step.
     */
    private HandlingContext executeCallStep(OperationStepCallSpec callStep,
            Map<String, Object> baseParameters) {
        // Merge step-level 'with' parameters with base parameters
        Map<String, Object> stepParams = new ConcurrentHashMap<>(baseParameters);

        if (callStep.getWith() != null) {
            for (Map.Entry<String, Object> entry : callStep.getWith().entrySet()) {
                Object rawValue = entry.getValue();
                if (rawValue instanceof String rawStringValue) {
                    stepParams.put(entry.getKey(),
                            Resolver.resolveMustacheTemplate(rawStringValue, stepParams));
                } else {
                    stepParams.put(entry.getKey(), rawValue);
                }
            }
        }

        if (callStep.getCall() != null) {
            String[] tokens = callStep.getCall().split("\\.");
            if (tokens.length == 2) {
                try {
                    return findClientRequestFor(tokens[0], tokens[1], stepParams);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Error resolving request parameters: " + e.getMessage(), e);
                }
            }
        }

        return null;
    }

    /**
     * Expose each step JSON output under the step name so downstream templates can reference
     * fields with syntax like {{step-name.field}}.
     */
    private void addStepOutputToParameters(Map<String, Object> runtimeParameters, String stepName,
            JsonNode stepOutput) {
        if (runtimeParameters == null || stepName == null || stepOutput == null) {
            return;
        }

        Object converted = mapper.convertValue(stepOutput, Object.class);
        runtimeParameters.put(stepName, converted);
    }

    /**
     * Resolve the output visible to subsequent steps.
     *
     * When consumed operation output parameters are defined, expose the projected object so
     * templates can reference declared names like {{step-name.userid}}.
     */
    private JsonNode resolveStepOutput(HandlingContext context, JsonNode rawOutput) {
        if (rawOutput == null) {
            return NullNode.instance;
        }

        if (context == null || context.clientOperation == null
                || context.clientOperation.getOutputParameters() == null
                || context.clientOperation.getOutputParameters().isEmpty()) {
            return rawOutput;
        }

        ObjectNode projected = mapper.createObjectNode();
        JsonNode unnamed = null;

        for (OutputParameterSpec outputParameter : context.clientOperation.getOutputParameters()) {
            JsonNode mapped = Resolver.resolveOutputMappings(outputParameter, rawOutput, mapper);

            if (mapped == null) {
                mapped = NullNode.instance;
            }

            if (outputParameter.getName() != null && !outputParameter.getName().isBlank()) {
                projected.set(outputParameter.getName(), mapped);
            } else if (unnamed == null) {
                unnamed = mapped;
            }
        }

        if (!projected.isEmpty()) {
            return projected;
        }

        return unnamed != null ? unnamed : rawOutput;
    }

    /**
     * Find and construct a client request context for a call specification.
     */
    public HandlingContext findClientRequestFor(ServerCallSpec call,
            Map<String, Object> requestParams) {

        if (call == null) {
            return null;
        }

        Map<String, Object> merged = new HashMap<>();

        if (requestParams != null) {
            merged.putAll(requestParams);
        }

        if (call.getWith() != null) {
            merged.putAll(call.getWith());
        }

        if (call.getOperation() != null) {
            String[] tokens = call.getOperation().split("\\.");

            if (tokens.length == 2) {
                return findClientRequestFor(tokens[0], tokens[1], merged);
            }
        }

        return null;
    }

    /**
     * Find and construct a client request context for a given client namespace, operation name, and
     * parameters.
     */
    public HandlingContext findClientRequestFor(String clientNamespace, String clientOpName,
            Map<String, Object> parameters) {

        for (ClientAdapter adapter : capability.getClientAdapters()) {
            if (adapter instanceof HttpClientAdapter) {
                HttpClientAdapter clientAdapter = (HttpClientAdapter) adapter;

                if (clientAdapter.getHttpClientSpec().getNamespace().equals(clientNamespace)) {
                    HttpClientOperationSpec clientOp = clientAdapter.getOperationSpec(clientOpName);

                    if (clientOp != null) {
                        String clientResUri = clientAdapter.getHttpClientSpec().getBaseUri()
                                + clientOp.getParentResource().getPath();

                        // Resolve Mustache templates
                        clientResUri = Resolver.resolveMustacheTemplate(clientResUri, parameters);

                        // Validate all templates are resolved
                        if (clientResUri.contains("{{") && clientResUri.contains("}}")) {
                            throw new IllegalArgumentException(
                                    "Unresolved template parameters in URI: " + clientResUri
                                            + ". Available parameters: "
                                            + (parameters != null ? parameters.keySet() : "none"));
                        }

                        HandlingContext ctx = new HandlingContext();
                        ctx.clientRequest = new Request();
                        ctx.clientAdapter = clientAdapter;
                        ctx.clientOperation = clientOp;
                        ctx.clientResponse = new Response(ctx.clientRequest);

                        // Apply client-level and operation-level input parameters
                        Resolver.resolveInputParametersToRequest(ctx.clientRequest,
                                clientAdapter.getHttpClientSpec().getInputParameters(), parameters);
                        Resolver.resolveInputParametersToRequest(ctx.clientRequest,
                                clientOp.getInputParameters(), parameters);

                        ctx.clientRequest.setMethod(Method.valueOf(clientOp.getMethod()));
                        ctx.clientRequest.setResourceRef(new Reference(
                                Resolver.resolveMustacheTemplate(clientResUri, parameters)));

                        if (clientOp.getBody() != null) {
                            String resolvedBody = Resolver
                                    .resolveMustacheTemplate(clientOp.getBody(), parameters);

                            if (resolvedBody.contains("{{") && resolvedBody.contains("}}")) {
                                throw new IllegalArgumentException(
                                        "Unresolved template parameters in body: " + resolvedBody
                                                + ". Available parameters: "
                                                + (parameters != null ? parameters.keySet()
                                                        : "none"));
                            }

                            ctx.clientRequest.setEntity(resolvedBody, MediaType.APPLICATION_JSON);
                        }

                        // Set authentication and headers
                        ctx.clientAdapter.setChallengeResponse(null, ctx.clientRequest,
                                ctx.clientRequest.getResourceRef().toString(), parameters);
                        ctx.clientAdapter.setHeaders(ctx.clientRequest);
                        return ctx;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Internal context for managing an HTTP client request-response pair.
     */
    public static class HandlingContext {
        public HttpClientAdapter clientAdapter;
        public HttpClientOperationSpec clientOperation;
        public Request clientRequest;
        public Response clientResponse;

        public void handle() {
            clientAdapter.getHttpClient().handle(clientRequest, clientResponse);
        }
    }

    /**
     * Result of a full step execution sequence.
     */
    public static class StepExecutionResult {
        public final HandlingContext lastContext;
        public final StepExecutionContext stepContext;

        public StepExecutionResult(HandlingContext lastContext, StepExecutionContext stepContext) {
            this.lastContext = lastContext;
            this.stepContext = stepContext;
        }
    }
}
