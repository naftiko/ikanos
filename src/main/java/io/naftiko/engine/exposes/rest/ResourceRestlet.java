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
package io.naftiko.engine.exposes.rest;

import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import io.naftiko.Capability;
import io.naftiko.engine.aggregates.AggregateFunction;
import io.naftiko.engine.aggregates.FunctionResult;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.engine.consumes.http.HttpClientAdapter;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.engine.util.Converter;
import io.naftiko.engine.util.Resolver;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.exposes.RestServerForwardSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restlet that handles calls to an API resource
 */
public class ResourceRestlet extends Restlet {

    private final Capability capability;
    private final RestServerSpec serverSpec;
    private final RestServerResourceSpec resourceSpec;
    private final OperationStepExecutor stepExecutor;

    public ResourceRestlet(Capability capability, RestServerSpec serverSpec,
            RestServerResourceSpec resourceSpec) {
        this.capability = capability;
        this.serverSpec = serverSpec;
        this.resourceSpec = resourceSpec;
        this.stepExecutor = new OperationStepExecutor(capability, serverSpec.getNamespace());
    }

    @Override
    public void handle(Request request, Response response) {
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
    }

    /**
     * Prepare a client request context by looking for a call configuration in the operation steps.
     * If a valid call configuration is found, constructs a client request context with the
     * corresponding client request and adapter. If an invalid call format is found, sets the
     * response to indicate a bad request and marks the context as handled.
     */
    private boolean handleFromOperationSpec(Request request, Response response) {
        OperationStepExecutor.HandlingContext found = null;

        for (RestServerOperationSpec serverOp : getResourceSpec().getOperations()) {

            if (serverOp.getMethod().equals(request.getMethod().getName())) {

                // Build request-scoped input parameter map (resource + operation)
                Map<String, Object> inputParameters =
                    stepExecutor.resolveInputParametersFromRequest(request, getServerSpec(),
                        getResourceSpec(), serverOp);

                // Include operation-level 'with' parameters for template resolution
                OperationStepExecutor.mergeWithParameters(serverOp.getWith(), inputParameters,
                        getServerSpec().getNamespace());

                // Delegate to aggregate function when ref is set
                if (serverOp.getRef() != null) {
                    return executeViaAggregate(serverOp, request, response, inputParameters);
                }

                if (serverOp.getCall() != null) {
                    try {
                        found = stepExecutor.findClientRequestFor(serverOp.getCall(),
                                inputParameters);
                    } catch (IllegalArgumentException e) {
                        Context.getCurrentLogger().warning("Error resolving request parameters: " + e);
                        response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
                        response.setEntity("Error resolving request parameters: " + e.getMessage(),
                                MediaType.TEXT_PLAIN);
                        return true;
                    }

                    if (found != null) {
                        try {
                            // Send the request to the target endpoint
                            found.handle();
                            response.setStatus(found.clientResponse.getStatus());
                        } catch (Exception e) {
                            Context.getCurrentLogger().warning("Error while handling HTTP client call in call mode: " + e);
                            response.setStatus(Status.SERVER_ERROR_INTERNAL);
                            response.setEntity(
                                    "Error while handling an HTTP client call\n\n" + e.toString(),
                                    MediaType.TEXT_PLAIN);
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
                        found = stepResult.lastContext;

                        // Apply step output mappings if defined
                        if (serverOp.getMappings() != null
                                && !serverOp.getMappings().isEmpty()) {
                            String mapped = stepExecutor.resolveStepMappings(
                                    serverOp.getMappings(), stepResult.stepContext);
                            if (mapped != null) {
                                response.setStatus(Status.SUCCESS_OK);
                                response.setEntity(mapped, MediaType.APPLICATION_JSON);
                                response.commit();
                                return true;
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        Context.getCurrentLogger().warning("Invalid argument in orchestrated steps: " + e);
                        response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
                        response.setEntity(e.getMessage(), MediaType.TEXT_PLAIN);
                        return true;
                    } catch (RuntimeException e) {
                        Context.getCurrentLogger().warning("Error while handling orchestrated steps: " + e);
                        response.setStatus(Status.SERVER_ERROR_INTERNAL);
                        response.setEntity(
                                "Error while handling an HTTP client call\n\n" + e.toString(),
                                MediaType.TEXT_PLAIN);
                        return true;
                    } catch (IOException e) {
                        Context.getCurrentLogger().warning("Error resolving step output mappings: " + e);
                        response.setStatus(Status.SERVER_ERROR_INTERNAL);
                        response.setEntity(
                                "Error resolving step output mappings\n\n" + e.toString(),
                                MediaType.TEXT_PLAIN);
                        return true;
                    }

                    if (found != null) {
                        // Return the response based on the last client request
                        response.setStatus(found.clientResponse.getStatus());
                        sendResponse(serverOp, response, found);
                        return true;
                    } else if (canBuildMockResponse(serverOp)) {
                        // No HTTP client adapter found, use mock mode with static values
                        sendMockResponse(serverOp, response, inputParameters);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Execute an operation by delegating to its referenced aggregate function.
     */
    private boolean executeViaAggregate(RestServerOperationSpec serverOp, Request request,
            Response response, Map<String, Object> inputParameters) {
        try {
            AggregateFunction fn = capability.lookupFunction(serverOp.getRef());
            FunctionResult result = fn.execute(inputParameters);

            if (result.isMock()) {
                ObjectMapper mapper = new ObjectMapper();
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

            if (result.lastContext != null) {
                response.setStatus(result.lastContext.clientResponse.getStatus());
                sendResponse(serverOp, response, result.lastContext);
                return true;
            }

            response.setStatus(Status.SERVER_ERROR_INTERNAL);
            response.setEntity("No result from aggregate function: " + serverOp.getRef(),
                    MediaType.TEXT_PLAIN);
            return true;
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().warning("Error in aggregate function call: " + e);
            response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            response.setEntity(e.getMessage(), MediaType.TEXT_PLAIN);
            return true;
        } catch (Exception e) {
            Context.getCurrentLogger().warning("Error in aggregate function call: " + e);
            response.setStatus(Status.SERVER_ERROR_INTERNAL);
            response.setEntity("Error in aggregate function call\n\n" + e.toString(),
                    MediaType.TEXT_PLAIN);
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
            ObjectMapper mapper = new ObjectMapper();

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
            Context.getCurrentLogger().warning("Error building mock response: " + e);
            response.setStatus(Status.SERVER_ERROR_INTERNAL);
            response.setEntity("Error building mock response: " + e.getMessage(),
                    MediaType.TEXT_PLAIN);
        }

        response.commit();
    }

    void sendResponse(RestServerOperationSpec serverOp, Response response,
            OperationStepExecutor.HandlingContext found) {
        // Apply output mappings if present or forward the raw entity
        if (serverOp.getOutputParameters() != null && !serverOp.getOutputParameters().isEmpty()) {
            try {
                String mapped = mapOutputParameters(serverOp, found);

                if (mapped != null) {
                    response.setEntity(mapped, MediaType.APPLICATION_JSON);
                } else {
                    response.setEntity(found.clientResponse.getEntity());
                }
            } catch (Exception e) {
                Context.getCurrentLogger().warning("Failed to map output parameters: " + e);
                response.setStatus(Status.SERVER_ERROR_INTERNAL);
                response.setEntity("Failed to map output parameters: " + e.getMessage(),
                        MediaType.TEXT_PLAIN);
            }
        } else {
            response.setEntity(found.clientResponse.getEntity());
        }

        response.commit();
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
                        String targetRef = httpAdapter.getHttpClientSpec().getBaseUri()
                                + normalizedPath;
                        Request clientRequest = new Request(request.getMethod(), targetRef);
                        clientRequest.setEntity(request.getEntity());

                        // Copy trusted headers from the original request to the client request
                        copyTrustedHeaders(request, clientRequest,
                                getResourceSpec().getForward().getTrustedHeaders());

                        // Prepare parameters map for template resolution
                        Map<String, Object> parameters = new ConcurrentHashMap<>();

                        // Resolve client input parameters first so authentication templates
                        // (e.g. bearer token from environment) can be resolved correctly
                        Resolver.resolveInputParametersToRequest(clientRequest,
                                httpAdapter.getHttpClientSpec().getInputParameters(), parameters);

                        // Set any authentication needed on the client request
                        Response clientResponse = new Response(clientRequest);
                        httpAdapter.setChallengeResponse(request, clientRequest,
                                clientRequest.getResourceRef().toString(), parameters);
                        httpAdapter.setHeaders(clientRequest);

                        // Send the request to the target endpoint
                        httpAdapter.getHttpClient().handle(clientRequest, clientResponse);
                        response.setStatus(clientResponse.getStatus());
                        response.setEntity(clientResponse.getEntity());
                        response.commit();
                        return true;
                    } catch (Exception e) {
                        Context.getCurrentLogger().warning("Error while handling HTTP client call in forward mode: " + e);
                        response.setStatus(Status.SERVER_ERROR_INTERNAL);
                        response.setEntity(
                                "Error while handling an HTTP client call\n\n" + e.toString(),
                                MediaType.TEXT_PLAIN);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Copy a set of trusted headers from one request to another.
     */
    void copyTrustedHeaders(Request from, Request to, Iterable<String> trustedHeaders) {
        if (trustedHeaders == null) {
            return;
        }

        for (String trustedHeader : trustedHeaders) {
            String headerValue = from.getHeaders().getFirstValue(trustedHeader, true);

            if (headerValue != null) {
                to.getHeaders().add(trustedHeader, headerValue);
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
            OperationStepExecutor.HandlingContext found) throws IOException {
        if (found == null || found.clientResponse == null
                || found.clientResponse.getEntity() == null) {
            return null;
        }

        JsonNode root = Converter.convertToJson(serverOp.getOutputRawFormat(),
                serverOp.getOutputSchema(), found.clientResponse.getEntity());

        for (OutputParameterSpec outputParameter : serverOp.getOutputParameters()) {
            if ("body".equalsIgnoreCase(inOrDefault(outputParameter))) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode mapped = Resolver.resolveOutputMappings(outputParameter, root, mapper);

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
