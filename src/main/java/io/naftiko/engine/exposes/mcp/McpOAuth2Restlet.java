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
package io.naftiko.engine.exposes.mcp;

import java.net.URI;
import java.util.List;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.JWKSet;
import io.naftiko.engine.exposes.OAuth2AuthenticationRestlet;
import io.naftiko.spec.consumes.OAuth2AuthenticationSpec;

/**
 * MCP-specific OAuth 2.1 Restlet that extends {@link OAuth2AuthenticationRestlet} with
 * MCP 2025-11-25 protocol behavior:
 * <ul>
 *   <li>Auto-serves Protected Resource Metadata (RFC 9728) at the well-known path</li>
 *   <li>Includes {@code resource_metadata} URL in {@code WWW-Authenticate} headers</li>
 * </ul>
 */
public class McpOAuth2Restlet extends OAuth2AuthenticationRestlet {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String metadataPath;
    private final String metadataUrl;
    private final String metadataJson;

    public McpOAuth2Restlet(OAuth2AuthenticationSpec spec, Restlet next) {
        super(spec, next);

        URI resourceUri = URI.create(spec.getResource());
        String resourcePath = resourceUri.getPath();
        if (resourcePath == null || "/".equals(resourcePath) || resourcePath.isEmpty()) {
            this.metadataPath = "/.well-known/oauth-protected-resource";
        } else {
            this.metadataPath = "/.well-known/oauth-protected-resource" + resourcePath;
        }
        this.metadataUrl = resourceUri.getScheme() + "://" + resourceUri.getAuthority()
                + metadataPath;
        this.metadataJson = buildProtectedResourceMetadata(spec);
    }

    /**
     * Test constructor — allows injecting a pre-loaded JWK set.
     */
    McpOAuth2Restlet(OAuth2AuthenticationSpec spec, Restlet next, JWKSet jwkSet) {
        super(spec, next, jwkSet);

        URI resourceUri = URI.create(spec.getResource());
        String resourcePath = resourceUri.getPath();
        if (resourcePath == null || "/".equals(resourcePath) || resourcePath.isEmpty()) {
            this.metadataPath = "/.well-known/oauth-protected-resource";
        } else {
            this.metadataPath = "/.well-known/oauth-protected-resource" + resourcePath;
        }
        this.metadataUrl = resourceUri.getScheme() + "://" + resourceUri.getAuthority()
                + metadataPath;
        this.metadataJson = buildProtectedResourceMetadata(spec);
    }

    @Override
    public void handle(Request request, Response response) {
        String path = request.getResourceRef() != null ? request.getResourceRef().getPath() : null;
        if (Method.GET.equals(request.getMethod()) && metadataPath.equals(path)) {
            serveProtectedResourceMetadata(response);
            return;
        }

        super.handle(request, response);
    }

    @Override
    protected String buildBearerChallengeParams(String error, String description, String scope) {
        String baseParams = super.buildBearerChallengeParams(error, description, scope);
        String metadata = "resource_metadata=\"" + metadataUrl + "\"";
        if (baseParams.isEmpty()) {
            return metadata;
        }
        return baseParams + ", " + metadata;
    }

    void serveProtectedResourceMetadata(Response response) {
        response.setStatus(Status.SUCCESS_OK);
        response.setEntity(metadataJson, MediaType.APPLICATION_JSON);
    }

    String getMetadataPath() {
        return metadataPath;
    }

    String getMetadataUrl() {
        return metadataUrl;
    }

    private static String buildProtectedResourceMetadata(OAuth2AuthenticationSpec spec) {
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("resource", spec.getResource());

        ArrayNode authServers = metadata.putArray("authorization_servers");
        authServers.add(spec.getAuthorizationServerUrl());

        List<String> scopes = spec.getScopes();
        if (scopes != null && !scopes.isEmpty()) {
            ArrayNode scopesArray = metadata.putArray("scopes_supported");
            for (String scope : scopes) {
                scopesArray.add(scope);
            }
        }

        ArrayNode bearerMethods = metadata.putArray("bearer_methods_supported");
        bearerMethods.add("header");

        try {
            return JSON.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Protected Resource Metadata", e);
        }
    }

}
