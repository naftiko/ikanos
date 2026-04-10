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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.representation.StringRepresentation;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.consumes.HttpClientOperationSpec;
import io.naftiko.spec.exposes.OperationStepCallSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.util.VersionHelper;

class OperationStepExecutorBranchTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    void findClientRequestForShouldValidateUnresolvedUriAndBodyTemplates() throws Exception {
        OperationStepExecutor executor = executorFromYaml("""
                naftiko: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/x"
                          operations:
                            - method: "GET"
                              name: "x"
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        - path: "/orders/{{orderId}}"
                          name: "orders"
                          operations:
                            - method: "POST"
                              name: "create"
                              body: "{{missingBody}}"
                """.formatted(schemaVersion));

        IllegalArgumentException uriError = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("svc", "create", Map.of()));
        assertTrue(uriError.getMessage().contains("Unresolved template parameters in URI"));

        OperationStepExecutor.HandlingContext bodyContext =
          executor.findClientRequestFor("svc", "create", Map.of("orderId", "o-1"));
        assertEquals("", bodyContext.clientRequest.getEntity().getText());
    }

    @Test
    void findClientRequestForShouldSetStructuredBodyMediaTypes() throws Exception {
        OperationStepExecutor executor = executorFromYaml("""
                naftiko: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/x"
                          operations:
                            - method: "GET"
                              name: "x"
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        - path: "/items"
                          name: "items"
                          operations:
                            - method: "POST"
                              name: "xml-op"
                              body:
                                type: "xml"
                                data:
                                  value: "{{v}}"
                            - method: "POST"
                              name: "form-op"
                              body:
                                type: "formUrlEncoded"
                                data:
                                  q: "{{v}}"
                            - method: "POST"
                              name: "sparql-op"
                              body:
                                type: "sparql"
                                data:
                                  query: "{{v}}"
                """.formatted(schemaVersion));

        Map<String, Object> params = Map.of("v", "ok");

        OperationStepExecutor.HandlingContext xml =
                executor.findClientRequestFor("svc", "xml-op", params);
        assertEquals(MediaType.APPLICATION_XML, xml.clientRequest.getEntity().getMediaType());

        OperationStepExecutor.HandlingContext form =
                executor.findClientRequestFor("svc", "form-op", params);
        assertEquals(MediaType.APPLICATION_WWW_FORM,
                form.clientRequest.getEntity().getMediaType());

        OperationStepExecutor.HandlingContext sparql =
                executor.findClientRequestFor("svc", "sparql-op", params);
        assertEquals("application/sparql-query",
                sparql.clientRequest.getEntity().getMediaType().getName());
    }

    @Test
    void resolveInputParametersFromRequestShouldHandleInvalidJsonBody() throws Exception {
        Capability capability = capabilityFromYaml("""
                naftiko: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      inputParameters:
                        - name: "X-Tenant"
                          in: "header"
                          type: string
                      resources:
                        - path: "/items"
                          inputParameters:
                            - name: "q"
                              in: "query"
                              type: string
                          operations:
                            - method: "GET"
                              name: "search"
                              inputParameters:
                                - name: "itemId"
                                  in: "path"
                                  type: string
                  consumes: []
                """.formatted(schemaVersion));

        RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        RestServerResourceSpec resourceSpec = serverSpec.getResources().get(0);
        RestServerOperationSpec operationSpec = resourceSpec.getOperations().get(0);
        OperationStepExecutor executor = new OperationStepExecutor(capability);

        Request request = new Request(Method.GET, "http://localhost/items?q=ice");
        request.getHeaders().add("X-Tenant", "acme");
        request.getAttributes().put("itemId", "i-1");
        request.setEntity(new StringRepresentation("{", MediaType.APPLICATION_JSON));

        Map<String, Object> resolved = executor.resolveInputParametersFromRequest(request,
                serverSpec, resourceSpec, operationSpec);

        assertEquals("acme", resolved.get("X-Tenant"));
        assertEquals("ice", resolved.get("q"));
        assertEquals("i-1", resolved.get("itemId"));
    }

    @Test
    void privateStepOutputHelpersShouldHandleNamedUnnamedAndNullInputs() throws Exception {
        OperationStepExecutor executor = executorFromYaml("""
                naftiko: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/x"
                          operations:
                            - method: "GET"
                              name: "x"
                  consumes: []
                """.formatted(schemaVersion));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode raw = mapper.readTree("{\"id\":\"u-1\",\"name\":\"Alice\"}");

        Map<String, Object> runtime = new HashMap<>();
        executor.addStepOutputToParameters(runtime, "step1", raw);
        assertTrue(runtime.containsKey("step1"));

        executor.addStepOutputToParameters(null, "step", raw);
        executor.addStepOutputToParameters(runtime, null, raw);
        executor.addStepOutputToParameters(runtime, "step", null);

        JsonNode nullRaw = executor.resolveStepOutput(null, null);
        assertTrue(nullRaw.isNull());

        JsonNode passthrough = executor.resolveStepOutput(null, raw);
        assertEquals("u-1", passthrough.path("id").asText());

        OperationStepExecutor.HandlingContext ctx = new OperationStepExecutor.HandlingContext();
        HttpClientOperationSpec operation = new HttpClientOperationSpec();

        OutputParameterSpec named = new OutputParameterSpec();
        named.setName("userId");
        named.setType("string");
        named.setMapping("$.id");

        OutputParameterSpec unnamed = new OutputParameterSpec();
        unnamed.setType("string");
        unnamed.setMapping("$.name");

        operation.getOutputParameters().addAll(List.of(named, unnamed));
        ctx.clientOperation = operation;

        JsonNode projected = executor.resolveStepOutput(ctx, raw);
        assertEquals("u-1", projected.path("userId").asText());
        assertNull(projected.get("name"));
    }

    @Test
    void executeStepsShouldFailForCallStepWithoutValidCallReference() throws Exception {
        OperationStepExecutor executor = executorFromYaml("""
                naftiko: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/x"
                          operations:
                            - method: "GET"
                              name: "x"
                  consumes: []
                """.formatted(schemaVersion));

        OperationStepCallSpec unsupported = new OperationStepCallSpec();
        unsupported.setType("custom");
        unsupported.setName("noop");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> executor.executeSteps(List.of(unsupported), Map.of("a", "b")));
        assertEquals("Invalid call format: null", error.getMessage());
    }

    private static OperationStepExecutor executorFromYaml(String yaml) throws Exception {
        return new OperationStepExecutor(capabilityFromYaml(yaml));
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        NaftikoSpec spec = YAML.readValue(yaml, NaftikoSpec.class);
        return new Capability(spec);
    }
}