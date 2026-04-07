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
import io.naftiko.engine.Converter;
import io.naftiko.engine.Resolver;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.engine.consumes.http.HttpClientAdapter;
import io.naftiko.engine.exposes.OperationStepExecutor;
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
        this.stepExecutor = new OperationStepExecutor(capability);
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
                        // No HTTP client adapter found, use mock mode with const values
                        sendMockResponse(serverOp, response);
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
                        // No HTTP client adapter found, use mock mode with const values
                        sendMockResponse(serverOp, response);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Check if an operation can build a mock response using const values from outputParameters.
     * Returns true if the operation has at least one outputParameter with a const value.
     */
    boolean canBuildMockResponse(RestServerOperationSpec serverOp) {
        if (serverOp.getOutputParameters() == null || serverOp.getOutputParameters().isEmpty()) {
            return false;
        }

        // Check if at least one output parameter has a const value
        for (OutputParameterSpec param : serverOp.getOutputParameters()) {
            if (param.getConstant() != null) {
                return true;
            }
            // Check nested properties for const values
            if (param.getProperties() != null && !param.getProperties().isEmpty()) {
                for (OutputParameterSpec prop : param.getProperties()) {
                    if (hasConstValue(prop)) {
                        return true;
                    }
                }
            }
            // Check items for const values
            if (param.getItems() != null && hasConstValue(param.getItems())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recursively check if a parameter or its nested structure has any const values.
     */
    private boolean hasConstValue(OutputParameterSpec param) {
        if (param == null) {
            return false;
        }

        if (param.getConstant() != null) {
            return true;
        }

        if (param.getProperties() != null) {
            for (OutputParameterSpec prop : param.getProperties()) {
                if (hasConstValue(prop)) {
                    return true;
                }
            }
        }

        if (param.getItems() != null) {
            return hasConstValue(param.getItems());
        }

        return false;
    }

    /**
     * Send a mock response using const values from outputParameters.
     */
    void sendMockResponse(RestServerOperationSpec serverOp, Response response) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            // Build a JSON response using const values from outputParameters
            JsonNode mockRoot = buildMockData(serverOp, mapper);

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

    /**
     * Build a JSON object with mock data from outputParameters const values.
     */
    private JsonNode buildMockData(RestServerOperationSpec serverOp, ObjectMapper mapper) {
        if (serverOp.getOutputParameters() == null || serverOp.getOutputParameters().isEmpty()) {
            return null;
        }

        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();

        for (OutputParameterSpec param : serverOp.getOutputParameters()) {
            JsonNode paramValue = buildParameterValue(param, mapper);
            if (paramValue != null && !(paramValue instanceof NullNode)) {
                // Use the parameter name if available, otherwise use "value"
                String fieldName = param.getName() != null ? param.getName() : "value";
                result.set(fieldName, paramValue);
            }
        }

        return result.size() > 0 ? result : null;
    }

    /**
     * Build a JSON node for a single parameter, using const values or structures.
     */
    JsonNode buildParameterValue(OutputParameterSpec param, ObjectMapper mapper) {
        if (param == null) {
            return NullNode.instance;
        }

        // Handle const values directly
        if (param.getConstant() != null) {
            return mapper.getNodeFactory().textNode(param.getConstant());
        }

        String type = param.getType();

        // Handle array types
        if ("array".equalsIgnoreCase(type)) {
            com.fasterxml.jackson.databind.node.ArrayNode arrayNode = mapper.createArrayNode();
            OutputParameterSpec items = param.getItems();

            if (items != null) {
                // Create one mock item to demonstrate the structure
                JsonNode itemValue = buildParameterValue(items, mapper);
                if (itemValue != null && !(itemValue instanceof NullNode)) {
                    arrayNode.add(itemValue);
                }
            }

            return arrayNode;
        }

        // Handle object types
        if ("object".equalsIgnoreCase(type)) {
            com.fasterxml.jackson.databind.node.ObjectNode objectNode = mapper.createObjectNode();

            if (param.getProperties() != null) {
                for (OutputParameterSpec prop : param.getProperties()) {
                    JsonNode propValue = buildParameterValue(prop, mapper);
                    if (propValue != null && !(propValue instanceof NullNode)) {
                        String propName = prop.getName() != null ? prop.getName() : "property";
                        objectNode.set(propName, propValue);
                    }
                }
            }

            return objectNode.size() > 0 ? objectNode : NullNode.instance;
        }

        // For other types without const values, return null
        return NullNode.instance;
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
