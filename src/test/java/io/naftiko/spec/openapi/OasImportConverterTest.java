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
package io.naftiko.spec.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.BasicAuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;
import io.naftiko.spec.consumes.DigestAuthenticationSpec;
import io.naftiko.spec.consumes.HttpClientOperationSpec;


public class OasImportConverterTest {

    private OasImportConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OasImportConverter();
    }

    // ── Namespace derivation ──

    @Test
    void convertShouldDeriveNamespaceFromInfoTitle() {
        OpenAPI openApi = minimalOpenApi("Petstore API");
        OasImportResult result = converter.convert(openApi);

        assertEquals("petstore-api", result.getHttpClient().getNamespace());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void convertShouldUsePlaceholderNamespaceWhenNoTitle() {
        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(new Info());
        openApi.setServers(List.of(server("https://api.example.com")));
        openApi.setPaths(new Paths());

        OasImportResult result = converter.convert(openApi);

        assertEquals("unknown-api", result.getHttpClient().getNamespace());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No info.title")));
    }

    @Test
    void convertShouldKebabCaseCamelCaseTitle() {
        OpenAPI openApi = minimalOpenApi("MyAwesomeService");
        OasImportResult result = converter.convert(openApi);

        assertEquals("my-awesome-service", result.getHttpClient().getNamespace());
    }

    // ── BaseUri derivation ──

    @Test
    void convertShouldDeriveBaseUriFromFirstServer() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setServers(List.of(
                server("https://api.petstore.io/v1"),
                server("https://staging.petstore.io/v1")));

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.petstore.io/v1", result.getHttpClient().getBaseUri());
    }

    @Test
    void convertShouldStripTrailingSlashFromBaseUri() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setServers(List.of(server("https://api.petstore.io/v1/")));

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.petstore.io/v1", result.getHttpClient().getBaseUri());
    }

    @Test
    void convertShouldUsePlaceholderBaseUriWhenNoServers() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setServers(null);

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.example.com", result.getHttpClient().getBaseUri());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No servers defined")));
    }

    // ── Resource grouping ──

    @Test
    void convertShouldGroupOperationsByTag() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        paths.addPathItem("/pets", pathItem("GET",
                operation("listPets", "List all pets", List.of("Pets"))));
        paths.addPathItem("/pets/{petId}", pathItem("GET",
                operation("showPetById", "Get a pet", List.of("Pets"))));
        paths.addPathItem("/stores", pathItem("GET",
                operation("listStores", "List stores", List.of("Stores"))));

        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertEquals(2, result.getHttpClient().getResources().size());
        assertEquals("pets", result.getHttpClient().getResources().get(0).getName());
        assertEquals("stores", result.getHttpClient().getResources().get(1).getName());
    }

    @Test
    void convertShouldFallbackToPathSegmentWhenNoTag() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();
        paths.addPathItem("/users/{id}", pathItem("GET",
                operation("getUser", "Get user", null)));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertEquals(1, result.getHttpClient().getResources().size());
        assertEquals("users", result.getHttpClient().getResources().get(0).getName());
    }

    // ── Operation name derivation ──

    @Test
    void convertShouldKebabCaseOperationId() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();
        paths.addPathItem("/pets", pathItem("GET",
                operation("listAllPets", "List pets", List.of("Pets"))));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        HttpClientOperationSpec op = result.getHttpClient().getResources().get(0)
                .getOperations().get(0);
        assertEquals("list-all-pets", op.getName());
    }

    @Test
    void convertShouldSynthesizeOperationNameWhenNoOperationId() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();
        Operation op = new Operation();
        op.setTags(List.of("Pets"));
        paths.addPathItem("/pets/{petId}", pathItem("DELETE", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        HttpClientOperationSpec opSpec = result.getHttpClient().getResources().get(0)
                .getOperations().get(0);
        assertEquals("delete-pets-pet-id", opSpec.getName());
    }

    // ── Input parameters ──

    @Test
    void convertShouldMapPathAndQueryParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("getPet", "Get pet", List.of("Pets"));
        op.setParameters(List.of(
                parameter("petId", "path", "integer", true),
                parameter("fields", "query", "string", false)));

        paths.addPathItem("/pets/{petId}", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<InputParameterSpec> inputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getInputParameters();
        assertEquals(2, inputs.size());
        assertEquals("petId", inputs.get(0).getName());
        assertEquals("path", inputs.get(0).getIn());
        assertEquals("number", inputs.get(0).getType());
        assertTrue(inputs.get(0).isRequired());

        assertEquals("fields", inputs.get(1).getName());
        assertEquals("query", inputs.get(1).getIn());
        assertEquals("string", inputs.get(1).getType());
        assertFalse(inputs.get(1).isRequired());
    }

    @Test
    void convertShouldMapRequestBodyPropertiesToBodyParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("createPet", "Create pet", List.of("Pets"));
        RequestBody body = new RequestBody();
        ObjectSchema bodySchema = new ObjectSchema();
        bodySchema.addProperty("name", new StringSchema());
        bodySchema.addProperty("age", new IntegerSchema());
        bodySchema.setRequired(List.of("name"));
        body.setContent(new Content().addMediaType("application/json",
                new MediaType().schema(bodySchema)));
        op.setRequestBody(body);

        paths.addPathItem("/pets", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<InputParameterSpec> inputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getInputParameters();
        assertEquals(2, inputs.size());
        assertEquals("body", inputs.get(0).getIn());
        assertEquals("body", inputs.get(1).getIn());
    }

    // ── Output parameters ──

    @Test
    void convertShouldMapSuccessResponseToOutputParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("getPet", "Get pet", List.of("Pets"));
        ApiResponses responses = new ApiResponses();
        ObjectSchema responseSchema = new ObjectSchema();
        responseSchema.addProperty("id", new IntegerSchema());
        responseSchema.addProperty("name", new StringSchema());
        responseSchema.addProperty("active", new BooleanSchema());
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(responseSchema))));
        op.setResponses(responses);

        paths.addPathItem("/pets/{petId}", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getOutputParameters();
        assertEquals(3, outputs.size());
        assertEquals("id", outputs.get(0).getName());
        assertEquals("number", outputs.get(0).getType());
        assertEquals("$.id", outputs.get(0).getMapping());
    }

    @Test
    void convertShouldMapNestedObjectOutputParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("getPet", "Get pet", List.of("Pets"));
        ObjectSchema responseSchema = new ObjectSchema();
        ObjectSchema addressSchema = new ObjectSchema();
        addressSchema.addProperty("street", new StringSchema());
        addressSchema.addProperty("city", new StringSchema());
        responseSchema.addProperty("address", addressSchema);

        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(responseSchema))));
        op.setResponses(responses);

        paths.addPathItem("/pets/{petId}", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        OutputParameterSpec addressOut = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getOutputParameters().get(0);
        assertEquals("address", addressOut.getName());
        assertEquals("object", addressOut.getType());
        assertEquals(2, addressOut.getProperties().size());
    }

    @Test
    void convertShouldMapArrayOutputParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("listPets", "List", List.of("Pets"));
        ArraySchema arraySchema = new ArraySchema();
        ObjectSchema itemSchema = new ObjectSchema();
        itemSchema.addProperty("id", new IntegerSchema());
        itemSchema.addProperty("name", new StringSchema());
        arraySchema.setItems(itemSchema);

        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(arraySchema))));
        op.setResponses(responses);

        paths.addPathItem("/pets", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getOutputParameters();
        assertEquals(1, outputs.size());
        assertEquals("array", outputs.get(0).getType());
        assertEquals("$[*]", outputs.get(0).getMapping());
    }

    @Test
    void convertShouldExcludeWriteOnlyOutputParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("getPet", "Get pet", List.of("Pets"));
        ObjectSchema responseSchema = new ObjectSchema();
        responseSchema.addProperty("name", new StringSchema());
        StringSchema writeOnly = new StringSchema();
        writeOnly.setWriteOnly(true);
        responseSchema.addProperty("password", writeOnly);

        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(responseSchema))));
        op.setResponses(responses);

        paths.addPathItem("/pets/{petId}", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getOutputParameters();
        assertEquals(1, outputs.size());
        assertEquals("name", outputs.get(0).getName());
    }

    @Test
    void convertShouldHandleNoOutputParameters() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();
        Operation op = operation("deletePet", "Delete", List.of("Pets"));
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("204", new ApiResponse().description("Deleted"));
        op.setResponses(responses);
        paths.addPathItem("/pets/{petId}", pathItem("DELETE", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertTrue(result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getOutputParameters().isEmpty());
    }

    // ── Authentication ──

    @Test
    void convertShouldMapApiKeyAuthentication() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .name("X-API-Key")
                .in(SecurityScheme.In.HEADER);
        components.addSecuritySchemes("apiKey", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertInstanceOf(ApiKeyAuthenticationSpec.class, result.getHttpClient().getAuthentication());
        ApiKeyAuthenticationSpec apiKey =
                (ApiKeyAuthenticationSpec) result.getHttpClient().getAuthentication();
        assertEquals("X-API-Key", apiKey.getKey());
        assertEquals("header", apiKey.getPlacement());
    }

    @Test
    void convertShouldMapBearerAuthentication() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer");
        components.addSecuritySchemes("bearerAuth", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertInstanceOf(BearerAuthenticationSpec.class, result.getHttpClient().getAuthentication());
    }

    @Test
    void convertShouldMapBasicAuthentication() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("basic");
        components.addSecuritySchemes("basicAuth", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertInstanceOf(BasicAuthenticationSpec.class, result.getHttpClient().getAuthentication());
    }

    @Test
    void convertShouldMapDigestAuthentication() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("digest");
        components.addSecuritySchemes("digestAuth", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertInstanceOf(DigestAuthenticationSpec.class, result.getHttpClient().getAuthentication());
        DigestAuthenticationSpec digest =
                (DigestAuthenticationSpec) result.getHttpClient().getAuthentication();
        assertEquals("{{USERNAME}}", digest.getUsername());
        assertEquals("{{PASSWORD}}", new String(digest.getPassword()));
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("Digest authentication mapped")));
    }

    @Test
    void convertShouldWarnOnOAuth2Authentication() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2);
        components.addSecuritySchemes("oauth", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertNull(result.getHttpClient().getAuthentication());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("oauth2")));
    }

    @Test
    void convertShouldWarnOnOpenIdConnectAuthentication() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.OPENIDCONNECT);
        components.addSecuritySchemes("oidc", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertNull(result.getHttpClient().getAuthentication());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("openIdConnect")));
    }

    // ── Edge cases ──

    @Test
    void convertShouldHandleEmptyPathsGracefully() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(null);

        OasImportResult result = converter.convert(openApi);

        assertTrue(result.getHttpClient().getResources().isEmpty());
    }

    // ── Helper: toKebabCase ──

    @Test
    void toKebabCaseShouldConvertCamelCase() {
        assertEquals("list-all-pets", OasImportConverter.toKebabCase("listAllPets"));
    }

    @Test
    void toKebabCaseShouldConvertSpacesAndSpecialChars() {
        assertEquals("petstore-api", OasImportConverter.toKebabCase("Petstore API"));
    }

    // ── Helper: mapSchemaType ──

    @Test
    void mapSchemaTypeShouldMapIntegerToNumber() {
        assertEquals("number", OasImportConverter.mapSchemaType("integer"));
    }

    @Test
    void mapSchemaTypeShouldPreserveString() {
        assertEquals("string", OasImportConverter.mapSchemaType("string"));
    }

    // ── OAS 3.1: resolveSchemaType ──

    @Test
    void resolveSchemaTypeShouldReturnTypeFromGetType() {
        Schema<?> schema = new StringSchema();
        assertEquals("string", OasImportConverter.resolveSchemaType(schema));
    }

    @Test
    void resolveSchemaTypeShouldReturnTypeFromGetTypesWhenGetTypeIsNull() {
        Schema<?> schema = new Schema<>(SpecVersion.V31);
        schema.setTypes(Set.of("string", "null"));
        assertNull(schema.getType(), "getType() should be null for OAS 3.1 array types");
        assertEquals("string", OasImportConverter.resolveSchemaType(schema));
    }

    @Test
    void resolveSchemaTypeShouldPreferNonNullType() {
        Schema<?> schema = new Schema<>(SpecVersion.V31);
        schema.setTypes(Set.of("integer", "null"));
        assertEquals("integer", OasImportConverter.resolveSchemaType(schema));
    }

    @Test
    void resolveSchemaTypeShouldReturnNullWhenBothEmpty() {
        Schema<?> schema = new Schema<>();
        assertNull(OasImportConverter.resolveSchemaType(schema));
    }

    // ── OAS 3.1: nullable parameter via type array ──

    @Test
    void convertShouldMapOas31NullableParameterType() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("getItem", "Get", List.of("Items"));
        Parameter param = new Parameter();
        param.setName("status");
        param.setIn("query");
        Schema<?> nullableString = new Schema<>(SpecVersion.V31);
        nullableString.setTypes(Set.of("string", "null"));
        param.setSchema(nullableString);
        op.setParameters(List.of(param));

        paths.addPathItem("/items", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        InputParameterSpec input = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getInputParameters().get(0);
        assertEquals("string", input.getType());
    }

    // ── OAS 3.1: output parameter with type array ──

    @Test
    void convertShouldMapOas31NullableOutputProperty() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("getItem", "Get", List.of("Items"));
        Schema<?> responseSchema = new Schema<>(SpecVersion.V31);
        responseSchema.setTypes(Set.of("object"));
        Schema<?> nullableProp = new Schema<>(SpecVersion.V31);
        nullableProp.setTypes(Set.of("string", "null"));
        responseSchema.addProperty("nickname", nullableProp);

        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(responseSchema))));
        op.setResponses(responses);

        paths.addPathItem("/items/{id}", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getOutputParameters();
        assertEquals(1, outputs.size());
        assertEquals("nickname", outputs.get(0).getName());
        assertEquals("string", outputs.get(0).getType());
    }

    // ── OAS 3.1: request body with type array ──

    @Test
    void convertShouldMapOas31RequestBodyWithNullableProperty() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("createItem", "Create", List.of("Items"));
        Schema<?> bodySchema = new Schema<>(SpecVersion.V31);
        bodySchema.setTypes(Set.of("object"));
        Schema<?> nullableField = new Schema<>(SpecVersion.V31);
        nullableField.setTypes(Set.of("string", "null"));
        bodySchema.addProperty("description", nullableField);

        RequestBody body = new RequestBody();
        body.setContent(new Content().addMediaType("application/json",
                new MediaType().schema(bodySchema)));
        op.setRequestBody(body);

        paths.addPathItem("/items", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<InputParameterSpec> inputs = result.getHttpClient().getResources().get(0)
                .getOperations().get(0).getInputParameters();
        assertEquals(1, inputs.size());
        assertEquals("body", inputs.get(0).getIn());
        assertEquals("string", inputs.get(0).getType());
    }

    // ── Utility methods ──

    private OpenAPI minimalOpenApi(String title) {
        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(new Info().title(title));
        openApi.setServers(List.of(server("https://api.example.com")));
        openApi.setPaths(new Paths());
        return openApi;
    }

    private Server server(String url) {
        return new Server().url(url);
    }

    private PathItem pathItem(String method, Operation op) {
        PathItem item = new PathItem();
        switch (method) {
            case "GET" -> item.setGet(op);
            case "POST" -> item.setPost(op);
            case "PUT" -> item.setPut(op);
            case "DELETE" -> item.setDelete(op);
            case "PATCH" -> item.setPatch(op);
        }
        return item;
    }

    private Operation operation(String operationId, String summary, List<String> tags) {
        Operation op = new Operation();
        op.setOperationId(operationId);
        op.setSummary(summary);
        op.setTags(tags);
        return op;
    }

    private Parameter parameter(String name, String in, String type, boolean required) {
        Parameter p = new Parameter();
        p.setName(name);
        p.setIn(in);
        p.setRequired(required);
        Schema<?> schema = switch (type) {
            case "integer" -> new IntegerSchema();
            case "boolean" -> new BooleanSchema();
            default -> new StringSchema();
        };
        p.setSchema(schema);
        return p;
    }

}
