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
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.models.OpenAPI;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Round-trip integration test: Export → Import → compare operations, parameters, auth.
 */
public class OasRoundTripIntegrationTest {

    @Test
    void roundTripShouldPreserveOperationNames() {
        // Build an Ikanos spec
        IkanosSpec spec = new IkanosSpec();
        spec.setIkanos("1.0.0-alpha1");
        spec.setInfo(new InfoSpec("Round Trip API", "Test", null, null));
        CapabilitySpec capability = new CapabilitySpec();

        RestServerSpec rest = new RestServerSpec("localhost", 8080, null);
        BearerAuthenticationSpec auth = new BearerAuthenticationSpec();
        auth.setToken("{{TOKEN}}");
        rest.setAuthentication(auth);

        RestServerResourceSpec resource = new RestServerResourceSpec();
        resource.setPath("/items");
        resource.setName("items");

        RestServerOperationSpec getOp = new RestServerOperationSpec();
        getOp.setMethod("GET");
        getOp.setName("list-items");
        getOp.setDescription("List all items");

        InputParameterSpec queryParam = new InputParameterSpec();
        queryParam.setName("page");
        queryParam.setIn("query");
        queryParam.setType("number");
        queryParam.setRequired(false);
        getOp.getInputParameters().add(queryParam);

        OutputParameterSpec outParam = new OutputParameterSpec();
        outParam.setName("id");
        outParam.setType("number");
        getOp.getOutputParameters().add(outParam);

        OutputParameterSpec nameOut = new OutputParameterSpec();
        nameOut.setName("name");
        nameOut.setType("string");
        getOp.getOutputParameters().add(nameOut);

        resource.setOperations(List.of(getOp));
        rest.getResources().add(resource);
        capability.getExposes().add(rest);
        spec.setCapability(capability);

        // Export
        OasExportBuilder exporter = new OasExportBuilder();
        OasExportResult exportResult = exporter.build(spec, null);
        OpenAPI openApi = exportResult.getOpenApi();

        assertNotNull(openApi.getPaths().get("/items"));
        assertNotNull(openApi.getPaths().get("/items").getGet());
        assertEquals("list-items", openApi.getPaths().get("/items").getGet().getOperationId());

        // Import back
        OasImportConverter importer = new OasImportConverter();
        OasImportResult importResult = importer.convert(openApi);
        HttpClientSpec httpClient = importResult.getHttpClient();

        assertEquals("round-trip-api", httpClient.getNamespace());
        assertFalse(httpClient.getResources().isEmpty());

        // Find the items resource
        boolean foundListItems = false;
        for (var res : httpClient.getResources()) {
            for (HttpClientOperationSpec op : res.getOperations()) {
                if ("list-items".equals(op.getName())) {
                    foundListItems = true;
                    assertEquals("GET", op.getMethod());

                    // Query parameter should survive round-trip
                    assertTrue(op.getInputParameters().stream()
                            .anyMatch(p -> "page".equals(p.getName())
                                    && "query".equals(p.getIn())));

                    // Output parameters should survive
                    assertFalse(op.getOutputParameters().isEmpty());
                }
            }
        }
        assertTrue(foundListItems, "list-items operation should survive round-trip");

        // Authentication should survive (bearer)
        assertNotNull(httpClient.getAuthentication(),
                "Bearer auth should survive round-trip");
    }

    @Test
    void roundTripShouldPreserveMultipleResources() {
        IkanosSpec spec = new IkanosSpec();
        spec.setIkanos("1.0.0-alpha1");
        spec.setInfo(new InfoSpec("Multi Resource", null, null, null));
        CapabilitySpec capability = new CapabilitySpec();

        RestServerSpec rest = new RestServerSpec("localhost", 8080, null);

        RestServerResourceSpec pets = new RestServerResourceSpec();
        pets.setPath("/pets");
        pets.setName("pets");
        RestServerOperationSpec listPets = new RestServerOperationSpec();
        listPets.setMethod("GET");
        listPets.setName("list-pets");
        pets.setOperations(List.of(listPets));

        RestServerResourceSpec stores = new RestServerResourceSpec();
        stores.setPath("/stores");
        stores.setName("stores");
        RestServerOperationSpec listStores = new RestServerOperationSpec();
        listStores.setMethod("GET");
        listStores.setName("list-stores");
        stores.setOperations(List.of(listStores));

        rest.getResources().add(pets);
        rest.getResources().add(stores);
        capability.getExposes().add(rest);
        spec.setCapability(capability);

        // Export then Import
        OasExportBuilder exporter = new OasExportBuilder();
        OpenAPI openApi = exporter.build(spec, null).getOpenApi();

        OasImportConverter importer = new OasImportConverter();
        HttpClientSpec httpClient = importer.convert(openApi).getHttpClient();

        // Both resources should be present after round-trip
        assertTrue(httpClient.getResources().size() >= 2,
                "Both resources should survive round-trip");
    }

}
