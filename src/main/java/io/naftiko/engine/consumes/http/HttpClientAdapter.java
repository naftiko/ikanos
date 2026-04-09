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
package io.naftiko.engine.consumes.http;

import org.restlet.Client;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import io.naftiko.Capability;
import io.naftiko.engine.Resolver;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.AuthenticationSpec;
import io.naftiko.spec.consumes.BasicAuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;
import io.naftiko.spec.consumes.DigestAuthenticationSpec;
import io.naftiko.spec.consumes.HttpClientOperationSpec;
import io.naftiko.spec.consumes.HttpClientResourceSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import static org.restlet.data.Protocol.HTTP;
import static org.restlet.data.Protocol.HTTPS;
import java.util.Map;

/**
 * HTTP Client Adapter implementation
 */
public class HttpClientAdapter extends ClientAdapter {

    private final Client httpClient;

    public HttpClientAdapter(Capability capability, HttpClientSpec spec) {
        super(capability, spec);
        this.httpClient = new Client(HTTP, HTTPS);
    }

    public HttpClientSpec getHttpClientSpec() {
        return (HttpClientSpec) getSpec();
    }

    /**
     * Finds the HttpOperationSpec for a given operationId by searching through all resources and
     * their operations.
     * 
     * @param operationName The ID of the operation to find
     * @return The HttpOperationSpec if found, or null if not found
     */
    public HttpClientOperationSpec getOperationSpec(String operationName) {
        for (HttpClientResourceSpec res : getHttpClientSpec().getResources()) {
            for (HttpClientOperationSpec op : res.getOperations()) {
                if (op.getName().equals(operationName)) {
                    return op;
                }
            }
        }

        return null;
    }

    /**
     * Set any default headers from the input parameters on the client request
     */
    public void setHeaders(Request request) {
        // Set any default headers from the input parameters
        for (InputParameterSpec param : getHttpClientSpec().getInputParameters()) {
            if ("header".equalsIgnoreCase(param.getIn()) && param.getValue() != null) {
                request.getHeaders().set(param.getName(), param.getValue());
            }
        }
    }

    /**
     * Set the appropriate authentication headers on the client request based on the specification
     */
    public void setChallengeResponse(Request serverRequest, Request clientRequest, String targetRef,
            Map<String, Object> parameters) {
        AuthenticationSpec authenticationSpec = getHttpClientSpec().getAuthentication();

        if (authenticationSpec != null) {
            // Add authentication headers if needed
            String type = authenticationSpec.getType();
            ChallengeResponse challengeResponse = null;

            switch (type) {
                case "basic":
                    BasicAuthenticationSpec basicAuth =
                            (BasicAuthenticationSpec) authenticationSpec;
                    challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_BASIC);
                    challengeResponse.setIdentifier(
                            Resolver.resolveMustacheTemplate(basicAuth.getUsername(), parameters));
                    challengeResponse.setSecret(Resolver
                            .resolveMustacheTemplate(basicAuth.getPassword().toString(), parameters)
                            .toCharArray());
                    clientRequest.setChallengeResponse(challengeResponse);
                    break;

                case "digest":
                    DigestAuthenticationSpec digestAuth =
                            (DigestAuthenticationSpec) authenticationSpec;
                    challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_DIGEST);
                    challengeResponse.setIdentifier(
                            Resolver.resolveMustacheTemplate(digestAuth.getUsername(), parameters));
                    challengeResponse.setSecret(Resolver.resolveMustacheTemplate(
                            digestAuth.getPassword().toString(), parameters).toCharArray());
                    clientRequest.setChallengeResponse(challengeResponse);
                    break;

                case "bearer":
                    BearerAuthenticationSpec bearerAuth =
                            (BearerAuthenticationSpec) authenticationSpec;
                    challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_OAUTH_BEARER);
                    challengeResponse.setRawValue(
                        Resolver.resolveMustacheTemplate(bearerAuth.getToken(), parameters));
                    clientRequest.setChallengeResponse(challengeResponse);
                    break;

                case "apikey":
                    ApiKeyAuthenticationSpec apiKeyAuth =
                            (ApiKeyAuthenticationSpec) authenticationSpec;
                    String key = Resolver.resolveMustacheTemplate(apiKeyAuth.getKey(), parameters);
                    String value =
                            Resolver.resolveMustacheTemplate(apiKeyAuth.getValue(), parameters);
                    String placement = apiKeyAuth.getPlacement();

                    if (placement.equals("header")) {
                        clientRequest.getHeaders().add(key, value);
                    } else if (placement.equals("query")) {
                        String separator = targetRef.contains("?") ? "&" : "?";
                        String newTargetRef = targetRef + separator + key + "=" + value;
                        clientRequest.setResourceRef(newTargetRef);
                    }
                    break;

                default:
                    break;
            }
        } else if(serverRequest != null && serverRequest.getChallengeResponse() != null) {
            // Use existing challenge response if present
            clientRequest.setChallengeResponse(serverRequest.getChallengeResponse());
        }
    }

    public Client getHttpClient() {
        return httpClient;
    }

    @Override
    public void start() throws Exception {
        getHttpClient().start();
    }

    @Override
    public void stop() throws Exception {
        getHttpClient().stop();
    }

}
