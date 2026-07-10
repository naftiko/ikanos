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
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import org.restlet.Request;
import org.restlet.data.Method;
import org.restlet.data.MediaType;
import java.io.File;
import java.util.Map;

public class HttpBodyIntegrationTest {

    private Capability capability;
    private RestServerSpec serverSpec;
    private RestServerResourceSpec resourceSpec;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/http/http-body-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "HTTP body capability file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec =
                mapper.readValue(file, IkanosSpec.class);

        capability = new Capability(spec);

        // locate RestResourceSpec
        RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(0);
        serverSpec = (RestServerSpec) adapter.getSpec();
        resourceSpec = serverSpec.getResources().values().iterator().next();
    }

    @Test
    public void testClientRequestBodyTemplating() throws Exception {
        RestServerOperationSpec serverOp = resourceSpec.getOperations().values().iterator().next();

        // Build incoming request body
        String incomingJson = "{\"user\":{\"id\":\"123\",\"name\":\"Alice\"}}";
        Request req = new Request(Method.POST, "/users");
        req.setEntity(incomingJson, MediaType.APPLICATION_JSON);

        OperationStepExecutor executor = new OperationStepExecutor(capability);
        Map<String, Object> params = executor.resolveInputParametersFromRequest(req, serverSpec,
                resourceSpec, serverOp);

        assertNotNull(params, "Params map should be built");
        assertEquals("123", params.get("userId").toString(),
                "userId should be extracted from body");
        assertEquals("Alice", params.get("userName").toString(),
                "userName should be extracted from body");

        OperationStepExecutor.HandlingContext handlingCtx =
                executor.findClientRequestFor(serverOp.getCall(), params);

        assertNotNull(handlingCtx, "HandlingContext should not be null");
        Request clientRequest = handlingCtx.clientRequest;

        assertNotNull(clientRequest, "Client Request should be constructed");
        assertNotNull(clientRequest.getEntity(), "Client request entity should be set");

        String body = clientRequest.getEntity().getText();
        assertTrue(body.contains("\"id\":\"123\""), "Templated body should contain id 123");
        assertTrue(body.contains("\"name\":\"Alice\""), "Templated body should contain name Alice");
    }
}
