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

import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;
import io.naftiko.spec.consumes.HttpClientOperationSpec;
import io.naftiko.spec.consumes.HttpClientResourceSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;

/**
 * Integration tests: load real OAS fixture files, import, and validate results.
 */
public class OasImportIntegrationTest {

    private OasImportConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OasImportConverter();
    }

    @Test
    void importPetstoreShouldProduceValidConsumesSpec() {
        String path = getFixturePath("openapi/petstore-3.0.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi, "Failed to parse petstore-3.0.yaml");

        OasImportResult result = converter.convert(openApi);

        HttpClientSpec httpClient = result.getHttpClient();
        assertEquals("petstore", httpClient.getNamespace());
        assertEquals("https://petstore.swagger.io/v1", httpClient.getBaseUri());
        assertFalse(httpClient.getResources().isEmpty());

        // Should have a Pets resource
        HttpClientResourceSpec petsResource = httpClient.getResources().stream()
                .filter(r -> "pets".equals(r.getName()))
                .findFirst().orElse(null);
        assertNotNull(petsResource, "Expected a 'pets' resource");

        // Should have listPets, createPet, showPetById, deletePet
        assertEquals(4, petsResource.getOperations().size());

        // listPets should have a 'limit' query parameter
        HttpClientOperationSpec listPets = petsResource.getOperations().stream()
                .filter(o -> "list-pets".equals(o.getName()))
                .findFirst().orElse(null);
        assertNotNull(listPets);
        assertEquals("GET", listPets.getMethod());
        assertTrue(listPets.getInputParameters().stream()
                .anyMatch(p -> "limit".equals(p.getName()) && "query".equals(p.getIn())));

        // listPets should have output parameters (array)
        assertFalse(listPets.getOutputParameters().isEmpty());

        // createPet should have body parameters
        HttpClientOperationSpec createPet = petsResource.getOperations().stream()
                .filter(o -> "create-pet".equals(o.getName()))
                .findFirst().orElse(null);
        assertNotNull(createPet);
        assertTrue(createPet.getInputParameters().stream()
                .anyMatch(p -> "body".equals(p.getIn())));

        assertTrue(result.getWarnings().isEmpty(),
                "Unexpected warnings: " + result.getWarnings());
    }

    @Test
    void importComplexApiShouldHandleAllOfAndOneOf() {
        String path = getFixturePath("openapi/complex-api.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi, "Failed to parse complex-api.yaml");

        OasImportResult result = converter.convert(openApi);

        HttpClientSpec httpClient = result.getHttpClient();
        assertEquals("complex-api", httpClient.getNamespace());
        assertEquals("https://api.complex.io/v2", httpClient.getBaseUri());

        // Bearer auth should be mapped
        assertInstanceOf(BearerAuthenticationSpec.class, httpClient.getAuthentication());

        // Should have users and resources groups
        assertTrue(httpClient.getResources().size() >= 2);

        // oneOf should emit a warning
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("oneOf")));
    }

    @Test
    void importNoServersShouldUsePlaceholderBaseUri() {
        String path = getFixturePath("openapi/no-servers.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi);

        OasImportResult result = converter.convert(openApi);

        assertEquals("https://api.example.com", result.getHttpClient().getBaseUri());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("No servers")));
    }

    @Test
    void importNoOperationIdsShouldSynthesizeNames() {
        String path = getFixturePath("openapi/no-operation-ids.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi);

        OasImportResult result = converter.convert(openApi);

        HttpClientSpec httpClient = result.getHttpClient();
        assertFalse(httpClient.getResources().isEmpty());

        // All operations should have names even without operationId
        for (HttpClientResourceSpec resource : httpClient.getResources()) {
            for (HttpClientOperationSpec op : resource.getOperations()) {
                assertNotNull(op.getName(), "Operation should have a synthesized name");
                assertFalse(op.getName().isEmpty());
            }
        }
    }

    // ── OAS 3.1 integration tests ──

    @Test
    void importOas31PetstoreShouldProduceValidConsumesSpec() {
        String path = getFixturePath("openapi/petstore-3.1.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi, "Failed to parse petstore-3.1.yaml");

        OasImportResult result = converter.convert(openApi);

        HttpClientSpec httpClient = result.getHttpClient();
        assertEquals("petstore-31", httpClient.getNamespace());
        assertEquals("https://petstore.swagger.io/v2", httpClient.getBaseUri());

        // Bearer auth should be mapped
        assertInstanceOf(BearerAuthenticationSpec.class, httpClient.getAuthentication());

        // Should have Pets resource
        HttpClientResourceSpec petsResource = httpClient.getResources().stream()
                .filter(r -> "pets".equals(r.getName()))
                .findFirst().orElse(null);
        assertNotNull(petsResource, "Expected a 'pets' resource");

        // Should have all 4 operations
        assertEquals(4, petsResource.getOperations().size());

        // listPets should have a nullable status query parameter resolved to string type
        HttpClientOperationSpec listPets = petsResource.getOperations().stream()
                .filter(o -> "list-pets".equals(o.getName()))
                .findFirst().orElse(null);
        assertNotNull(listPets);

        InputParameterSpec statusParam = listPets.getInputParameters().stream()
                .filter(p -> "status".equals(p.getName()))
                .findFirst().orElse(null);
        assertNotNull(statusParam, "Expected 'status' query parameter");
        assertEquals("string", statusParam.getType(),
                "OAS 3.1 type: [string, null] should resolve to string");

        // listPets output should include nullable tag property resolved to string
        assertFalse(listPets.getOutputParameters().isEmpty());
    }

    @Test
    void importOas31ShouldResolveNullableOutputPropertyTypes() {
        String path = getFixturePath("openapi/petstore-3.1.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi);

        OasImportResult result = converter.convert(openApi);

        // showPetById should have output with nested owner object
        HttpClientOperationSpec showPet = result.getHttpClient().getResources().stream()
                .flatMap(r -> r.getOperations().stream())
                .filter(o -> "show-pet-by-id".equals(o.getName()))
                .findFirst().orElse(null);
        assertNotNull(showPet);

        // Should have output parameters including owner (object) and tag (nullable string)
        assertFalse(showPet.getOutputParameters().isEmpty());
        OutputParameterSpec ownerParam = showPet.getOutputParameters().stream()
                .filter(p -> "owner".equals(p.getName()))
                .findFirst().orElse(null);
        assertNotNull(ownerParam, "Expected 'owner' output parameter");
        assertEquals("object", ownerParam.getType());
    }

    @Test
    void importOas31ShouldResolveNullableBodyPropertyTypes() {
        String path = getFixturePath("openapi/petstore-3.1.yaml");
        OpenAPI openApi = new OpenAPIV3Parser().read(path);
        assertNotNull(openApi);

        OasImportResult result = converter.convert(openApi);

        // createPet body should have tag property resolved to string
        HttpClientOperationSpec createPet = result.getHttpClient().getResources().stream()
                .flatMap(r -> r.getOperations().stream())
                .filter(o -> "create-pet".equals(o.getName()))
                .findFirst().orElse(null);
        assertNotNull(createPet);

        InputParameterSpec tagParam = createPet.getInputParameters().stream()
                .filter(p -> "tag".equals(p.getName()))
                .findFirst().orElse(null);
        assertNotNull(tagParam, "Expected 'tag' body parameter");
        assertEquals("string", tagParam.getType(),
                "OAS 3.1 type: [string, null] should resolve to string");
    }

    private String getFixturePath(String resourcePath) {
        File file = new File("src/test/resources/" + resourcePath);
        assertTrue(file.exists(), "Fixture not found: " + file.getAbsolutePath());
        return file.getAbsolutePath();
    }

}
