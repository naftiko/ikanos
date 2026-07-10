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
package io.ikanos.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.http.HttpClientAdapter;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.InputParameterSpec;
import org.restlet.Request;
import org.restlet.data.Method;
import org.restlet.data.MediaType;
import java.io.File;
import java.util.Map;

public class HeaderQueryIntegrationTest {

    private Capability capability;
    private RestServerSpec serverSpec;
    private RestServerResourceSpec resourceSpec;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/http/http-header-query-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Capability file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec =
                mapper.readValue(file, IkanosSpec.class);

        capability = new Capability(spec);

        RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(0);
        serverSpec = (RestServerSpec) adapter.getSpec();
        resourceSpec = serverSpec.getResources().values().iterator().next();
    }

    @Test
    public void testHeadersAndQueryPopulation() throws Exception {
        RestServerOperationSpec serverOp = resourceSpec.getOperations().values().iterator().next();

        String incomingJson = "{\"user\":{\"id\":\"999\"}}";
        Request req = new Request(Method.POST, "/search");
        req.setEntity(incomingJson, MediaType.APPLICATION_JSON);

        OperationStepExecutor executor = new OperationStepExecutor(capability);
        Map<String, Object> params = executor.resolveInputParametersFromRequest(req, serverSpec,
                resourceSpec, serverOp);
        OperationStepExecutor.HandlingContext handlingCtx =
                executor.findClientRequestFor(serverOp.getCall(), params);

        assertNotNull(handlingCtx, "HandlingContext should not be null");

        Request clientRequest = handlingCtx.clientRequest;

        assertNotNull(clientRequest, "Client request should be constructed");

        // Ensure the client adapter has expected inputParameters configured
        HttpClientAdapter clientAdapter = handlingCtx.clientAdapter;
        assertNotNull(clientAdapter, "Client adapter should be present");
        assertFalse(clientAdapter.getHttpClientSpec().getInputParameters().isEmpty(),
                "Client spec should have inputParameters");

        // Verify the client spec input parameter content
        InputParameterSpec firstSpec =
                clientAdapter.getHttpClientSpec().getInputParameters().get(0);
        assertEquals("X-API-Key", firstSpec.getName(),
                "First client input parameter name should be X-API-Key");
        assertEquals("ABC123", firstSpec.getValue(),
                "First client input parameter value should be ABC123");
        // Apply client-level inputParameters to a fresh request
        Request helperReq =
                new Request(Method.POST, "http://example.com/items");
        
        // Apply client-level input parameters to the request using Resolver
        io.ikanos.engine.util.Resolver.resolveInputParametersToRequest(helperReq,
                clientAdapter.getHttpClientSpec().getInputParameters(), params);

        String apiKey = helperReq.getHeaders().getFirstValue("X-API-Key", true);
        assertEquals("ABC123", apiKey,
                "API key header should be set from client inputParameters (helper)");

        // Query param from operation-level inputParameters (q=999)
        String ref = clientRequest.getResourceRef().toString();
        assertTrue(ref.contains("q=999"),
                "Query param q should be present with value 999; got: " + ref);
        // Guard against JSONPath returning JsonNode.toString() instead of textValue(),
        // which would produce q=%22999%22 (URL-encoded double quotes around the value).
        assertFalse(ref.contains("q=%22"),
                "Query param value should not be double-quoted (JSONPath must return textValue, not toString); got: " + ref);
    }
}
