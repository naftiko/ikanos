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
package io.ikanos.engine.exposes.rest;

import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.aggregates.FlowResult;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.RestletBackedResult;
import io.ikanos.engine.consumes.http.ConsumedAuthentication;
import io.ikanos.engine.consumes.http.HttpClientAdapter;
import io.ikanos.engine.consumes.http.StandardRequestHeaders;
import io.ikanos.engine.execution.Executions;
import io.ikanos.engine.execution.InputDeclinedException;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.engine.exposes.ErrorReference;
import io.ikanos.engine.exposes.mcp.ResourceHandler;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.engine.util.Resolver;
import io.ikanos.engine.util.Secrets;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.AwsSigV4AuthenticationSpec;
import io.ikanos.spec.exposes.rest.RestServerForwardSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.representation.Representation;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Restlet that handles calls to an API resource
 */
public class ResourceRestlet extends Restlet {

    private final Capability capability;
    private final RestServerSpec serverSpec;
    private final RestServerResourceSpec resourceSpec;
    private final OperationStepExecutor stepExecutor;
    private final RestInputRequests inputRequests;

    /** Set while an operation runs as a round of input requests: a question ends the round. */
    private static final ThreadLocal<Boolean> IN_ROUND = ThreadLocal.withInitial(() -> false);

    public ResourceRestlet(Capability capability, RestServerSpec serverSpec,
            RestServerResourceSpec resourceSpec) {
        this(capability, serverSpec, resourceSpec, null);
    }

    /**
     * @param inputRequests answers the operations that may ask their caller for input, or
     *        {@code null} when they answer {@code 501}
     */
    public ResourceRestlet(Capability capability, RestServerSpec serverSpec,
            RestServerResourceSpec resourceSpec, RestInputRequests inputRequests) {
        this.capability = capability;
        this.serverSpec = serverSpec;
        this.resourceSpec = resourceSpec;
        this.stepExecutor = new OperationStepExecutor(capability, serverSpec.getNamespace());
        this.inputRequests = inputRequests;
    }

    @Override
    public void handle(Request request, Response response) {
        // Extract W3C traceparent from inbound headers
        TelemetryBootstrap telemetry = TelemetryBootstrap.get();
        io.opentelemetry.context.Context extractedContext =
                OtelRestletBridge.extractContext(request);

        String operationId = resourceSpec.getPath() + " " + request.getMethod().getName();
        String capabilityName = capability.getSpec().getInfo() != null
                ? capability.getSpec().getInfo().getDisplay() : null;
        Span span = telemetry.startServerSpan("rest", operationId, extractedContext,
                request.getMethod().getName(), capabilityName);

        long startNanos = System.nanoTime();
        boolean error = false;
        try (Scope scope = span.makeCurrent()) {
            TelemetryBootstrap.populateMdc(span);
            boolean handled = handleFromOperationSpec(request, response);

            if (!handled && getResourceSpec().getForward() != null) {
                handled = handleFromForwardSpec(request, response);
            }

            if (!handled) {
                response.setStatus(Status.CLIENT_ERROR_NOT_FOUND);
                response.setEntity(
                        "Unable to handle the request. Please check the capability specification.",
                        MediaType.TEXT_PLAIN);
            }
        } catch (Exception e) {
            error = true;
            TelemetryBootstrap.recordError(span, e);
            telemetry.getMetrics().recordRequestError("rest", operationId,
                    e.getClass().getSimpleName());
            throw e;
        } finally {
            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            String status = error ? "500"
                    : response.getStatus() != null
                            ? String.valueOf(response.getStatus().getCode()) : "200";
            telemetry.getMetrics().recordRequest("rest", operationId, status, durationSec);
            TelemetryBootstrap.clearMdc();
            TelemetryBootstrap.endSpan(span);
        }
    }

    /**
     * Prepare a client request context by looking for a call configuration in the operation steps.
     * If a valid call configuration is found, constructs a client request context with the
     * corresponding client request and adapter. If an invalid call format is found, sets the
     * response to indicate a bad request and marks the context as handled.
     */
    private boolean handleFromOperationSpec(Request request, Response response) {
        for (RestServerOperationSpec serverOp : getResourceSpec().getOperations().values()) {

            if (serverOp.getMethod().equals(request.getMethod().getName())) {

                // Build request-scoped input parameter map (resource + operation). A value that
                // does not match its declared type is the caller's error.
                Map<String, Object> inputParameters;
                try {
                    inputParameters = stepExecutor.resolveInputParametersFromRequest(request,
                            getServerSpec(), getResourceSpec(), serverOp);
                } catch (IllegalArgumentException e) {
                    sendError(response, Status.CLIENT_ERROR_BAD_REQUEST,
                            "Error resolving request parameters",
                            "Error resolving request parameters", e);
                    return true;
                }

                // Include operation-level 'with' parameters for template resolution
                OperationStepExecutor.mergeWithParameters(serverOp.getWith(), inputParameters,
                        getServerSpec().getNamespace());

                // An operation that may ask its caller answers in rounds (202, then answers)
                if (inputRequests != null && inputRequests.applies(serverOp)) {
                    inputRequests.first(this, serverOp, request, response, inputParameters);
                    return true;
                }
                return runOperation(serverOp, request, response, inputParameters);
            }
        }

        return false;
    }

    /**
     * Runs an operation with its resolved input parameters and writes its response: the
     * referenced flow, the singleton call, or the steps.
     */
    boolean runOperation(RestServerOperationSpec serverOp, Request request, Response response,
            Map<String, Object> inputParameters) {
        ConsumedResult found = null;
        // Delegate to aggregate function when ref is set
        if (serverOp.getRef() != null) {
            return executeViaAggregate(serverOp, request, response, inputParameters);
        }

        if (serverOp.getCall() != null) {
            ConsumedInvocation invocation;
            try {
                invocation = stepExecutor.findClientRequestFor(serverOp.getCall(),
                        inputParameters);
            } catch (IllegalArgumentException e) {
                sendError(response, Status.CLIENT_ERROR_BAD_REQUEST,
                        "Error resolving request parameters",
                        "Error resolving request parameters", e);
                return true;
            }

            if (invocation != null) {
                try {
                    // Send the request to the target endpoint
                    found = Executions.invoke(invocation);
                    // An upstream error is answered as in steps: mode, never passed
                    // through (#879); no answer at all too (#873)
                    if (found != null) {
                        OperationStepExecutor.failIfUnsuccessful(
                                serverOp.getCall().getOperation(), found);
                    }
                    response.setStatus(statusOf(found));
                } catch (OperationStepExecutor.StepFailedException e) {
                    sendError(response, Status.SERVER_ERROR_BAD_GATEWAY,
                            "An upstream call failed", "Call failed", e);
                    return true;
                } catch (InputRequiredException e) {
                    // The target, a flow or a consumed MCP tool that may ask, asked the caller.
                    sendInputRequired(response, e);
                    return true;
                } catch (InputDeclinedException e) {
                    sendError(response, Status.CLIENT_ERROR_CONFLICT,
                            "The caller declined the question", "Caller declined", e);
                    return true;
                } catch (Exception e) {
                    sendError(response, Status.SERVER_ERROR_INTERNAL,
                            "Error while handling a consumed call",
                            "Error while handling consumed call in call mode", e);
                    return true;
                }

                sendResponse(serverOp, response, found);
                return true;
            } else if (canBuildMockResponse(serverOp)) {
                // No HTTP client adapter found, use mock mode with static values
                sendMockResponse(serverOp, response, inputParameters);
                return true;
            } else {
                response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
                response.setEntity("Invalid call format: "
                        + (serverOp.getCall() != null ? serverOp.getCall().getOperation()
                                : "null"),
                        MediaType.TEXT_PLAIN);
                return true;
            }
        } else {
            // Orchestrated mode - execute steps in sequence
            try {
                OperationStepExecutor.StepExecutionResult stepResult =
                        stepExecutor.executeSteps(serverOp.getSteps(), inputParameters);
                found = stepResult.lastResult;

                // Apply step output mappings if defined
                if (serverOp.getMappings() != null
                        && !serverOp.getMappings().isEmpty()) {
                    String mapped = stepExecutor.resolveStepMappings(
                            serverOp.getMappings(), serverOp.getOutputParameters(),
                            stepResult.stepContext);
                    if (mapped != null) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(mapped, MediaType.APPLICATION_JSON);
                        response.commit();
                        return true;
                    }
                }
            } catch (OperationStepExecutor.StepFailedException e) {
                sendError(response, Status.SERVER_ERROR_BAD_GATEWAY,
                        "An upstream call failed",
                        "Orchestrated step failed", e);
                return true;
            } catch (IllegalArgumentException e) {
                sendError(response, Status.CLIENT_ERROR_BAD_REQUEST,
                        "Invalid request", "Invalid argument in orchestrated steps", e);
                return true;
            } catch (InputRequiredException e) {
                sendInputRequired(response, e);
                return true;
            } catch (InputDeclinedException e) {
                sendError(response, Status.CLIENT_ERROR_CONFLICT,
                        "The caller declined the question", "Caller declined", e);
                return true;
            } catch (RuntimeException e) {
                sendError(response, Status.SERVER_ERROR_INTERNAL,
                        "Error while handling a consumed call",
                        "Error while handling orchestrated steps", e);
                return true;
            } catch (IOException e) {
                sendError(response, Status.SERVER_ERROR_INTERNAL,
                        "Error resolving step output mappings",
                        "Error resolving step output mappings", e);
                return true;
            }

            if (found != null) {
                // Return the response based on the last client request
                response.setStatus(statusOf(found));
                sendResponse(serverOp, response, found);
                return true;
            } else if (canBuildMockResponse(serverOp)) {
                // No HTTP client adapter found, use mock mode with static values
                sendMockResponse(serverOp, response, inputParameters);
                return true;
            }
        }
        return false;
    }

    /**
     * Runs an operation as one round of input requests and returns what it answered, as JSON
     * the journal can keep: {@code status}, {@code mediaType} and a text {@code body}. A question
     * ends the round with an {@link InputRequiredException}, propagated to the caller.
     *
     * @throws IllegalStateException when the operation answers with a body that is not text,
     *         which a round cannot keep
     */
    JsonNode runForRounds(RestServerOperationSpec serverOp, Map<String, Object> inputParameters) {
        Request request = new Request();
        Response scratch = new Response(request);
        IN_ROUND.set(true);
        try {
            runOperation(serverOp, request, scratch, inputParameters);
        } finally {
            IN_ROUND.remove();
        }
        tools.jackson.databind.node.ObjectNode outcome =
                IkanosMappers.json().createObjectNode();
        outcome.put("status", scratch.getStatus() != null ? scratch.getStatus().getCode() : 200);
        Representation entity = scratch.getEntity();
        if (entity != null && entity.isAvailable()) {
            MediaType type = entity.getMediaType();
            if (type != null && !isText(type)) {
                throw new IllegalStateException("The operation answered with a body that is not "
                        + "text (" + type + "), which an operation that asks cannot keep");
            }
            try {
                outcome.put("body", entity.getText());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the operation's response", e);
            }
            outcome.put("mediaType", type != null ? type.toString()
                    : MediaType.APPLICATION_JSON.toString());
        }
        return outcome;
    }

    /** Whether a round can keep this body as text: the types MCP resources read as text. */
    static boolean isText(MediaType type) {
        return !ResourceHandler.isBinaryMime(type.getName());
    }

    /**
     * Execute an operation by delegating to its referenced aggregate flow.
     */
    private boolean executeViaAggregate(RestServerOperationSpec serverOp, Request request,
            Response response, Map<String, Object> inputParameters) {
        try {
            AggregateFlow fn = capability.lookupFlow(serverOp.getRef());
            FlowResult result = fn.execute(inputParameters);

            if (result.isMock()) {
                ObjectMapper mapper = IkanosMappers.json();
                if (result.mockOutput != null) {
                    response.setStatus(Status.SUCCESS_OK);
                    response.setEntity(
                            mapper.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(result.mockOutput),
                            MediaType.APPLICATION_JSON);
                } else {
                    response.setStatus(Status.SUCCESS_NO_CONTENT);
                }
                response.commit();
                return true;
            }

            if (result.hasMappedOutput()) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity(result.mappedOutput, MediaType.APPLICATION_JSON);
                response.commit();
                return true;
            }

            if (result.lastResult != null) {
                // An upstream error is answered as in call: and steps: mode (#879)
                OperationStepExecutor.failIfUnsuccessful(serverOp.getRef(), result.lastResult);
                response.setStatus(statusOf(result.lastResult));
                sendResponse(serverOp, response, result.lastResult);
                return true;
            }

            response.setStatus(Status.SERVER_ERROR_INTERNAL);
            response.setEntity("No result from aggregate function: " + serverOp.getRef(),
                    MediaType.TEXT_PLAIN);
            return true;
        } catch (IllegalArgumentException e) {
            sendError(response, Status.CLIENT_ERROR_BAD_REQUEST, "Invalid request",
                    "Error in aggregate function call", e);
            return true;
        } catch (OperationStepExecutor.StepFailedException e) {
            sendError(response, Status.SERVER_ERROR_BAD_GATEWAY, "An upstream call failed",
                    "Aggregate flow step failed", e);
            return true;
        } catch (InputRequiredException e) {
            sendInputRequired(response, e);
            return true;
        } catch (InputDeclinedException e) {
            sendError(response, Status.CLIENT_ERROR_CONFLICT, "The caller declined the question",
                    "Caller declined", e);
            return true;
        } catch (Exception e) {
            sendError(response, Status.SERVER_ERROR_INTERNAL, "Error in aggregate function call",
                    "Error in aggregate function call", e);
            return true;
        }
    }

    /**
     * Check if an operation can build a mock response using static values from outputParameters.
     * Returns true if the operation has at least one outputParameter with a value.
     */
    boolean canBuildMockResponse(RestServerOperationSpec serverOp) {
        if (serverOp.getOutputParameters() == null || serverOp.getOutputParameters().isEmpty()) {
            return false;
        }

        // Check if at least one output parameter has a static value
        for (OutputParameterSpec param : serverOp.getOutputParameters()) {
            if (param.getValue() != null) {
                return true;
            }
            // Check nested properties for static values
            if (param.getProperties() != null && !param.getProperties().isEmpty()) {
                for (OutputParameterSpec prop : param.getProperties()) {
                    if (hasStaticValue(prop)) {
                        return true;
                    }
                }
            }
            // Check items for static values
            if (param.getItems() != null && hasStaticValue(param.getItems())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recursively check if a parameter or its nested structure has any static values.
     */
    private boolean hasStaticValue(OutputParameterSpec param) {
        if (param == null) {
            return false;
        }

        if (param.getValue() != null) {
            return true;
        }

        if (param.getProperties() != null) {
            for (OutputParameterSpec prop : param.getProperties()) {
                if (hasStaticValue(prop)) {
                    return true;
                }
            }
        }

        if (param.getItems() != null) {
            return hasStaticValue(param.getItems());
        }

        return false;
    }

    /**
     * Send a mock response using static or templated values from outputParameters.
     */
    void sendMockResponse(RestServerOperationSpec serverOp, Response response,
            Map<String, Object> inputParameters) {
        try {
            ObjectMapper mapper = IkanosMappers.json();

            // Build a JSON response using static/templated values from outputParameters
            JsonNode mockRoot = Resolver.buildMockData(serverOp.getOutputParameters(), mapper,
                    inputParameters);

            if (mockRoot != null) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity(
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mockRoot),
                        MediaType.APPLICATION_JSON);
            } else {
                response.setStatus(Status.SUCCESS_NO_CONTENT);
            }
        } catch (Exception e) {
            sendError(response, Status.SERVER_ERROR_INTERNAL, "Error building mock response",
                    "Error building mock response", e);
        }

        response.commit();
    }

    /**
     * Answers an operation whose steps ask the caller for input ({@code elicit}). The REST adapter
     * cannot ask yet: answering on a {@code 202} execution is not specified. The response is
     * {@code 501 Not Implemented}, naming the step, rather than a generic server error.
     */
    void sendInputRequired(Response response, InputRequiredException e) {
        if (IN_ROUND.get()) {
            throw e;
        }
        String steps = e.questions().stream()
                .map(InputRequiredException.Question::key)
                .collect(java.util.stream.Collectors.joining(", "));
        sendError(response, Status.SERVER_ERROR_NOT_IMPLEMENTED,
                "This operation asks for input (" + steps + "), which the REST adapter cannot "
                        + "request yet; call it through an MCP adapter",
                "Step asked for input over REST", e);
    }

    /**
     * Answer a failed request without disclosing internal detail: the caller receives a generic
     * message and a correlation identifier, the full exception goes to the log under the same
     * identifier. See {@link ErrorReference}. Package-private so it can be unit-tested directly.
     */
    void sendError(Response response, Status status, String publicMessage, String logContext,
            Throwable cause) {
        String correlationId = ErrorReference.record(Level.WARNING, logContext, cause);
        response.setStatus(status);
        response.setEntity(ErrorReference.withReference(publicMessage, correlationId),
                MediaType.TEXT_PLAIN);
    }

    /**
     * A forwarded call got no HTTP answer (a connector code such as 1001, or none): answer
     * {@code 502} with an error reference, as a failed step does, rather than forwarding a code
     * that isn't an HTTP status (#873). The failure cause goes to the log line under the
     * reference, so an operator can tell a refused connection from a timeout. An HTTP error from
     * the upstream is still passed through: {@code forward} is a proxy.
     */
    private boolean sendUpstreamUnreachable(Response response, int status, String what,
            Throwable cause) {
        sendError(response, Status.SERVER_ERROR_BAD_GATEWAY, "An upstream call failed",
                what + " got no response (status " + status + ")", cause);
        return true;
    }

    void sendResponse(RestServerOperationSpec serverOp, Response response,
            ConsumedResult found) {
        // Binary path: the consumed operation declared `outputRawFormat: binary`. Buffer the raw
        // bytes with the size cap, return them with the resolved Content-Type, and skip output
        // mappings (they are nonsensical for raw bytes). See capability-binary-content.md §7.5.
        if (found.operation().isBinary() && sendBinaryResponse(serverOp, response, found)) {
            return;
        }

        // Apply output mappings if present or forward the raw entity
        if (serverOp.getOutputParameters() != null && !serverOp.getOutputParameters().isEmpty()) {
            try {
                String mapped = mapOutputParameters(serverOp, found);

                if (mapped != null) {
                    response.setEntity(mapped, MediaType.APPLICATION_JSON);
                } else {
                    response.setEntity(rawEntityOf(found));
                }
            } catch (Exception e) {
                sendError(response, Status.SERVER_ERROR_INTERNAL, "Failed to map output parameters",
                        "Failed to map output parameters", e);
            }
        } else {
            response.setEntity(rawEntityOf(found));
        }

        response.commit();
    }

    /**
     * Status to put on the REST response for a consumed result. A Restlet-backed result keeps the
     * upstream status object as received; any other result maps its HTTP-equivalent code.
     */
    static Status statusOf(ConsumedResult result) {
        if (result instanceof RestletBackedResult restlet && restlet.restletStatus() != null) {
            return restlet.restletStatus();
        }
        return Status.valueOf(result.status());
    }

    /**
     * Unmapped body to forward on the REST response. A Restlet-backed result forwards its entity
     * byte for byte; any other result forwards its text with its media type.
     */
    static Representation rawEntityOf(ConsumedResult result) {
        if (result instanceof RestletBackedResult restlet) {
            return restlet.restletEntity();
        }
        try {
            String text = result.text();
            if (text == null) {
                return null;
            }
            String mediaType = result.mediaType();
            return new org.restlet.representation.StringRepresentation(text,
                    mediaType != null ? MediaType.valueOf(mediaType) : MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read consumed result body", e);
        }
    }

    /**
     * Return a buffered binary entity for a REST operation whose consumed operation declares
     * {@code outputRawFormat: binary} (§7.5).
     *
     * <p>The size cap is the per-operation {@code maxBinarySize} when declared, otherwise the REST
     * adapter-level {@code maxBinarySize} ({@code exposes.<name>.maxBinarySize}), otherwise the
     * engine default (§4.7). The {@code Content-Type} follows the precedence in §4.3.1: a declared
     * {@code outputMediaType} on the consumed operation wins (resolved inside
     * {@link ConsumedResult#mediaType()}); otherwise the REST
     * response contract's declared binary media type is used; otherwise the resolved upstream type;
     * otherwise {@code application/octet-stream}. {@code outputParameters} mappings are skipped with
     * an INFO log (§4.6).</p>
     *
     * @return {@code true} when the binary entity was written (response committed); {@code false}
     *         when there were no bytes to return, so the caller falls back to the text path
     */
    private boolean sendBinaryResponse(RestServerOperationSpec serverOp, Response response,
            ConsumedResult found) {
        try {
            byte[] bytes = found.bytes(
                    found.operation().maxBinaryBytes(serverSpec.getMaxBinarySize()));
            if (bytes == null) {
                return false;
            }

            if (serverOp.getOutputParameters() != null
                    && !serverOp.getOutputParameters().isEmpty()) {
                Context.getCurrentLogger().info(
                        "Skipping outputParameters mappings for REST operation '"
                                + resourceSpec.getPath() + " " + serverOp.getMethod()
                                + "': response is binary (" + found.mediaType() + ")");
            }

            // §4.3.1: declared outputMediaType (already applied in mediaType()) wins;
            // otherwise prefer the REST response contract's declared binary media type.
            String mediaType = found.mediaType();
            Optional<String> declaredRestType = serverOp.findBinaryResponseMediaType();
            String declaredUpstreamType = found.operation().outputMediaType();
            boolean hasDeclaredUpstreamType = declaredUpstreamType != null
                    && !declaredUpstreamType.isBlank();
            if (!hasDeclaredUpstreamType && declaredRestType.isPresent()) {
                mediaType = declaredRestType.get();
            }
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM.getName();
            }

            response.setEntity(new ByteArrayRepresentation(bytes, MediaType.valueOf(mediaType)));
            response.commit();
            return true;
        } catch (OperationStepExecutor.BinarySizeExceededException e) {
            Context.getCurrentLogger().warning("Binary response exceeded maxBinarySize: " + e);
            response.setStatus(Status.SERVER_ERROR_INTERNAL);
            response.setEntity(
                    "Upstream response exceeded maxBinarySize (limit=" + e.getMaxBytes()
                            + " bytes)",
                    MediaType.TEXT_PLAIN);
            response.commit();
            return true;
        } catch (IOException e) {
            sendError(response, Status.SERVER_ERROR_INTERNAL, "Error buffering binary response",
                    "Error buffering binary response", e);
            response.commit();
            return true;
        }
    }

    /**
     * Prepare a client request context based on a forward specification. This is used for direct
     * calls to the resource that should be forwarded to a target endpoint without going through the
     * operation steps.
     */
    boolean handleFromForwardSpec(Request request, Response response) {
        for (ClientAdapter adapter : getCapability().getClientAdapters()) {
            if (adapter instanceof HttpClientAdapter) {
                HttpClientAdapter httpAdapter = (HttpClientAdapter) adapter;
                RestServerForwardSpec forwardSpec = getResourceSpec().getForward();

                if (httpAdapter.getHttpClientSpec().getNamespace()
                        .equals(forwardSpec.getTargetNamespace())) {
                    try {
                        // Prepare the HTTP client request
                        String path = (String) request.getAttributes().get("path");
                        String normalizedPath = (path == null || path.isEmpty()) ? "" : "/" + path;
                        String baseRef = forwardTargetRef(httpAdapter.getBaseUri(),
                                normalizedPath, null);
                        Request clientRequest = new Request(request.getMethod(), baseRef);
                        clientRequest.setEntity(sized(request));

                        // Copy trusted headers from the original request to the client request
                        copyTrustedHeaders(request, clientRequest,
                                getResourceSpec().getForward().getTrustedHeaders());

                        // Prepare parameters map for template resolution
                        Map<String, Object> parameters = new ConcurrentHashMap<>();

                        // Resolve client input parameters first so authentication templates
                        // (e.g. bearer token from environment) can be resolved correctly
                        Resolver.resolveInputParametersToRequest(clientRequest,
                                httpAdapter.getHttpClientSpec().getInputParameters(), parameters);

                        // The caller's query, filtered with the same inputs and bindings that
                        // authentication renders the consumed API key name with, goes before
                        // the adapter's own query parameters
                        String resolvedRef = clientRequest.getResourceRef().toString();
                        String query = filterForwardedQuery(request.getResourceRef().getQuery(),
                                forwardExcludedQueryNames(httpAdapter, parameters,
                                        appendedQuery(baseRef, resolvedRef)));
                        if (query != null && !query.isEmpty() && signsWithAwsSigV4(
                                httpAdapter.getHttpClientSpec().getAuthentication())) {
                            return refuseCallerQuery(response, forwardSpec.getTargetNamespace());
                        }
                        clientRequest.setResourceRef(withCallerQuery(httpAdapter.getBaseUri(),
                                normalizedPath, baseRef, resolvedRef, query));

                        // Set any authentication needed on the client request
                        Response clientResponse = new Response(clientRequest);
                        // The caller's credential is forwarded only when Authorization is a
                        // trusted header: when the target declares no authentication,
                        // ConsumedAuthentication copies the inbound challenge response (#1010)
                        Request credentialSource = isTrustedHeader(
                                forwardSpec.getTrustedHeaders(), "Authorization") ? request : null;
                        httpAdapter.setChallengeResponse(credentialSource, clientRequest,
                                clientRequest.getResourceRef().toString(), parameters);

                        // Send the request to the target endpoint
                        httpAdapter.getHttpClient().handle(clientRequest, clientResponse);
                        int forwardedStatus = clientResponse.getStatus().getCode();
                        if (ConsumedResult.noResponseReceived(forwardedStatus)) {
                            clientResponse.release();
                            return sendUpstreamUnreachable(response, forwardedStatus,
                                    "Forward to '" + forwardSpec.getTargetNamespace() + "'",
                                    clientResponse.getStatus().getThrowable());
                        }
                        response.setStatus(clientResponse.getStatus());
                        response.setEntity(clientResponse.getEntity());
                        response.commit();
                        return true;
                    } catch (Exception e) {
                        sendError(response, Status.SERVER_ERROR_INTERNAL,
                                "Error while handling an HTTP client call",
                                "Error while handling HTTP client call in forward mode", e);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * @return {@code true} when a consumed adapter with this authentication signs its calls with
     *         {@code awsSigV4}
     */
    static boolean signsWithAwsSigV4(AuthenticationSpec authentication) {
        return authentication instanceof AwsSigV4AuthenticationSpec;
    }

    /**
     * Answers {@code 400} to a forwarded request that carries a query parameter for a target that
     * signs with {@code awsSigV4}. The signature would cover the caller's query with the
     * operator's credentials, and S3 reads a query name such as {@code acl}, {@code tagging} or
     * {@code versionId} as part of the operation, so a caller could turn a forwarded read or
     * write into another one. The adapter's own query input parameters and the query of its
     * {@code baseUri} are not the caller's, and still go out; the parameters the forward drops
     * anyway, such as the exposed API key, don't count.
     */
    private boolean refuseCallerQuery(Response response, String targetNamespace) {
        response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
        response.setEntity("A forward to '" + targetNamespace + "' takes no query parameter: the"
                + " target signs with awsSigV4, so the query would be signed with the operator's"
                + " credentials.", MediaType.TEXT_PLAIN);
        return true;
    }

    /**
     * @return {@code true} when {@code name} is listed in {@code trustedHeaders}, ignoring case
     */
    static boolean isTrustedHeader(Iterable<String> trustedHeaders, String name) {
        if (trustedHeaders == null) {
            return false;
        }
        for (String trustedHeader : trustedHeaders) {
            if (name.equalsIgnoreCase(trustedHeader)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The inbound entity, with the size its {@code Content-Length} header gives when Restlet left
     * it unknown, so that the forward sends it with that {@code Content-Length} rather than
     * chunked: S3 and MinIO refuse an unsized body that isn't aws-chunked (#954).
     */
    static Representation sized(Request request) {
        Representation entity = request.getEntity();
        if (entity != null && entity.getSize() == Representation.UNKNOWN_SIZE) {
            String length = request.getHeaders().getFirstValue("Content-Length", true);
            if (length != null) {
                try {
                    long size = Long.parseLong(length.trim());
                    if (size >= 0) {
                        entity.setSize(size);
                    }
                } catch (NumberFormatException e) {
                    // Left unknown: the entity is sent chunked
                }
            }
        }
        return entity;
    }

    /**
     * Target of a forwarded request: the consumed adapter's base URI, the captured path, and the
     * incoming query string as received, neither decoded nor re-encoded, so that the upstream sees
     * what the caller sent (#953), once {@link #filterForwardedQuery} has removed the names of
     * {@link #forwardExcludedQueryNames}. When the base URI already has a query, the captured
     * path goes before it and the incoming query is joined to it with {@code &}. Query input
     * parameters of the adapter are appended after it.
     */
    static String forwardTargetRef(String baseUri, String normalizedPath, String rawQuery) {
        int queryStart = baseUri.indexOf('?');
        String basePath = queryStart < 0 ? baseUri : baseUri.substring(0, queryStart);
        String baseQuery = queryStart < 0 ? "" : baseUri.substring(queryStart + 1);

        String query = baseQuery;
        if (rawQuery != null && !rawQuery.isEmpty()) {
            query = query.isEmpty() ? rawQuery : query + '&' + rawQuery;
        }
        String targetRef = basePath + normalizedPath;
        return query.isEmpty() ? targetRef : targetRef + '?' + query;
    }

    /**
     * The forwarded target once the adapter's query input parameters are appended to
     * {@code baseRef}: the caller's filtered query goes between the base URI's query and those
     * parameters, as {@link #forwardTargetRef} places it.
     *
     * @param resolvedRef {@code baseRef} with the adapter's query parameters appended
     */
    static String withCallerQuery(String baseUri, String normalizedPath, String baseRef,
            String resolvedRef, String callerQuery) {
        if (callerQuery == null || callerQuery.isEmpty()) {
            return resolvedRef;
        }
        String adapterQuery = appendedQuery(baseRef, resolvedRef);
        return forwardTargetRef(baseUri, normalizedPath,
                adapterQuery.isEmpty() ? callerQuery : callerQuery + '&' + adapterQuery);
    }

    /**
     * The query that the adapter's query input parameters appended to {@code baseRef}.
     *
     * @param resolvedRef {@code baseRef} with the adapter's query parameters appended
     * @return that query, empty when no parameter was appended
     */
    static String appendedQuery(String baseRef, String resolvedRef) {
        return resolvedRef.startsWith(baseRef) && resolvedRef.length() > baseRef.length()
                ? resolvedRef.substring(baseRef.length() + 1) : "";
    }

    /**
     * Names of the query parameters a forwarded request must not take from the caller: the
     * exposed adapter's API key when its placement is {@code query}, which must not reach the
     * upstream, and the parameters the consumed adapter sets itself, the names in the query of its
     * {@code baseUri}, the wire names of its {@code in: query} input parameters that declare a
     * {@code value} or a {@code template}, which the operator owns even when they resolve to
     * nothing, the names that its other {@code in: query} input parameters actually sent (one
     * that resolved to no value sends nothing, so the caller's value of that name is kept) and its
     * API key when its placement is {@code query}, which the caller must not repeat or override.
     * The exposed API key name is rendered with the capability bindings; the consumed one with the
     * resolved adapter inputs and the bindings, as authentication renders it.
     *
     * @param parameters the adapter's resolved input parameters
     * @param adapterQuery the query the adapter's input parameters appended, as
     *        {@link #appendedQuery} returns it
     */
    Set<String> forwardExcludedQueryNames(HttpClientAdapter httpAdapter,
            Map<String, Object> parameters, String adapterQuery) {
        Map<String, Object> bindings = getCapability().getBindings() != null
                ? getCapability().getBindings() : Map.of();
        Set<String> names = new HashSet<>(baseUriQueryNames(httpAdapter.getBaseUri()));
        names.addAll(queryNames(adapterQuery));
        names.addAll(
                templatedQueryInputNames(httpAdapter.getHttpClientSpec().getInputParameters()));
        addQueryApiKeyName(getServerSpec().getAuthentication(), bindings, names);
        addQueryApiKeyName(httpAdapter.getHttpClientSpec().getAuthentication(),
                ConsumedAuthentication.withBindings(parameters, bindings), names);
        return names;
    }

    /**
     * Wire names of the {@code in: query} input parameters that declare a {@code value} or a
     * {@code template}. The operator owns such a parameter whether or not it resolves to a value,
     * so the caller can never supply it in its place.
     *
     * @return the names, empty when {@code specs} is null
     */
    static Set<String> templatedQueryInputNames(List<InputParameterSpec> specs) {
        Set<String> names = new HashSet<>();
        if (specs == null) {
            return names;
        }
        for (InputParameterSpec spec : specs) {
            if ("query".equalsIgnoreCase(spec.getIn())
                    && (spec.getValue() != null || spec.getTemplate() != null)) {
                names.add(spec.getWireName() != null && !spec.getWireName().isBlank()
                        ? spec.getWireName() : spec.getName());
            }
        }
        return names;
    }

    /**
     * Names of the query parameters pinned in a base URI, percent-decoded the way
     * {@link #filterForwardedQuery} decodes the caller's names, so a caller cannot repeat them
     * (an upstream that takes the last value would then use the caller's).
     *
     * @return the names, empty when the base URI is null or has no query
     */
    static Set<String> baseUriQueryNames(String baseUri) {
        int queryStart = baseUri == null ? -1 : baseUri.indexOf('?');
        return queryStart < 0 ? new HashSet<>() : queryNames(baseUri.substring(queryStart + 1));
    }

    /**
     * Names of the pairs of a raw query string, percent-decoded the way
     * {@link #filterForwardedQuery} decodes the caller's names.
     *
     * @return the names, empty when the query is null or empty
     */
    static Set<String> queryNames(String rawQuery) {
        Set<String> names = new HashSet<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return names;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (!rawName.isEmpty()) {
                names.add(decodeQueryName(rawName));
            }
        }
        return names;
    }

    private static void addQueryApiKeyName(AuthenticationSpec authentication,
            Map<String, Object> bindings, Set<String> names) {
        if (authentication instanceof ApiKeyAuthenticationSpec apiKey && apiKey.isQueryPlacement()
                && apiKey.getKey() != null) {
            names.add(Secrets.resolve(apiKey.getKey(), bindings));
        }
    }

    /**
     * Removes from a raw query string every pair whose name is in {@code excludedNames}. A name is
     * compared once percent-decoded as UTF-8, with {@code +} read as a space, the way the server
     * side reads the query when it checks an API key, so {@code api%5Fkey} matches
     * {@code api_key}; the comparison is case-sensitive. The pairs that remain are forwarded as
     * received, neither decoded nor re-encoded, in their order. A name that cannot be decoded is
     * compared as received.
     *
     * @return the filtered query, empty when every pair is removed, or {@code null} if
     *         {@code rawQuery} is null
     */
    static String filterForwardedQuery(String rawQuery, Set<String> excludedNames) {
        if (rawQuery == null || rawQuery.isEmpty() || excludedNames.isEmpty()) {
            return rawQuery;
        }
        StringJoiner kept = new StringJoiner("&");
        for (String pair : rawQuery.split("&", -1)) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (!excludedNames.contains(decodeQueryName(rawName))) {
                kept.add(pair);
            }
        }
        return kept.toString();
    }

    private static String decodeQueryName(String rawName) {
        try {
            return URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return rawName;
        }
    }

    /**
     * Copy a set of trusted headers from one request to another. A standard header (If-None-Match,
     * Accept, …) is set through the Restlet property the connector writes it from, since Restlet
     * drops it when added as a raw header (#1001). A header that describes the body
     * (Content-Type, …) is already forwarded with the entity. A value that cannot be parsed is
     * not forwarded, with a warning that names the header but not the value.
     */
    void copyTrustedHeaders(Request from, Request to, Iterable<String> trustedHeaders) {
        if (trustedHeaders == null) {
            return;
        }

        for (String trustedHeader : trustedHeaders) {
            String headerValue = from.getHeaders().getFirstValue(trustedHeader, true);

            if (headerValue == null || StandardRequestHeaders.isEntityHeader(trustedHeader)) {
                continue;
            }
            try {
                StandardRequestHeaders.set(to, trustedHeader, headerValue);
            } catch (IllegalArgumentException e) {
                Context.getCurrentLogger().warning("Trusted header '" + trustedHeader
                        + "' was not forwarded: " + e.getMessage());
            }
        }
    }

    /**
     * Map client response to the operation's declared outputParameters and return a JSON string to
     * send to the client. Returns null when mapping could not be applied and the caller should fall
     * back to the raw entity.
     * 
     * Handles conversion to JSON if outputRawFormat is specified.
     */
    String mapOutputParameters(RestServerOperationSpec serverOp,
            ConsumedResult found) throws IOException {
        if (found == null || !found.hasBody()) {
            return null;
        }

        JsonNode root = found.document();
        if (root == null) {
            return null;
        }

        for (OutputParameterSpec outputParameter : serverOp.getOutputParameters()) {
            if ("body".equalsIgnoreCase(inOrDefault(outputParameter))) {
                ObjectMapper mapper = IkanosMappers.json();
                JsonNode mapped =
                        Resolver.resolveExposedOutputMappings(outputParameter, root, mapper);

                if (mapped != null && !(mapped instanceof NullNode)) {
                    return mapper.writeValueAsString(mapped);
                }
            }
        }

        return null;
    }

    /**
     * Return the `in` value for a spec, defaulting to "body" when missing.
     */
    String inOrDefault(OutputParameterSpec spec) {
        if (spec == null)
            return "body";
        String in = spec.getIn();
        return in == null ? "body" : in;
    }

    public Capability getCapability() {
        return capability;
    }

    public RestServerSpec getServerSpec() {
        return serverSpec;
    }

    public RestServerResourceSpec getResourceSpec() {
        return resourceSpec;
    }

}
