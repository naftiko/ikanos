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
package io.ikanos.engine.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.representation.StringRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.http.HttpClientAdapter;
import io.ikanos.engine.observability.OtelNullSafety;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.scripting.ScriptStepExecutor;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepLookupSpec;
import io.ikanos.spec.scripting.OperationStepScriptSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * Executor for orchestrated operation steps.
 * 
 * Handles the common logic of executing a sequence of operation steps:
 * - Resolving and merging parameters at each step
 * - Finding the appropriate client adapter and operation
 * - Executing the client request
 * 
 * Used by both RestResourceRestlet and McpToolHandler to avoid duplication.
 */
public class OperationStepExecutor {

    private static final Logger logger = LoggerFactory.getLogger(OperationStepExecutor.class);

    private final Capability capability;
    private final ObjectMapper mapper;
    private final ScriptStepExecutor scriptExecutor;
    private volatile String exposeNamespace;

    public OperationStepExecutor(Capability capability) {
        this(capability, null);
    }

    public OperationStepExecutor(Capability capability, String exposeNamespace) {
        this.capability = capability;
        this.mapper = new ObjectMapper();
        this.scriptExecutor = new ScriptStepExecutor();
        this.exposeNamespace = exposeNamespace;
        if (capability != null && capability.getScriptingSpec() != null) {
            this.scriptExecutor.setScriptingSpec(capability.getScriptingSpec());
        }
    }

    ScriptStepExecutor getScriptExecutor() {
        return scriptExecutor;
    }

    /**
     * Override the expose namespace after construction.
     *
     * <p>This setter exists for {@link io.ikanos.engine.aggregates.AggregateFunction}, which
     * shares a single {@code OperationStepExecutor} across multiple aggregate functions that may
     * belong to different namespaces. The function sets its own namespace before each execution.
     * Handlers whose namespace is known at construction time should use
     * {@link #OperationStepExecutor(Capability, String)} instead.</p>
     */
    public void setExposeNamespace(String exposeNamespace) {
        this.exposeNamespace = exposeNamespace;
    }

    /**
     * Build a map of input parameter values for a given request and operation by evaluating
     * server-level, resource-level and operation-level InputParameterSpec entries.
     */
    public Map<String, Object> resolveInputParametersFromRequest(Request request,
            RestServerSpec serverSpec, RestServerResourceSpec resourceSpec,
            RestServerOperationSpec serverOp) {
        Map<String, Object> params = new HashMap<>();
        JsonNode tmpRoot = null;

        // Read request body once (may be null)
        try {
            if ((request.getEntity() != null) && !request.getEntity().isEmpty()) {
                tmpRoot = mapper.readTree(request.getEntity().getReader());
            }
        } catch (IOException e) {
            logger.debug("Request body is not valid JSON; treating as absent", e);
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
        Map<String, Object> runtimeParameters = new HashMap<>();

        if (baseParameters != null) {
            runtimeParameters.putAll(baseParameters);
        }

        if (steps == null || steps.isEmpty()) {
            return new StepExecutionResult(lastContext, stepContext);
        }

        StepHandlerRegistry registry = capability != null
            ? capability.getStepHandlerRegistry() : null;

        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            OperationStepSpec step = steps.get(stepIndex);

            // Check step handler registry before normal dispatch
            if (registry != null && step.getName() != null && registry.has(step.getName())) {
                TelemetryBootstrap telemetry = TelemetryBootstrap.get();
                Span stepSpan = telemetry.startStepCallSpan(
                        stepIndex, "handler:" + step.getName(), exposeNamespace);
                long handlerStartNanos = System.nanoTime();
                try (Scope stepScope = stepSpan.makeCurrent()) {
                    Map<String, Object> withValues = null;
                    if (step instanceof OperationStepCallSpec callSpec) {
                        withValues = callSpec.getWith();
                        if (withValues != null) {
                            Map<String, Object> resolved = new HashMap<>(withValues);
                            mergeWithParameters(resolved, runtimeParameters, exposeNamespace);
                            withValues = resolved;
                        }
                    }
                    JsonNode handlerResult = registry.executeHandler(
                            step.getName(), runtimeParameters, stepContext, withValues);
                    if (handlerResult != null) {
                        stepContext.storeStepOutput(step.getName(), handlerResult);
                        addStepOutputToParameters(
                                runtimeParameters, step.getName(), handlerResult);
                    }
                } catch (Exception e) {
                    TelemetryBootstrap.recordError(stepSpan, e);
                    throw e instanceof IllegalStateException
                            ? (IllegalStateException) e
                            : new IllegalStateException(
                                    "Step handler failed for step: " + step.getName(), e);
                } finally {
                    double handlerDurationSec =
                            (System.nanoTime() - handlerStartNanos) / 1_000_000_000.0;
                    telemetry.getMetrics().recordStep(
                            "handler", exposeNamespace, handlerDurationSec);
                    TelemetryBootstrap.endSpan(stepSpan);
                }
                continue;
            }

            switch (step) {
                case OperationStepCallSpec callStep -> {
                    TelemetryBootstrap telemetry = TelemetryBootstrap.get();
                    Span stepSpan = telemetry
                            .startStepCallSpan(stepIndex, callStep.getCall(), exposeNamespace);
                    long stepStartNanos = System.nanoTime();
                    try (Scope stepScope = stepSpan.makeCurrent()) {
                        lastContext = executeCallStep(callStep, runtimeParameters);

                        if (lastContext == null) {
                            throw new IllegalArgumentException("Invalid call format: "
                                    + (callStep.getCall() != null ? callStep.getCall() : "null"));
                        }

                        try {
                            lastContext.handle();
                        } catch (Exception e) {
                            throw new IllegalStateException(
                                    "Error while handling an HTTP client call", e);
                        }

                        // Store call output for lookup references when response is valid JSON
                        if (lastContext.clientResponse != null
                                && lastContext.clientResponse.getEntity() != null) {
                            try {
                                if (!(lastContext.clientResponse
                                        .getEntity() instanceof StringRepresentation)) {
                                    lastContext.clientResponse
                                            .setEntity(new StringRepresentation(
                                                    lastContext.clientResponse.getEntity()
                                                            .getText(),
                                                    lastContext.clientResponse.getEntity()
                                                            .getMediaType()));
                                }

                                JsonNode rawOutput = mapper.readTree(
                                        lastContext.clientResponse.getEntity().getReader());
                                JsonNode stepOutput =
                                        resolveStepOutput(lastContext, rawOutput);
                                stepContext.storeStepOutput(callStep.getName(), stepOutput);
                                addStepOutputToParameters(runtimeParameters,
                                        callStep.getName(), stepOutput);
                            } catch (IOException ignoreJsonParseError) {
                                logger.debug("Step output is not JSON; skipping lookup index update", ignoreJsonParseError);
                            }
                        }
                    } catch (Exception e) {
                        TelemetryBootstrap.recordError(stepSpan, e);
                        throw e;
                    } finally {
                        double stepDurationSec =
                                (System.nanoTime() - stepStartNanos) / 1_000_000_000.0;
                        telemetry.getMetrics().recordStep(
                                "call", exposeNamespace, stepDurationSec);
                        TelemetryBootstrap.endSpan(stepSpan);
                    }
                }
                case OperationStepLookupSpec lookupStep -> {
                    TelemetryBootstrap lookupTelemetry = TelemetryBootstrap.get();
                    Span stepSpan = lookupTelemetry
                            .startStepLookupSpan(stepIndex, lookupStep.getMatch());
                    long lookupStartNanos = System.nanoTime();
                    try (Scope stepScope = stepSpan.makeCurrent()) {
                        JsonNode indexData = stepContext.getStepOutput(lookupStep.getIndex());

                        if (indexData == null) {
                            throw new IllegalArgumentException(
                                    "Lookup step references non-existent step: "
                                            + lookupStep.getIndex());
                        }

                        String resolvedLookupValue = Resolver.resolveMustacheTemplate(
                                lookupStep.getLookupValue(), runtimeParameters);

                        // Resolve lookup value from step context (JsonPath) when applicable
                        JsonNode lookupValueNode = resolveJsonPathFromStepContext(
                                lookupStep.getLookupValue(), stepContext);

                        JsonNode lookupResult;
                        if (lookupValueNode != null && lookupValueNode.isArray()) {
                            // Multi-value lookup: collect results into an array
                            ArrayNode resultArray = mapper.createArrayNode();
                            for (JsonNode item : lookupValueNode) {
                                JsonNode match = LookupExecutor.executeLookup(indexData,
                                        lookupStep.getMatch(), item.asText(),
                                        lookupStep.getOutputParameters());
                                if (match != null) {
                                    resultArray.add(match);
                                }
                            }
                            lookupResult = resultArray.isEmpty() ? null : resultArray;
                        } else if (lookupValueNode != null) {
                            lookupResult = LookupExecutor.executeLookup(indexData,
                                    lookupStep.getMatch(), lookupValueNode.asText(),
                                    lookupStep.getOutputParameters());
                        } else {
                            lookupResult = LookupExecutor.executeLookup(indexData,
                                    lookupStep.getMatch(), resolvedLookupValue,
                                    lookupStep.getOutputParameters());
                        }

                        if (lookupResult != null) {
                            stepContext.storeStepOutput(lookupStep.getName(), lookupResult);
                        }
                    } catch (Exception e) {
                        TelemetryBootstrap.recordError(stepSpan, e);
                        throw e;
                    } finally {
                        double lookupDurationSec =
                                (System.nanoTime() - lookupStartNanos) / 1_000_000_000.0;
                        lookupTelemetry.getMetrics().recordStep(
                                "lookup", exposeNamespace, lookupDurationSec);
                        TelemetryBootstrap.endSpan(stepSpan);
                    }
                }
                case OperationStepScriptSpec scriptStep -> {
                    ScriptStepExecutor.requireScriptingPermitted(
                            scriptStep.getName(), scriptExecutor.getScriptingSpec());
                    TelemetryBootstrap scriptTelemetry = TelemetryBootstrap.get();
                    String effectiveScriptLanguage = scriptStep.getLanguage();
                    if ((effectiveScriptLanguage == null
                            || effectiveScriptLanguage.isBlank())
                            && scriptExecutor.getScriptingSpec() != null) {
                        effectiveScriptLanguage =
                                scriptExecutor.getScriptingSpec().getDefaultLanguage();
                    }
                    Span stepSpan = scriptTelemetry.startStepScriptSpan(
                            stepIndex, scriptStep.getFile(),
                            effectiveScriptLanguage == null
                                    || effectiveScriptLanguage.isBlank()
                                            ? "unknown"
                                            : effectiveScriptLanguage);
                    long scriptStartNanos = System.nanoTime();
                    try (Scope stepScope = stepSpan.makeCurrent()) {
                        JsonNode scriptResult = scriptExecutor.execute(
                                scriptStep, runtimeParameters, stepContext);
                        if (scriptResult != null) {
                            stepContext.storeStepOutput(scriptStep.getName(), scriptResult);
                            addStepOutputToParameters(
                                    runtimeParameters, scriptStep.getName(), scriptResult);
                        }
                    } catch (Exception e) {
                        TelemetryBootstrap.recordError(stepSpan, e);
                        throw e;
                    } finally {
                        double scriptDurationSec =
                                (System.nanoTime() - scriptStartNanos) / 1_000_000_000.0;
                        scriptTelemetry.getMetrics().recordStep(
                                "script", exposeNamespace, scriptDurationSec);
                        TelemetryBootstrap.endSpan(stepSpan);
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
     * Merges 'with' parameters into a target map, resolving values against the current state of
     * the target map. Handles two value syntaxes:
     * <ul>
     *   <li>Mustache template ({@code {{paramName}}}) — resolved via JMustache.</li>
     *   <li>Namespace-qualified reference ({@code namespace.paramName}) — resolved by looking up
     *       {@code paramName} in the target map when a matching namespace prefix is provided.</li>
     * </ul>
     *
     * @param with      the 'with' map from a spec; may be null (no-op)
     * @param target    the map to merge into; resolution uses its current state
     * @param namespace the expose namespace used to detect qualified references (e.g.
     *                  {@code "shipyard-api"}); may be null to skip namespace resolution
     */
    public static void mergeWithParameters(Map<String, Object> with, Map<String, Object> target,
            String namespace) {
        if (with == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : with.entrySet()) {
            Object rawValue = entry.getValue();
            if (rawValue instanceof String rawStringValue) {
                if (namespace != null && rawStringValue.startsWith(namespace + ".")) {
                    String paramName = rawStringValue.substring(namespace.length() + 1);
                    Object resolved = target.get(paramName);
                    if (resolved != null) {
                        target.put(entry.getKey(), resolved);
                    }
                } else {
                    target.put(entry.getKey(),
                            Resolver.resolveMustacheTemplate(rawStringValue, target));
                }
            } else {
                target.put(entry.getKey(), rawValue);
            }
        }
    }

    /**
     * Execute a single call step.
     */
    private HandlingContext executeCallStep(OperationStepCallSpec callStep,
            Map<String, Object> baseParameters) {
        // Merge step-level 'with' parameters with base parameters
        Map<String, Object> stepParams = new HashMap<>(baseParameters);

        mergeWithParameters(callStep.getWith(), stepParams, exposeNamespace);

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
    public void addStepOutputToParameters(Map<String, Object> runtimeParameters, String stepName,
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
    public JsonNode resolveStepOutput(HandlingContext context, JsonNode rawOutput) {
        if (rawOutput == null) {
            return NullNode.instance;
        }

        if (context == null || context.clientOperation == null
                || context.clientOperation.getOutputParameters() == null
                || context.clientOperation.getOutputParameters().isEmpty()) {
            return rawOutput;
        }

        // When the raw output is an array, augment each element with projected fields
        // instead of applying the mapping to the array root (which would collapse it).
        if (rawOutput.isArray()) {
            ArrayNode resultArray = mapper.createArrayNode();
            for (JsonNode element : rawOutput) {
                ObjectNode augmented =
                        element.isObject() ? ((ObjectNode) element).deepCopy()
                                : mapper.createObjectNode();
                for (OutputParameterSpec outputParameter : context.clientOperation
                        .getOutputParameters()) {
                    if (outputParameter.getName() != null
                            && !outputParameter.getName().isBlank()) {
                        JsonNode mapped = Resolver.resolveOutputMappings(outputParameter, element,
                                mapper);
                        if (mapped != null) {
                            augmented.set(outputParameter.getName(), mapped);
                        }
                    }
                }
                resultArray.add(augmented);
            }
            return resultArray;
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

        mergeWithParameters(call.getWith(), merged, exposeNamespace);

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

                        ctx.clientRequest.setMethod(Method.valueOf(clientOp.getMethod()));
                        ctx.clientRequest.setResourceRef(new Reference(
                                Resolver.resolveMustacheTemplate(clientResUri, parameters)));

                        // Apply client-level and operation-level input parameters
                        // NOTE: setResourceRef must be called first so that query params
                        // (in: query) are appended to the correct base URI, not to null.
                        Resolver.resolveInputParametersToRequest(ctx.clientRequest,
                                clientAdapter.getHttpClientSpec().getInputParameters(), parameters);
                        Resolver.resolveInputParametersToRequest(ctx.clientRequest,
                                clientOp.getInputParameters(), parameters);

                        if (clientOp.getBody() != null) {
                            String resolvedBody;
                            MediaType bodyMediaType = MediaType.APPLICATION_JSON;

                            Object bodySpec = clientOp.getBody();
                            if (bodySpec instanceof String) {
                                // Legacy: plain Mustache template string
                                resolvedBody = Resolver.resolveMustacheTemplate(
                                        (String) bodySpec, parameters);
                            } else {
                                // Structured {type, data} RequestBody object
                                @SuppressWarnings("unchecked")
                                Map<String, Object> bodyMap = (Map<String, Object>) bodySpec;
                                String bodyType = String.valueOf(
                                        bodyMap.getOrDefault("type", "json"));
                                Object data = bodyMap.get("data");
                                String dataStr;
                                try {
                                    dataStr = mapper.writeValueAsString(data);
                                } catch (IOException e) {
                                    throw new IllegalArgumentException(
                                        "Invalid structured body data for operation: "
                                            + clientNamespace + "." + clientOpName,
                                        e);
                                }
                                resolvedBody = Resolver.resolveMustacheTemplate(
                                        dataStr, parameters);
                                if ("formUrlEncoded".equalsIgnoreCase(bodyType)) {
                                    bodyMediaType = MediaType.APPLICATION_WWW_FORM;
                                } else if ("xml".equalsIgnoreCase(bodyType)) {
                                    bodyMediaType = MediaType.APPLICATION_XML;
                                } else if ("sparql".equalsIgnoreCase(bodyType)) {
                                    bodyMediaType = MediaType.valueOf(
                                            "application/sparql-query");
                                }
                            }

                            if (resolvedBody.contains("{{") && resolvedBody.contains("}}")) {
                                throw new IllegalArgumentException(
                                        "Unresolved template parameters in body: " + resolvedBody
                                                + ". Available parameters: "
                                                + (parameters != null ? parameters.keySet()
                                                        : "none"));
                            }

                            ctx.clientRequest.setEntity(resolvedBody, bodyMediaType);
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
     * Execute either a simple call or a sequence of steps, returning the last HandlingContext.
     *
     * <p>When {@code call} is non-null the matching client adapter is located, the request is
     * built and {@link HandlingContext#handle()} is invoked. When {@code steps} is non-empty the
     * full step-orchestration path runs instead. Throws {@link IllegalArgumentException} when the
     * call reference cannot be resolved or neither {@code call} nor {@code steps} is defined.</p>
     *
     * @param call        the simple call spec, or {@code null}
     * @param steps       the step list, or {@code null}/empty
     * @param parameters  resolved parameters available for template substitution
     * @param entityLabel human-readable label used in error messages (e.g. {@code "Tool 'my-tool'"})
     * @return the resulting {@link HandlingContext}
     * @throws IllegalArgumentException when the call reference is invalid or neither mode is
     *         defined
     * @throws Exception when the underlying HTTP request fails
     */
    public HandlingContext execute(ServerCallSpec call, List<OperationStepSpec> steps,
            Map<String, Object> parameters, String entityLabel) throws Exception {
        if (call != null) {
            HandlingContext found = findClientRequestFor(call, parameters);
            if (found == null) {
                throw new IllegalArgumentException(
                        "Invalid call for " + entityLabel + ": " + call.getOperation());
            }
            found.handle();
            return found;
        } else if (steps != null && !steps.isEmpty()) {
            return executeSteps(steps, parameters).lastContext;
        } else {
            throw new IllegalArgumentException(
                    entityLabel + " has neither call nor steps defined");
        }
    }

    /**
     * Resolve step output mappings into a composite JSON object.
     *
     * <p>Each mapping references a step output via a {@code $.<step-name>.<field-path>}
     * expression. The resolved values are assembled into a single JSON object keyed by
     * {@code targetName}.</p>
     *
     * @param mappings    the list of step output mappings to apply
     * @param stepContext the execution context containing step outputs
     * @return the composite JSON string, or {@code null} when no mapping resolved
     */
    public String resolveStepMappings(List<StepOutputMappingSpec> mappings,
            StepExecutionContext stepContext) throws IOException {
        if (mappings == null || mappings.isEmpty() || stepContext == null) {
            return null;
        }

        ObjectNode result = mapper.createObjectNode();

        for (StepOutputMappingSpec mapping : mappings) {
            JsonNode resolved = resolveJsonPathFromStepContext(mapping.getValue(), stepContext);
            if (resolved != null) {
                setNestedField(result, mapping.getTargetName(), resolved);
            }
        }

        return result.isEmpty() ? null : mapper.writeValueAsString(result);
    }

    /**
     * Set a potentially nested field on an ObjectNode using dot-notation.
     *
     * <p>For example, {@code "route.from"} creates {@code {"route":{"from": value}}}.</p>
     */
    private void setNestedField(ObjectNode root, String path, JsonNode value) {
        if (path == null || path.isEmpty()) {
            return;
        }

        int dotIndex = path.indexOf('.');
        if (dotIndex == -1) {
            root.set(path, value);
            return;
        }

        String head = path.substring(0, dotIndex);
        String tail = path.substring(dotIndex + 1);

        JsonNode existing = root.get(head);
        ObjectNode child;
        if (existing != null && existing.isObject()) {
            child = (ObjectNode) existing;
        } else {
            child = mapper.createObjectNode();
            root.set(head, child);
        }

        setNestedField(child, tail, value);
    }

    /**
     * Resolve a {@code $.<step-name>.<field-path>} expression against the step context.
     *
     * @param value       the path expression (e.g. {@code "$.get-ship.imo_number"})
     * @param stepContext the execution context containing step outputs
     * @return the resolved {@link JsonNode}, or {@code null} when the path cannot be resolved
     */
    JsonNode resolveJsonPathFromStepContext(String value, StepExecutionContext stepContext) {
        if (value == null || !value.startsWith("$.") || stepContext == null) {
            return null;
        }

        String path = value.substring(2);
        int firstDot = path.indexOf('.');

        String stepName;
        String fieldPath;

        if (firstDot == -1) {
            stepName = path;
            fieldPath = null;
        } else {
            stepName = path.substring(0, firstDot);
            fieldPath = path.substring(firstDot + 1);
        }

        JsonNode stepOutput = stepContext.getStepOutput(stepName);
        if (stepOutput == null) {
            return null;
        }

        if (fieldPath == null || fieldPath.isEmpty()) {
            return stepOutput;
        }

        JsonNode current = stepOutput;
        for (String segment : fieldPath.split("\\.")) {
            if (current == null || current.isNull()) {
                return null;
            }
            current = current.get(segment);
        }

        return current;
    }

    /**
     * Apply output parameter mappings to a JSON response string.
     *
     * <p>Parses {@code responseText} as JSON and evaluates each {@link OutputParameterSpec} in
     * order, returning the first non-null mapped value serialised back to JSON. Returns
     * {@code null} when no mapping matches (callers should fall back to the raw response).</p>
     *
     * @param responseText     the raw HTTP response body
     * @param outputParameters the list of output parameter specs to try
     * @return the first mapped JSON string, or {@code null} if none matched
     */
    public String applyOutputMappings(String responseText,
            List<OutputParameterSpec> outputParameters) throws IOException {
        return applyOutputMappings(responseText, outputParameters, null, null);
    }

    /**
     * Apply output parameter mappings, converting the response from the declared format first.
     *
     * <p>When {@code outputRawFormat} is non-null (e.g. {@code "xml"}), the response text
     * is converted to a JSON tree via {@link Converter#convertToJson(String, String, String)}
     * before mappings are applied. When {@code null}, the text is parsed as JSON directly.</p>
     *
     * @param responseText     the raw HTTP response body
     * @param outputParameters the list of output parameter specs to try
     * @param outputRawFormat  the declared format (may be {@code null} for JSON)
     * @param outputSchema     the declared schema (used by HTML/Markdown selectors)
     * @return the first mapped JSON string, or {@code null} if none matched
     */
    public String applyOutputMappings(String responseText,
            List<OutputParameterSpec> outputParameters,
            String outputRawFormat, String outputSchema) throws IOException {
        if (responseText == null || responseText.isEmpty()) {
            return null;
        }
        if (outputParameters == null || outputParameters.isEmpty()) {
            return null;
        }
        JsonNode root = Converter.convertToJson(outputRawFormat, outputSchema, responseText);
        for (OutputParameterSpec outputParam : outputParameters) {
            JsonNode mapped = Resolver.resolveOutputMappings(outputParam, root, mapper);
            if (mapped != null && !(mapped instanceof NullNode)) {
                return mapper.writeValueAsString(mapped);
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
            TelemetryBootstrap telemetry = TelemetryBootstrap.get();

            String method = clientRequest.getMethod() != null
                    ? clientRequest.getMethod().getName() : "UNKNOWN";
            String url = clientRequest.getResourceRef() != null
                    ? clientRequest.getResourceRef().toString() : "unknown";
            String namespace = clientAdapter.getHttpClientSpec().getNamespace();

            Span span = telemetry.startClientSpan(method, url, namespace);
            long clientStartNanos = System.nanoTime();
            try (Scope scope = span.makeCurrent()) {
                // Inject W3C trace context after the client span is current
                // so downstream services see this span as the parent
                OtelRestletBridge.injectContext(clientRequest);

                clientAdapter.getHttpClient().handle(clientRequest, clientResponse);

                if (clientResponse != null && clientResponse.getStatus() != null) {
                    int statusCode = clientResponse.getStatus().getCode();
                    span.setAttribute(
                            OtelNullSafety.nonNullLongKey(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE),
                            statusCode);
                    if (statusCode >= 500) {
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR,
                                "HTTP " + statusCode);
                    }
                }
            } catch (Exception e) {
                TelemetryBootstrap.recordError(span, e);
                throw e;
            } finally {
                double clientDurationSec =
                        (System.nanoTime() - clientStartNanos) / 1_000_000_000.0;
                String host = clientRequest.getResourceRef() != null
                        ? clientRequest.getResourceRef().getHostDomain() : "unknown";
                int code = clientResponse != null && clientResponse.getStatus() != null
                        ? clientResponse.getStatus().getCode() : 0;
                telemetry.getMetrics().recordHttpClient(method, host != null ? host : "unknown",
                        code, clientDurationSec);
                TelemetryBootstrap.endSpan(span);
            }
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
