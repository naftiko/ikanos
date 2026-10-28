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

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

public class OasImportWriterTest {

    private static Schema schema;

    private final OasImportWriter writer = new OasImportWriter();

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = OasImportWriterTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(IkanosMappers.json().readTree(in));
        }
    }

    private static List<String> schemaErrors(JsonNode document) {
        return schema.validate(document).stream()
                .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                .toList();
    }

    private static HttpClientSpec adapterWithOperation(HttpClientOperationSpec operation) {
        HttpClientSpec adapter = new HttpClientSpec("demo", "https://api.demo.io", null);
        HttpClientResourceSpec resource = new HttpClientResourceSpec();
        resource.setName("items");
        resource.setPath("/items");
        operation.setName("list-items");
        operation.setMethod("GET");
        resource.setOperations(Map.of("list-items", operation));
        adapter.getResources().put("items", resource);
        return adapter;
    }

    private static JsonNode operationNode(JsonNode document) {
        return document.path("consumes").path(0).path("resources").path("items")
                .path("operations").path("list-items");
    }

    @ParameterizedTest
    @ValueSource(strings = {"petstore-2.0.yaml", "petstore-3.0.yaml", "petstore-3.1.yaml",
            "complex-api.yaml", "no-operation-ids.yaml", "no-servers.yaml",
            "petstore-swagger2-full.json", "edge-cases.yaml"})
    void toDocumentShouldBeSchemaValidForEveryFixture(String fixture) {
        File file = new File("src/test/resources/openapi/" + fixture);
        assertTrue(file.exists(), "Fixture not found: " + file.getAbsolutePath());
        OpenAPI openApi = new OpenAPIParser().readLocation(file.getAbsolutePath(), null, null)
                .getOpenAPI();
        HttpClientSpec adapter = new OasImportConverter().convert(openApi).getHttpClient();

        JsonNode document = writer.toDocument(adapter);

        List<String> errors = schemaErrors(document);
        assertTrue(errors.isEmpty(), "#871: " + fixture + " must import to a schema-valid "
                + "document, but got: " + errors);
    }

    @Test
    void toDocumentShouldWriteSchemaVersionAndAdapterIdentity() {
        JsonNode document = writer.toDocument(
                adapterWithOperation(new HttpClientOperationSpec()));

        assertEquals(VersionHelper.getSchemaVersion(), document.path("ikanos").asString());
        JsonNode adapter = document.path("consumes").path(0);
        assertEquals("http", adapter.path("type").asString());
        assertEquals("demo", adapter.path("namespace").asString());
        assertEquals("https://api.demo.io", adapter.path("baseUri").asString());
        assertFalse(adapter.has("authentication"));
    }

    @Test
    void toDocumentShouldKeyInputParametersByNameAndDropDescriptiveFields() {
        HttpClientOperationSpec operation = new HttpClientOperationSpec();
        InputParameterSpec limit = new InputParameterSpec();
        limit.setName("limit");
        limit.setIn("query");
        limit.setType("number");
        limit.setDescription("Maximum number of items.");
        limit.setRequired(false);
        InputParameterSpec version = new InputParameterSpec();
        version.setName("Api-Version");
        version.setIn("header");
        version.setValue("2024-01-01");
        operation.setInputParameters(Map.of("limit", limit, "Api-Version", version));

        JsonNode inputs = operationNode(writer.toDocument(adapterWithOperation(operation)))
                .path("inputParameters");

        assertEquals("query", inputs.path("limit").path("in").asString());
        assertEquals(1, inputs.path("limit").size(),
                "#871: ConsumedInputParameter has no type, description or required");
        assertEquals("header", inputs.path("Api-Version").path("in").asString());
        assertEquals("2024-01-01", inputs.path("Api-Version").path("value").asString());
    }

    @Test
    void toDocumentShouldKeepQuerySerializationFields() {
        HttpClientOperationSpec operation = new HttpClientOperationSpec();
        InputParameterSpec status = new InputParameterSpec();
        status.setName("status");
        status.setIn("query");
        status.setWireName("status[]");
        InputParameterSpec ids = new InputParameterSpec();
        ids.setName("ids");
        ids.setIn("query");
        ids.setStyle("form");
        ids.setExplode(false);
        InputParameterSpec tags = new InputParameterSpec();
        tags.setName("tags");
        tags.setIn("query");
        tags.setStyle("pipeDelimited");
        operation.setInputParameters(Map.of("status", status, "ids", ids, "tags", tags));

        JsonNode document = writer.toDocument(adapterWithOperation(operation));
        JsonNode inputs = operationNode(document).path("inputParameters");

        assertEquals("status[]", inputs.path("status").path("wireName").asString(),
                "#872: without wireName the request sends status=a instead of status[]=a");
        assertEquals("form", inputs.path("ids").path("style").asString());
        assertTrue(inputs.path("ids").path("explode").isBoolean());
        assertFalse(inputs.path("ids").path("explode").asBoolean(),
                "#872: without explode: false the request sends ids=a&ids=b instead of ids=a,b");
        assertEquals("pipeDelimited", inputs.path("tags").path("style").asString());
        assertFalse(inputs.path("tags").has("explode"));
        assertTrue(schemaErrors(document).isEmpty(), "got: " + schemaErrors(document));
    }

    @Test
    void toDocumentShouldKeepQuerySerializationFieldsFromImportedSpec() {
        File file = new File("src/test/resources/openapi/edge-cases.yaml");
        OpenAPI openApi = new OpenAPIParser().readLocation(file.getAbsolutePath(), null, null)
                .getOpenAPI();
        HttpClientSpec adapter = new OasImportConverter().convert(openApi).getHttpClient();

        JsonNode inputs = writer.toDocument(adapter).path("consumes").path(0)
                .path("resources").path("items").path("operations").path("list-items")
                .path("inputParameters");

        assertEquals("status[]", inputs.path("status").path("wireName").asString());
        assertFalse(inputs.path("ids").path("explode").asBoolean(true));
        assertEquals("pipeDelimited", inputs.path("tags").path("style").asString());
    }

    @Test
    void toDocumentShouldWriteNamedOutputsAsTypedJsonPathsAndSkipUnnamedOnes() {
        HttpClientOperationSpec operation = new HttpClientOperationSpec();
        OutputParameterSpec unnamedArray = new OutputParameterSpec();
        unnamedArray.setType("array");
        unnamedArray.setMapping("$[*]");
        OutputParameterSpec owner = new OutputParameterSpec();
        owner.setName("owner");
        owner.setType("object");
        owner.setMapping("$.owner");
        OutputParameterSpec ownerName = new OutputParameterSpec();
        ownerName.setName("name");
        ownerName.setType("string");
        ownerName.setMapping("$.owner.name");
        owner.getProperties().add(ownerName);
        operation.getOutputParameters().add(unnamedArray);
        operation.getOutputParameters().add(owner);

        JsonNode outputs = operationNode(writer.toDocument(adapterWithOperation(operation)))
                .path("outputParameters");

        assertEquals(1, outputs.size(),
                "#871: an unnamed output has no map key and is the raw response anyway");
        assertEquals("object", outputs.path("owner").path("type").asString());
        assertEquals("$.owner", outputs.path("owner").path("value").asString());
        assertEquals(2, outputs.path("owner").size(),
                "#871: ConsumedOutputParameter only allows 'type' and 'value'");
    }

    @Test
    void toDocumentShouldOmitOutputParametersWhenOnlyUnnamedOutputsExist() {
        HttpClientOperationSpec operation = new HttpClientOperationSpec();
        OutputParameterSpec scalar = new OutputParameterSpec();
        scalar.setType("string");
        scalar.setMapping("$");
        operation.getOutputParameters().add(scalar);

        JsonNode op = operationNode(writer.toDocument(adapterWithOperation(operation)));

        assertFalse(op.has("outputParameters"));
        assertEquals("GET", op.path("method").asString());
    }

    @Test
    void toDocumentShouldNotRepeatResourceAndOperationNames() {
        HttpClientOperationSpec operation = new HttpClientOperationSpec();
        operation.setDisplay("List items");
        operation.setDescription("Returns every item.");

        JsonNode resource = writer.toDocument(adapterWithOperation(operation))
                .path("consumes").path(0).path("resources").path("items");

        assertFalse(resource.has("name"), "#871: the resource map key is the name");
        assertEquals("/items", resource.path("path").asString());
        JsonNode op = resource.path("operations").path("list-items");
        assertFalse(op.has("name"), "#871: the operation map key is the name");
        assertEquals("List items", op.path("display").asString());
        assertEquals("Returns every item.", op.path("description").asString());
    }

    @Test
    void toDocumentShouldOmitResourcesWhenTheApiHasNoPaths() {
        HttpClientSpec adapter = new HttpClientSpec("empty", "https://api.empty.io", null);

        JsonNode document = writer.toDocument(adapter);

        assertFalse(document.path("consumes").path(0).has("resources"));
        assertTrue(schemaErrors(document).isEmpty());
    }

    @Test
    void toDocumentShouldWriteApiKeyAuthenticationFields() {
        HttpClientSpec adapter = adapterWithOperation(new HttpClientOperationSpec());
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setKey("X-API-Key");
        apiKey.setValue("{{API_KEY}}");
        apiKey.setPlacement("header");
        adapter.setAuthentication(apiKey);

        JsonNode document = writer.toDocument(adapter);

        JsonNode auth = document.path("consumes").path(0).path("authentication");
        assertEquals("apikey", auth.path("type").asString());
        assertEquals("X-API-Key", auth.path("key").asString());
        assertEquals("{{API_KEY}}", auth.path("value").asString());
        assertEquals("header", auth.path("placement").asString());
        assertTrue(schemaErrors(document).isEmpty(), "Unexpected: " + schemaErrors(document));
    }

    @Test
    void toDocumentShouldWriteBasicAndDigestCredentialsAsStrings() {
        HttpClientSpec adapter = adapterWithOperation(new HttpClientOperationSpec());
        BasicAuthenticationSpec basic = new BasicAuthenticationSpec();
        basic.setUsername("{{USERNAME}}");
        basic.setPassword("{{PASSWORD}}".toCharArray());
        adapter.setAuthentication(basic);

        JsonNode basicAuth = writer.toDocument(adapter).path("consumes").path(0)
                .path("authentication");

        assertEquals("basic", basicAuth.path("type").asString());
        assertEquals("{{USERNAME}}", basicAuth.path("username").asString());
        assertEquals("{{PASSWORD}}", basicAuth.path("password").asString());

        DigestAuthenticationSpec digest = new DigestAuthenticationSpec();
        digest.setUsername("{{USERNAME}}");
        digest.setPassword("{{PASSWORD}}".toCharArray());
        adapter.setAuthentication(digest);

        JsonNode document = writer.toDocument(adapter);

        JsonNode digestAuth = document.path("consumes").path(0).path("authentication");
        assertEquals("digest", digestAuth.path("type").asString());
        assertEquals("{{PASSWORD}}", digestAuth.path("password").asString());
        assertTrue(schemaErrors(document).isEmpty(), "Unexpected: " + schemaErrors(document));
    }

}
