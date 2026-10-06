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
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelRouteTable;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import static org.restlet.data.Protocol.HTTP;
import static org.restlet.data.Protocol.HTTPS;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP Client Adapter implementation
 */
public class HttpClientAdapter extends ClientAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        for (HttpClientResourceSpec res : getHttpClientSpec().getResources().values()) {
            for (HttpClientOperationSpec op : res.getOperations().values()) {
                if (op.getName().equals(operationName)) {
                    return op;
                }
            }
        }

        return null;
    }

    /**
     * Build a Restlet request for one consumed operation: URI templating, client- and
     * operation-level input parameters, body, authentication and default headers.
     *
     * @return the prepared invocation, or {@code null} when the operation is not declared
     * @throws IllegalArgumentException when URI or body templates cannot be resolved
     */
    @Override
    public HttpInvocation prepare(String operationName, Map<String, Object> parameters) {
        HttpClientOperationSpec clientOp = getOperationSpec(operationName);
        if (clientOp == null) {
            return null;
        }

        String clientResUri = getHttpClientSpec().getBaseUri()
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

        Request clientRequest = new Request();
        clientRequest.setMethod(Method.valueOf(clientOp.getMethod()));
        clientRequest.setResourceRef(new Reference(
                Resolver.resolveMustacheTemplate(clientResUri, parameters)));

        // Apply client-level and operation-level input parameters
        // NOTE: setResourceRef must be called first so that query params
        // (in: query) are appended to the correct base URI, not to null.
        Resolver.resolveInputParametersToRequest(clientRequest,
                getHttpClientSpec().getInputParameters(), parameters);
        Resolver.resolveInputParametersToRequest(clientRequest,
                clientOp.getInputParameters(), parameters);

        if (clientOp.getBody() != null) {
            String resolvedBody;
            MediaType bodyMediaType = MediaType.APPLICATION_JSON;

            Object bodySpec = clientOp.getBody();
            if (bodySpec instanceof String) {
                // Legacy: plain Mustache template string
                resolvedBody = Resolver.resolveMustacheTemplate((String) bodySpec, parameters);
            } else {
                // Structured {type, data} RequestBody object
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = (Map<String, Object>) bodySpec;
                String bodyType = String.valueOf(bodyMap.getOrDefault("type", "json"));
                Object data = bodyMap.get("data");
                String dataStr;
                try {
                    dataStr = JSON.writeValueAsString(data);
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "Invalid structured body data for operation: "
                                    + getHttpClientSpec().getNamespace() + "." + operationName,
                            e);
                }
                resolvedBody = Resolver.resolveMustacheTemplate(dataStr, parameters);
                if ("formUrlEncoded".equalsIgnoreCase(bodyType)) {
                    bodyMediaType = MediaType.APPLICATION_WWW_FORM;
                } else if ("xml".equalsIgnoreCase(bodyType)) {
                    bodyMediaType = MediaType.APPLICATION_XML;
                } else if ("sparql".equalsIgnoreCase(bodyType)) {
                    bodyMediaType = MediaType.valueOf("application/sparql-query");
                }
            }

            if (resolvedBody.contains("{{") && resolvedBody.contains("}}")) {
                throw new IllegalArgumentException(
                        "Unresolved template parameters in body: " + resolvedBody
                                + ". Available parameters: "
                                + (parameters != null ? parameters.keySet() : "none"));
            }

            clientRequest.setEntity(resolvedBody, bodyMediaType);
        }

        // Set authentication and headers
        setChallengeResponse(null, clientRequest, clientRequest.getResourceRef().toString(),
                parameters);
        setHeaders(clientRequest);
        return new HttpInvocation(this, clientOp, clientRequest);
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
        ConsumedAuthentication.apply(getHttpClientSpec().getAuthentication(), serverRequest,
                clientRequest, targetRef, ConsumedAuthentication.withBindings(parameters,
                        getCapability() != null ? getCapability().getBindings() : null));
    }

    public Client getHttpClient() {
        return httpClient;
    }

    /**
     * Package-private test helper. Returns the {@link TunnelRouteTable} installed on this
     * adapter's underlying Restlet {@link Client} context, or {@code null} when this adapter
     * is not tunnel-routed.
     *
     * <p>This indirection lets tests assert tunnel-routing behaviour without reaching
     * through the Restlet {@link Context} / attributes API in every assertion. If a future
     * refactor changes how the route table is stored (e.g. moves it off the Restlet context
     * to a dedicated field, or to a lazy-init slot for connection-pool reset), only this
     * single method needs updating — the test suite stays green.
     */
    TunnelRouteTable tunnelRouteTable() {
        if (httpClient == null) {
            return null;
        }
        Context context = httpClient.getContext();
        if (context == null) {
            return null;
        }
        Object attribute = context.getAttributes().get(TunnelRouteTable.CONTEXT_ATTRIBUTE);
        return attribute instanceof TunnelRouteTable table ? table : null;
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
