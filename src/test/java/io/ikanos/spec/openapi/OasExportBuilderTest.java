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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;


public class OasExportBuilderTest {

    private OasExportBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new OasExportBuilder();
    }

    // ── Info mapping ──

    @Test
    void buildShouldMapInfoLabelToTitle() {
        IkanosSpec spec = minimalSpec("My API", "A test API");
        OasExportResult result = builder.build(spec, null);

        assertEquals("My API", result.getOpenApi().getInfo().getTitle());
        assertEquals("A test API", result.getOpenApi().getInfo().getDescription());
    }

    @Test
    void buildShouldUseDefaultTitleWhenNoLabel() {
        IkanosSpec spec = minimalSpec(null, null);
        OasExportResult result = builder.build(spec, null);

        assertEquals("ikanos Capability", result.getOpenApi().getInfo().getTitle());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No info.label")));
    }

    // ── Server mapping ──

    @Test
    void buildShouldMapAddressAndPort() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.setAddress("localhost");
        rest.setPort(8080);

        OasExportResult result = builder.build(spec, null);

        assertEquals("http://localhost:8080",
                result.getOpenApi().getServers().get(0).getUrl());
    }

    @Test
    void buildShouldNormalizeWildcardAddress() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.setAddress("0.0.0.0");
        rest.setPort(3000);

        OasExportResult result = builder.build(spec, null);

        assertEquals("http://localhost:3000",
                result.getOpenApi().getServers().get(0).getUrl());
    }

    // ── Paths ──

    @Test
    void buildShouldCreatePathsFromResources() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", "List all pets"));
        rest.getResources().add(resourceWithOperation("/stores", "stores",
                "GET", "list-stores", "List stores"));

        OasExportResult result = builder.build(spec, null);

        assertNotNull(result.getOpenApi().getPaths().get("/pets"));
        assertNotNull(result.getOpenApi().getPaths().get("/stores"));
    }

    @Test
    void buildShouldMapOperationIdAndTags() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", "List all pets"));

        OasExportResult result = builder.build(spec, null);

        Operation op = result.getOpenApi().getPaths().get("/pets").getGet();
        assertEquals("list-pets", op.getOperationId());
        assertEquals(List.of("pets"), op.getTags());
    }

    // ── Parameters ──

    @Test
    void buildShouldMapQueryParameters() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        RestServerResourceSpec resource = resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null);

        InputParameterSpec param = new InputParameterSpec();
        param.setName("limit");
        param.setIn("query");
        param.setType("number");
        param.setRequired(false);
        resource.getOperations().get(0).getInputParameters().add(param);
        rest.getResources().add(resource);

        OasExportResult result = builder.build(spec, null);

        Operation op = result.getOpenApi().getPaths().get("/pets").getGet();
        assertEquals(1, op.getParameters().size());
        assertEquals("limit", op.getParameters().get(0).getName());
        assertEquals("query", op.getParameters().get(0).getIn());
    }

    @Test
    void buildShouldMapBodyParametersToRequestBody() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        RestServerResourceSpec resource = resourceWithOperation("/pets", "pets",
                "POST", "create-pet", null);

        InputParameterSpec nameParam = new InputParameterSpec();
        nameParam.setName("name");
        nameParam.setIn("body");
        nameParam.setType("string");
        nameParam.setRequired(true);
        resource.getOperations().get(0).getInputParameters().add(nameParam);
        rest.getResources().add(resource);

        OasExportResult result = builder.build(spec, null);

        Operation op = result.getOpenApi().getPaths().get("/pets").getPost();
        assertNotNull(op.getRequestBody());
        assertNotNull(op.getRequestBody().getContent().get("application/json"));
    }

    // ── Responses ──

    @Test
    void buildShouldMap200ResponseFromOutputParameters() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        RestServerResourceSpec resource = resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null);

        OutputParameterSpec outParam = new OutputParameterSpec();
        outParam.setName("id");
        outParam.setType("number");
        resource.getOperations().get(0).getOutputParameters().add(outParam);
        rest.getResources().add(resource);

        OasExportResult result = builder.build(spec, null);

        Operation op = result.getOpenApi().getPaths().get("/pets").getGet();
        assertNotNull(op.getResponses().get("200"));
        assertNotNull(op.getResponses().get("200").getContent());
    }

    @Test
    void buildShouldMap204ResponseWhenNoOutputParameters() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets/{petId}", "pets",
                "DELETE", "delete-pet", null));

        OasExportResult result = builder.build(spec, null);

        Operation op = result.getOpenApi().getPaths().get("/pets/{petId}").getDelete();
        assertNotNull(op.getResponses().get("204"));
    }

    @Test
    void buildShouldMapNestedObjectOutputParameters() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        RestServerResourceSpec resource = resourceWithOperation("/pets/{petId}", "pets",
                "GET", "get-pet", null);

        OutputParameterSpec address = new OutputParameterSpec();
        address.setName("address");
        address.setType("object");
        OutputParameterSpec street = new OutputParameterSpec();
        street.setName("street");
        street.setType("string");
        address.getProperties().add(street);

        resource.getOperations().get(0).getOutputParameters().add(address);
        rest.getResources().add(resource);

        OasExportResult result = builder.build(spec, null);

        assertNotNull(result.getOpenApi().getPaths().get("/pets/{petId}").getGet()
                .getResponses().get("200"));
    }

    // ── Security ──

    @Test
    void buildShouldMapBearerAuthentication() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
        bearer.setToken("{{TOKEN}}");
        rest.setAuthentication(bearer);

        OasExportResult result = builder.build(spec, null);

        assertNotNull(result.getOpenApi().getComponents());
        SecurityScheme scheme = result.getOpenApi().getComponents()
                .getSecuritySchemes().get("bearerAuth");
        assertEquals(SecurityScheme.Type.HTTP, scheme.getType());
        assertEquals("bearer", scheme.getScheme());
    }

    @Test
    void buildShouldMapBasicAuthentication() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        BasicAuthenticationSpec basic = new BasicAuthenticationSpec();
        basic.setUsername("user");
        basic.setPassword("pass".toCharArray());
        rest.setAuthentication(basic);

        OasExportResult result = builder.build(spec, null);

        SecurityScheme scheme = result.getOpenApi().getComponents()
                .getSecuritySchemes().get("basicAuth");
        assertEquals("basic", scheme.getScheme());
    }

    @Test
    void buildShouldMapApiKeyAuthentication() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setKey("X-API-Key");
        apiKey.setPlacement("header");
        rest.setAuthentication(apiKey);

        OasExportResult result = builder.build(spec, null);

        SecurityScheme scheme = result.getOpenApi().getComponents()
                .getSecuritySchemes().get("apiKeyAuth");
        assertEquals(SecurityScheme.Type.APIKEY, scheme.getType());
        assertEquals("X-API-Key", scheme.getName());
    }

    @Test
    void buildShouldMapOauth2AuthenticationWithClientCredentialsFlow() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setAuthorizationServerUri("https://auth.example.com");
        oauth2.setTokenEndpoint("https://auth.example.com/oauth/token");
        oauth2.setScopes(List.of("read:pets", "write:pets"));
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        SecurityScheme scheme = result.getOpenApi().getComponents()
                .getSecuritySchemes().get("oauth2Auth");
        assertEquals(SecurityScheme.Type.OAUTH2, scheme.getType());
        assertNotNull(scheme.getFlows());
        assertNotNull(scheme.getFlows().getClientCredentials());
        assertEquals("https://auth.example.com/oauth/token",
                scheme.getFlows().getClientCredentials().getTokenUrl());
        assertTrue(scheme.getFlows().getClientCredentials().getScopes().containsKey("read:pets"));
        assertTrue(scheme.getFlows().getClientCredentials().getScopes().containsKey("write:pets"));
        assertTrue(result.getWarnings().stream()
                .noneMatch(w -> w.contains("tokenEndpoint")));
    }

    @Test
    void buildShouldFallbackToAuthorizationServerUriWhenTokenEndpointMissing() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setAuthorizationServerUri("https://auth.example.com");
        oauth2.setScopes(List.of("read:pets"));
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        SecurityScheme scheme = result.getOpenApi().getComponents()
                .getSecuritySchemes().get("oauth2Auth");
        assertEquals("https://auth.example.com",
                scheme.getFlows().getClientCredentials().getTokenUrl());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No tokenEndpoint set")));
    }

    @Test
    void buildShouldNotSetScopesOnFlowWhenNull() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setTokenEndpoint("https://auth.example.com/oauth/token");
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        SecurityScheme scheme = result.getOpenApi().getComponents()
                .getSecuritySchemes().get("oauth2Auth");
        assertEquals(SecurityScheme.Type.OAUTH2, scheme.getType());
        assertNull(scheme.getFlows().getClientCredentials().getScopes(),
                "Scopes should not be set when source scopes are null");
    }

    @Test
    void buildShouldSetDocumentLevelSecurityForOauth2() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setTokenEndpoint("https://auth.example.com/oauth/token");
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        assertNotNull(result.getOpenApi().getSecurity());
        assertFalse(result.getOpenApi().getSecurity().isEmpty());
        assertTrue(result.getOpenApi().getSecurity().get(0).containsKey("oauth2Auth"));
    }

    @Test
    void buildShouldIncludeScopesInSecurityRequirementForOauth2() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setTokenEndpoint("https://auth.example.com/oauth/token");
        oauth2.setScopes(List.of("read:pets", "write:pets"));
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        List<String> requiredScopes =
                result.getOpenApi().getSecurity().get(0).get("oauth2Auth");
        assertNotNull(requiredScopes);
        assertEquals(2, requiredScopes.size());
        assertTrue(requiredScopes.contains("read:pets"));
        assertTrue(requiredScopes.contains("write:pets"));
    }

    // ── Edge cases ──

    @Test
    void buildShouldWarnAndSkipOauth2WhenBothTokenUrlAndIssuerAreNull() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        assertNull(result.getOpenApi().getComponents().getSecuritySchemes(),
                "No security scheme should be added when both are missing");
        assertNull(result.getOpenApi().getSecurity(),
                "No document-level security should be added when both are missing");
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("both are missing")));
    }

    @Test
    void buildShouldWarnAndSkipOauth2WhenBothTokenUrlAndIssuerAreBlank() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setTokenEndpoint("   ");
        oauth2.setAuthorizationServerUri("   ");
        rest.setAuthentication(oauth2);

        OasExportResult result = builder.build(spec, null);

        assertNull(result.getOpenApi().getComponents().getSecuritySchemes(),
                "No security scheme should be added when both are blank");
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("both are missing")));
    }

    @Test
    void buildShouldWarnWhenNoRestAdapter() {
        IkanosSpec spec = new IkanosSpec();
        spec.setCapability(new CapabilitySpec());

        OasExportResult result = builder.build(spec, null);

        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No REST adapter")));
    }

    @Test
    void buildShouldFindRestAdapterByNamespace() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.setNamespace("my-api");
        rest.getResources().add(resourceWithOperation("/data", "data",
                "GET", "get-data", null));

        OasExportResult result = builder.build(spec, "my-api");

        assertNotNull(result.getOpenApi().getPaths().get("/data"));
    }

    // ── OAS 3.1 export ──

    @Test
    void buildShouldSetOpenapi30VersionByDefault() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));

        OasExportResult result = builder.build(spec, null);

        assertEquals("3.0.3", result.getOpenApi().getOpenapi());
    }

    @Test
    void buildShouldSetOpenapi31VersionWhenRequested() {
        IkanosSpec spec = minimalSpec("Test", null);
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/pets", "pets",
                "GET", "list-pets", null));

        OasExportResult result = builder.build(spec, null, SpecVersion.V31);

        assertEquals("3.1.0", result.getOpenApi().getOpenapi());
        assertEquals(SpecVersion.V31, result.getOpenApi().getSpecVersion());
    }

    @Test
    void buildWithV31ShouldProduceReparsableSpec() {
        IkanosSpec spec = minimalSpec("Test 31", "OAS 3.1 test");
        RestServerSpec rest = getRestServer(spec);
        rest.getResources().add(resourceWithOperation("/items", "items",
                "GET", "list-items", "List all items"));

        OasExportResult result = builder.build(spec, null, SpecVersion.V31);

        String yaml = io.swagger.v3.core.util.Yaml31.pretty(result.getOpenApi());
        assertNotNull(yaml);
        assertTrue(yaml.contains("openapi: 3.1.0") || yaml.contains("openapi: \"3.1.0\""));
    }

    // ── Utility methods ──

    private IkanosSpec minimalSpec(String label, String description) {
        IkanosSpec spec = new IkanosSpec();
        spec.setIkanos("1.0.0-alpha1");
        if (label != null || description != null) {
            spec.setInfo(new InfoSpec(label, description, null, null));
        }
        CapabilitySpec capability = new CapabilitySpec();
        RestServerSpec rest = new RestServerSpec("localhost", 8080, null);
        capability.getExposes().add(rest);
        spec.setCapability(capability);
        return spec;
    }

    private RestServerSpec getRestServer(IkanosSpec spec) {
        return (RestServerSpec) spec.getCapability().getExposes().get(0);
    }

    private RestServerResourceSpec resourceWithOperation(String path, String name,
            String method, String opName, String description) {
        RestServerResourceSpec resource = new RestServerResourceSpec();
        resource.setPath(path);
        resource.setName(name);

        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setMethod(method);
        op.setName(opName);
        op.setDescription(description);
        resource.setOperations(List.of(op));

        return resource;
    }

}
