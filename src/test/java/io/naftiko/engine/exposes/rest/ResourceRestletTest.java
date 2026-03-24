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
package io.naftiko.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerSpec;

public class ResourceRestletTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void handleShouldBuildMockResponseFromConstOutputParameters() throws Exception {
        Capability capability = capabilityFromYaml(minimalCapabilityYaml());
        RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
                .getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().get(0));

        RestServerOperationSpec operation = new RestServerOperationSpec();
        operation.getOutputParameters().add(stringOutput("status", "ready"));

        OutputParameterSpec payload = new OutputParameterSpec();
        payload.setName("payload");
        payload.setType("object");
        payload.getProperties().add(stringOutput("id", "42"));

        OutputParameterSpec tags = new OutputParameterSpec();
        tags.setName("tags");
        tags.setType("array");
        OutputParameterSpec tagItem = new OutputParameterSpec();
        tagItem.setType("string");
        tagItem.setConstant("active");
        tags.setItems(tagItem);
        payload.getProperties().add(tags);
        operation.getOutputParameters().add(payload);

        Request request = new Request(Method.GET, "http://localhost/preview");
        Response response = new Response(request);

        java.lang.reflect.Method sendMockResponse = ResourceRestlet.class
                .getDeclaredMethod("sendMockResponse",
                RestServerOperationSpec.class, Response.class);
        sendMockResponse.setAccessible(true);
        sendMockResponse.invoke(restlet, operation, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertNotNull(response.getEntity());

        JsonNode parsedPayload = JSON.readTree(response.getEntity().getText());
        assertEquals("ready", parsedPayload.path("status").asText());
        assertEquals("42", parsedPayload.path("payload").path("id").asText());
        assertEquals("active", parsedPayload.path("payload").path("tags").get(0).asText());
    }

    @Test
    public void handleShouldMapOutputParametersFromClientResponse() throws Exception {
        Capability capability = capabilityFromYaml(minimalCapabilityYaml());
        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().get(0));

        RestServerOperationSpec operation = new RestServerOperationSpec();
        OutputParameterSpec mappedBody = new OutputParameterSpec();
        mappedBody.setType("object");
        mappedBody.setIn("body");

        OutputParameterSpec id = new OutputParameterSpec();
        id.setName("id");
        id.setType("string");
        id.setMapping("$.user.id");
        mappedBody.getProperties().add(id);

        OutputParameterSpec name = new OutputParameterSpec();
        name.setName("name");
        name.setType("string");
        name.setMapping("$.user.name");
        mappedBody.getProperties().add(name);

        operation.getOutputParameters().add(mappedBody);

        OperationStepExecutor.HandlingContext handlingContext =
                new OperationStepExecutor.HandlingContext();
        Request clientRequest = new Request(Method.GET, "http://localhost/internal");
        handlingContext.clientResponse = new Response(clientRequest);
        handlingContext.clientResponse.setEntity(
                "{\"user\":{\"id\":\"u-1\",\"name\":\"Alice\"}}",
                MediaType.APPLICATION_JSON);

        java.lang.reflect.Method mapOutputParameters = ResourceRestlet.class
                .getDeclaredMethod("mapOutputParameters",
                RestServerOperationSpec.class, OperationStepExecutor.HandlingContext.class);
        mapOutputParameters.setAccessible(true);
        String mapped = (String) mapOutputParameters.invoke(restlet, operation, handlingContext);

        JsonNode payload = JSON.readTree(mapped);
        assertEquals("u-1", payload.path("id").asText());
        assertEquals("Alice", payload.path("name").asText());
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);
        return new Capability(spec);
    }

    private static String minimalCapabilityYaml() {
        return """
                naftiko: "1.0.0-alpha1"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/test"
                          operations:
                            - method: "GET"
                              name: "test"
                  consumes: []
                """;
    }

    private static OutputParameterSpec stringOutput(String name, String constant) {
        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setName(name);
        spec.setType("string");
        spec.setConstant(constant);
        return spec;
    }
}