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
package io.ikanos.engine.consumes.http;

import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelRouteTable;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import static org.restlet.data.Protocol.HTTP;
import static org.restlet.data.Protocol.HTTPS;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * HTTP Client Adapter implementation
 */
public class HttpClientAdapter extends ClientAdapter {

    /**
     * Fully-qualified class name of the {@link TunnelAwareHttpClientHelper} subclass that
     * Restlet instantiates reflectively when this adapter routes requests through a tunnel.
     * Kept as a string constant so the engine module need not statically depend on the helper
     * class via the Restlet helper-resolution path.
     */
    static final String TUNNEL_AWARE_HELPER_CLASS_NAME =
            "io.ikanos.engine.consumes.http.TunnelAwareHttpClientHelper";

    private final Client httpClient;

    public HttpClientAdapter(Capability capability, HttpClientSpec spec) {
        this(capability, spec, Map.of());
    }

    /**
     * Tunnel-aware constructor used by {@link Capability} bootstrap.
     *
     * <p>When {@code spec.getTunnel()} is non-null AND {@code tunnels} contains an entry for
     * the tunnel's type, the underlying Restlet {@link Client} is created with a custom
     * {@link TunnelAwareHttpClientHelper} that installs a Jetty request listener routing
     * matching hosts through the tunnel. Otherwise this behaves identically to the 2-arg
     * constructor (direct internet path).
     *
     * @param capability the owning capability
     * @param spec the consumed-HTTP spec
     * @param tunnels {@code tunnel.type → Tunnel} map of started tunnel instances; an empty
     *     map disables tunnel routing
     */
    public HttpClientAdapter(
            Capability capability, HttpClientSpec spec, Map<String, Tunnel> tunnels) {
        super(capability, spec);
        Tunnel tunnel = selectTunnel(spec, tunnels);
        if (tunnel == null) {
            this.httpClient = new Client(HTTP, HTTPS);
        } else {
            TunnelRouteTable routes = new TunnelRouteTable();
            routes.register(extractHost(spec), tunnel);
            Context restletContext = new Context();
            restletContext.getAttributes().put(TunnelRouteTable.CONTEXT_ATTRIBUTE, routes);
            this.httpClient = new Client(
                    restletContext, List.of(HTTP, HTTPS), TUNNEL_AWARE_HELPER_CLASS_NAME);
        }
    }

    /**
     * Package-private for testing. Resolves the {@link Tunnel} instance that should serve
     * {@code spec}, or {@code null} when no tunnel is configured or available.
     */
    static Tunnel selectTunnel(HttpClientSpec spec, Map<String, Tunnel> tunnels) {
        if (spec == null || spec.getTunnel() == null || tunnels == null || tunnels.isEmpty()) {
            return null;
        }
        return tunnels.get(spec.getTunnel().getType());
    }

    /**
     * Package-private for testing. Extracts the host portion of {@link HttpClientSpec#getBaseUri()}
     * for use as the {@link TunnelRouteTable} key.
     */
    static String extractHost(HttpClientSpec spec) {
        String baseUri = spec.getBaseUri();
        if (baseUri == null || baseUri.isBlank()) {
            throw new IllegalArgumentException(
                    "ConsumesHttp.baseUri is required when tunnel is configured");
        }
        try {
            URI uri = new URI(baseUri);
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalArgumentException(
                        "ConsumesHttp.baseUri must include a host: " + baseUri);
            }
            return host;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(
                    "Invalid ConsumesHttp.baseUri: " + baseUri, ex);
        }
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
                            .resolveMustacheTemplate(new String(basicAuth.getPassword()), parameters)
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
                            new String(digestAuth.getPassword()), parameters).toCharArray());
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

                    if (placement == null) {
                        throw new IllegalArgumentException(
                                "Placement is required for apikey authentication (expected: header or query)");
                    }

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
