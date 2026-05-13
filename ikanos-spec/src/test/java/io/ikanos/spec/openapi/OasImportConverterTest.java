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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;


public class OasImportConverterTest {

    private OasImportConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OasImportConverter();
    }

    @Test
    void convertShouldRejectNullOpenApi() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(null));

        assertEquals("OpenAPI document must not be null", error.getMessage());
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

    @Test
    void convertShouldUsePlaceholderBaseUriWhenFirstServerEntryIsNull() {
        OpenAPI openApi = minimalOpenApi("Test");
        List<Server> servers = new ArrayList<>();
        servers.add(null);
        openApi.setServers(servers);

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.example.com", result.getHttpClient().getBaseUri());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No servers defined")));
    }

    // ── Resource grouping ──

    @Test
    void convertShouldGroupOperationsByPath() {
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

        Map<String, HttpClientResourceSpec> resources = result.getHttpClient().getResources();
        assertEquals(3, resources.size());
        assertEquals("/pets", resources.get("pets").getPath());
        assertEquals("pets", resources.get("pets").getName());
        assertEquals("/pets/{{petId}}", resources.get("pets-pet-id").getPath());
        assertEquals("pets-pet-id", resources.get("pets-pet-id").getName());
        assertEquals("/stores", resources.get("stores").getPath());
        assertEquals("stores", resources.get("stores").getName());
    }

    @Test
    void convertShouldDeriveResourceNameFromPath() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();
        paths.addPathItem("/users/{id}", pathItem("GET",
                operation("getUser", "Get user", null)));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertEquals(1, result.getHttpClient().getResources().size());
        HttpClientResourceSpec resource = firstResource(result);
        assertEquals("users-id", resource.getName());
        assertEquals("/users/{{id}}", resource.getPath());
    }

    // Non-regression test for bug S2259: deriveResourceName must not throw NPE when
    // the path is null. Before the fix, calling path.replaceAll(...) on a null path
    // produced a NullPointerException. After the fix, the method falls back to "root".
    @Test
    void deriveResourceNameShouldFallBackToRootWhenPathIsNull() {
        assertEquals("root", converter.deriveResourceName(null));
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

        HttpClientOperationSpec op = firstOperation(firstResource(result));
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

        HttpClientOperationSpec opSpec = firstOperation(firstResource(result));
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

        List<InputParameterSpec> inputs = firstOperation(firstResource(result)).getInputParameters();
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

        List<InputParameterSpec> inputs = firstOperation(firstResource(result)).getInputParameters();
        assertEquals(2, inputs.size());
        assertEquals("body", inputs.get(0).getIn());
        assertEquals("body", inputs.get(1).getIn());
    }

    @Test
    void convertShouldPreserveOriginalPropertyNamesInRequestBody() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("createUser", "Create user", List.of("Users"));
        RequestBody body = new RequestBody();
        ObjectSchema bodySchema = new ObjectSchema();
        bodySchema.addProperty("firstName", new StringSchema());
        bodySchema.addProperty("last_name", new StringSchema());
        body.setContent(new Content().addMediaType("application/json",
                new MediaType().schema(bodySchema)));
        op.setRequestBody(body);

        paths.addPathItem("/users", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<InputParameterSpec> inputs = firstOperation(firstResource(result)).getInputParameters();
        assertEquals(2, inputs.size());
        assertEquals("firstName", inputs.get(0).getName());
        assertEquals("last_name", inputs.get(1).getName());
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

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
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

        OutputParameterSpec addressOut = firstOperation(firstResource(result)).getOutputParameters().get(0);
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

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
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

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
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

        assertTrue(firstOperation(firstResource(result)).getOutputParameters().isEmpty());
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

    @Test
    void convertShouldWarnWhenSecuritySchemeTypeIsMissing() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        components.addSecuritySchemes("broken", new SecurityScheme());
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertNull(result.getHttpClient().getAuthentication());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("missing type")));
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

    // Non-regression test for bug S5850: toKebabCase must trim BOTH leading and trailing
    // hyphens. The original regex `^-|-$` is ambiguous; the explicitly-grouped form
    // `(^-)|(-$)` is unambiguous and trims both ends consistently.
    @Test
    void toKebabCaseShouldTrimLeadingAndTrailingHyphens() {
        // Input that produces leading and trailing hyphens after non-alphanumeric replacement
        assertEquals("foo-bar", OasImportConverter.toKebabCase("-foo-bar-"));
        assertEquals("only", OasImportConverter.toKebabCase("-only-"));
        // Single hyphen on each side via special chars
        assertEquals("api", OasImportConverter.toKebabCase(" api "));
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

        InputParameterSpec input = firstOperation(firstResource(result)).getInputParameters().get(0);
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

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
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

        List<InputParameterSpec> inputs = firstOperation(firstResource(result)).getInputParameters();
        assertEquals(1, inputs.size());
        assertEquals("body", inputs.get(0).getIn());
        assertEquals("string", inputs.get(0).getType());
    }

    // ── B2: Additional branch-coverage tests ──

    @Test
    void convertShouldUsePlaceholderBaseUriWhenServerUrlIsSlash() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setServers(List.of(server("/")));

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.example.com", result.getHttpClient().getBaseUri());
    }

    @Test
    void convertShouldUsePlaceholderBaseUriWhenServerUrlIsEmpty() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setServers(List.of(server("")));

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.example.com", result.getHttpClient().getBaseUri());
    }

    @Test
    void convertShouldUsePlaceholderBaseUriWhenServersListIsEmpty() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setServers(List.of());

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.example.com", result.getHttpClient().getBaseUri());
    }

    @Test
    void convertShouldUsePlaceholderNamespaceWhenInfoIsNull() {
        OpenAPI openApi = new OpenAPI();
        openApi.setServers(List.of(server("https://api.example.com")));
        openApi.setPaths(new Paths());

        OasImportResult result = converter.convert(openApi);

        assertEquals("unknown-api", result.getHttpClient().getNamespace());
    }

    @Test
    void convertShouldWarnOnUnsupportedHttpScheme() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        Components components = new Components();
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("ntlm");
        components.addSecuritySchemes("ntlm", scheme);
        openApi.setComponents(components);

        OasImportResult result = converter.convert(openApi);

        assertNull(result.getHttpClient().getAuthentication());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("Unsupported HTTP security scheme")));
    }

    @Test
    void convertShouldSkipAuthenticationWhenNoComponents() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        openApi.setComponents(null);

        OasImportResult result = converter.convert(openApi);

        assertNull(result.getHttpClient().getAuthentication());
    }

    @Test
    void convertShouldSkipAuthenticationWhenSecuritySchemesAreEmpty() {
        OpenAPI openApi = minimalOpenApi("Test");
        openApi.setPaths(new Paths());
        openApi.setComponents(new Components());

        OasImportResult result = converter.convert(openApi);

        assertNull(result.getHttpClient().getAuthentication());
    }

    @Test
    void convertShouldHandleNonObjectRequestBodyAsSingleParam() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("upload", "Upload data", null);
        RequestBody body = new RequestBody();
        body.setRequired(true);
        body.setContent(new Content().addMediaType("application/json",
                new MediaType().schema(new StringSchema())));
        op.setRequestBody(body);

        paths.addPathItem("/upload", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<InputParameterSpec> inputs = firstOperation(firstResource(result)).getInputParameters();
        assertEquals(1, inputs.size());
        assertEquals("body", inputs.get(0).getName());
        assertEquals("body", inputs.get(0).getIn());
        assertTrue(inputs.get(0).isRequired());
    }

    @Test
    void convertShouldHandleRequestBodyWithNullContent() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("post", "Post data", null);
        RequestBody body = new RequestBody();
        body.setContent(null);
        op.setRequestBody(body);

        paths.addPathItem("/data", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertTrue(firstOperation(firstResource(result)).getInputParameters().isEmpty());
    }

    @Test
    void convertShouldFallbackToNonJsonMediaTypeForRequestBody() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("post", "Post data", null);
        RequestBody body = new RequestBody();
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("data", new StringSchema());
        body.setContent(new Content().addMediaType("application/xml",
                new MediaType().schema(schema)));
        op.setRequestBody(body);

        paths.addPathItem("/data", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<InputParameterSpec> inputs = firstOperation(firstResource(result)).getInputParameters();
        assertEquals(1, inputs.size());
        assertEquals("data", inputs.get(0).getName());
    }

    @Test
    void convertShouldMapNonObjectRequestBodyWithNullRequired() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("upload", "Upload", null);
        RequestBody body = new RequestBody();
        body.setRequired(null);
        body.setContent(new Content().addMediaType("application/json",
                new MediaType().schema(new StringSchema())));
        op.setRequestBody(body);

        paths.addPathItem("/upload", pathItem("POST", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        InputParameterSpec param = firstOperation(firstResource(result)).getInputParameters().get(0);
        assertFalse(param.isRequired());
    }

    @Test
    void convertShouldMapAllOfOutputResponseByMergingProperties() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Schema<?> baseSchema = new ObjectSchema();
        baseSchema.addProperty("id", new IntegerSchema());

        Schema<?> extSchema = new ObjectSchema();
        extSchema.addProperty("name", new StringSchema());

        Schema<?> composedSchema = new Schema<>();
        composedSchema.setAllOf(List.of(baseSchema, extSchema));

        Operation op = operation("get", "Get item", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(composedSchema))));
        op.setResponses(responses);

        paths.addPathItem("/items", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
        assertEquals(2, outputs.size());
    }

    @Test
    void convertShouldMapOneOfOutputResponseUsingFirstVariant() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Schema<?> variant1 = new ObjectSchema();
        variant1.addProperty("text", new StringSchema());

        Schema<?> variant2 = new ObjectSchema();
        variant2.addProperty("number", new IntegerSchema());

        Schema<?> composedSchema = new Schema<>();
        composedSchema.setOneOf(List.of(variant1, variant2));

        Operation op = operation("get", "Get", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(composedSchema))));
        op.setResponses(responses);

        paths.addPathItem("/data", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
        assertEquals(1, outputs.size());
        assertEquals("text", outputs.get(0).getName());
        // Verify that a warning was emitted for oneOf composition.
        // Note: the exact wording of the warning is not a stable contract;
        // we only assert that at least one warning was generated.
        assertFalse(result.getWarnings().isEmpty(),
                "Expected at least one warning for oneOf composition");
    }

    @Test
    void convertShouldMapScalarOutputResponse() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("count", "Count items", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new IntegerSchema()))));
        op.setResponses(responses);

        paths.addPathItem("/count", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
        assertEquals(1, outputs.size());
        assertEquals("number", outputs.get(0).getType());
        assertEquals("$", outputs.get(0).getMapping());
    }

    @Test
    void convertShouldMapArrayOutputWithPrimitiveItems() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        ArraySchema arraySchema = new ArraySchema();
        arraySchema.setItems(new StringSchema());

        Operation op = operation("list", "List tags", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(arraySchema))));
        op.setResponses(responses);

        paths.addPathItem("/tags", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
        assertEquals(1, outputs.size());
        assertEquals("array", outputs.get(0).getType());
        assertNotNull(outputs.get(0).getItems());
        assertEquals("string", outputs.get(0).getItems().getType());
    }

    @Test
    void convertShouldMapPropertyArrayWithPrimitiveItems() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        ObjectSchema responseSchema = new ObjectSchema();
        ArraySchema tagArray = new ArraySchema();
        tagArray.setItems(new StringSchema());
        responseSchema.addProperty("tags", tagArray);

        Operation op = operation("get", "Get", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(responseSchema))));
        op.setResponses(responses);

        paths.addPathItem("/item", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        List<OutputParameterSpec> outputs = firstOperation(firstResource(result)).getOutputParameters();
        OutputParameterSpec tagsParam = outputs.stream()
                .filter(o -> "tags".equals(o.getName())).findFirst().orElseThrow();
        assertEquals("array", tagsParam.getType());
        assertNotNull(tagsParam.getItems());
        assertEquals("string", tagsParam.getItems().getType());
    }

    @Test
    void convertShouldMapPropertyArrayWithObjectItems() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        ObjectSchema responseSchema = new ObjectSchema();
        ArraySchema itemsArray = new ArraySchema();
        ObjectSchema itemSchema = new ObjectSchema();
        itemSchema.addProperty("name", new StringSchema());
        itemsArray.setItems(itemSchema);
        responseSchema.addProperty("results", itemsArray);

        Operation op = operation("search", "Search", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(responseSchema))));
        op.setResponses(responses);

        paths.addPathItem("/search", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        OutputParameterSpec resultsParam = firstOperation(firstResource(result)).getOutputParameters().stream()
                .filter(o -> "results".equals(o.getName())).findFirst().orElseThrow();
        assertEquals("array", resultsParam.getType());
        assertNotNull(resultsParam.getItems());
        assertEquals("object", resultsParam.getItems().getType());
    }

    @Test
    void convertShouldFallbackToNonJsonResponseMediaType() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("get", "Get XML", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType("application/xml",
                        new MediaType().schema(new ObjectSchema()
                                .addProperty("value", new StringSchema())))));
        op.setResponses(responses);

        paths.addPathItem("/data", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertFalse(firstOperation(firstResource(result)).getOutputParameters().isEmpty());
    }

    @Test
    void convertShouldReturnNullOutputsWhenNoSuccessResponse() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("delete", "Delete item", null);
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("404", new ApiResponse().description("Not found"));
        op.setResponses(responses);

        paths.addPathItem("/items/1", pathItem("DELETE", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertTrue(firstOperation(firstResource(result)).getOutputParameters().isEmpty());
    }

    @Test
    void convertShouldGroupHeadAndOptionsOperations() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        PathItem item = new PathItem();
        item.setHead(operation("headPets", "Head pets", null));
        item.setOptions(operation("optionsPets", "Options pets", null));
        paths.addPathItem("/pets", item);

        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        assertEquals(1, result.getHttpClient().getResources().size());
        assertEquals(2, firstResource(result).getOperations().size());
    }

    @Test
    void convertShouldMapInputParameterWithNullRequired() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("get", "Get", null);
        Parameter param = new Parameter();
        param.setName("filter");
        param.setIn("query");
        param.setRequired(null);
        param.setSchema(new StringSchema());
        op.setParameters(List.of(param));

        paths.addPathItem("/items", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        InputParameterSpec inputParam = firstOperation(firstResource(result)).getInputParameters().get(0);
        assertFalse(inputParam.isRequired());
    }

    @Test
    void convertShouldMapPathParameterAsRequiredByDefault() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("get", "Get", null);
        Parameter param = new Parameter();
        param.setName("id");
        param.setIn("path");
        param.setRequired(null);
        param.setSchema(new StringSchema());
        op.setParameters(List.of(param));

        paths.addPathItem("/items/{id}", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        InputParameterSpec inputParam = firstOperation(firstResource(result)).getInputParameters().get(0);
        assertTrue(inputParam.isRequired());
    }

    @Test
    void convertShouldMapInputParameterWithNullSchemaType() {
        OpenAPI openApi = minimalOpenApi("Test");
        Paths paths = new Paths();

        Operation op = operation("get", "Get", null);
        Parameter param = new Parameter();
        param.setName("q");
        param.setIn("query");
        param.setSchema(new Schema<>());
        op.setParameters(List.of(param));

        paths.addPathItem("/search", pathItem("GET", op));
        openApi.setPaths(paths);

        OasImportResult result = converter.convert(openApi);

        InputParameterSpec inputParam = firstOperation(firstResource(result)).getInputParameters().get(0);
        assertNull(inputParam.getType());
    }

    @Test
    void toKebabCaseShouldReturnNullForNullInput() {
        assertNull(OasImportConverter.toKebabCase(null));
    }

    @Test
    void mapSchemaTypeShouldReturnStringForNull() {
        assertEquals("string", OasImportConverter.mapSchemaType(null));
    }

    @Test
    void mapSchemaTypeShouldMapBooleanToBoolean() {
        assertEquals("boolean", OasImportConverter.mapSchemaType("boolean"));
    }

    @Test
    void mapSchemaTypeShouldMapArrayToArray() {
        assertEquals("array", OasImportConverter.mapSchemaType("array"));
    }

    @Test
    void mapSchemaTypeShouldMapObjectToObject() {
        assertEquals("object", OasImportConverter.mapSchemaType("object"));
    }

    @Test
    void mapSchemaTypeShouldMapNumberToNumber() {
        assertEquals("number", OasImportConverter.mapSchemaType("number"));
    }

    @Test
    void mapSchemaTypeShouldMapUnknownToString() {
        assertEquals("string", OasImportConverter.mapSchemaType("binary"));
    }

    @Test
    void resolveSchemaTypeShouldReturnNullWhenOnlyNullInTypes() {
        Schema<?> schema = new Schema<>(SpecVersion.V31);
        schema.setTypes(Set.of("null"));

        assertEquals("null", OasImportConverter.resolveSchemaType(schema));
    }

    @Test
    void deriveResourceNameShouldReturnRootForSlashPath() {
        assertEquals("root", converter.deriveResourceName("/"));
    }

    @Test
    void deriveResourceNameShouldStripLeadingSlash() {
        assertEquals("users", converter.deriveResourceName("/users"));
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

    /** Returns the first resource in declaration order (for single-resource tests). */
    private static HttpClientResourceSpec firstResource(OasImportResult result) {
        return result.getHttpClient().getResources().values().iterator().next();
    }

    /** Returns the first operation in declaration order (for single-operation tests). */
    private static HttpClientOperationSpec firstOperation(HttpClientResourceSpec resource) {
        return resource.getOperations().values().iterator().next();
    }

}

