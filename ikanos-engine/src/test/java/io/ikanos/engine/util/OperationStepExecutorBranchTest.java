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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;
import io.ikanos.util.VersionHelper;

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
                ikanos: "%s"
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
                ikanos: "%s"
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
                ikanos: "%s"
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
                ikanos: "%s"
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
                ikanos: "%s"
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

    /**
     * Regression test for #290: namespace-qualified references in step-level WithInjector.
     *
     * When a step has {@code with: {voyageId: "shipyard-tools.voyageId"}}, the qualified reference
     * should be resolved to the caller's argument value "VOY-2026-042", not passed as a literal.
     *
     * This test captures the parameters actually passed to findClientRequestFor. Before the fix,
     * the namespace is not propagated to step execution, so the literal string
     * "shipyard-tools.voyageId" is used instead of the resolved value.
     */
    @Test
    void executeStepsShouldResolveNamespaceQualifiedReferencesInStepWith() throws Exception {
        Capability capability = capabilityFromYaml("""
                ikanos: "%s"
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
                      namespace: "registry"
                      baseUri: "http://localhost:19999"
                      resources:
                        - path: "/voyages/{{voyageId}}"
                          name: "voyage"
                          operations:
                            - method: "GET"
                              name: "get-voyage"
                              inputParameters:
                                - name: "voyageId"
                                  in: "path"
                """.formatted(schemaVersion));

        // Subclass that captures parameters at the point of HTTP request construction
        class CapturingExecutor extends OperationStepExecutor {
            Map<String, Object> capturedParams;

            CapturingExecutor(Capability cap) {
                super(cap);
            }

            @Override
            public HandlingContext findClientRequestFor(String clientNamespace,
                    String clientOpName, Map<String, Object> parameters) {
                capturedParams = new HashMap<>(parameters);
                return super.findClientRequestFor(clientNamespace, clientOpName, parameters);
            }
        }

        CapturingExecutor executor = new CapturingExecutor(capability);
        executor.setExposeNamespace("shipyard-tools");

        OperationStepCallSpec step = new OperationStepCallSpec();
        step.setType("call");
        step.setName("get-voyage");
        step.setCall("registry.get-voyage");
        step.setWith(Map.of("voyageId", "shipyard-tools.voyageId"));

        // The HTTP call will fail (no server), but parameters are captured before the call
        try {
            executor.executeSteps(List.of(step), Map.of("voyageId", "VOY-2026-042"));
        } catch (RuntimeException expected) {
            // HTTP connection failure expected
        }

        assertNotNull(executor.capturedParams,
                "Parameters should have been captured before HTTP call");
        assertEquals("VOY-2026-042", executor.capturedParams.get("voyageId"),
                "Namespace-qualified reference 'shipyard-tools.voyageId' should resolve to "
                        + "the caller's argument value, not be passed as a literal string");
    }

    /**
     * Regression test for #329 — Bug 1: resolveStepOutput must augment array elements
     * with projected fields instead of collapsing the array.
     *
     * When a consumed operation (e.g. list-ships) declares an outputParameter that renames
     * a field (imo_number → imo-number), the projection must iterate over each element of
     * the array and add the renamed field while preserving the original fields. Before the
     * fix, the method applied the mapping to the array root, producing { "imo-number": null }.
     */
    @Test
    void resolveStepOutputShouldAugmentArrayElementsWithProjectedFields() throws Exception {
        OperationStepExecutor executor = executorFromYaml("""
                ikanos: "%s"
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
        JsonNode raw = mapper.readTree("""
                [
                  {"imo_number": "IMO-123", "vessel_name": "Star", "flag_code": "NO"},
                  {"imo_number": "IMO-456", "vessel_name": "Dawn", "flag_code": "SG"}
                ]
                """);

        OperationStepExecutor.HandlingContext ctx = new OperationStepExecutor.HandlingContext();
        HttpClientOperationSpec operation = new HttpClientOperationSpec();

        OutputParameterSpec renamed = new OutputParameterSpec();
        renamed.setName("imo-number");
        renamed.setType("string");
        renamed.setMapping("$.imo_number");

        operation.getOutputParameters().add(renamed);
        ctx.clientOperation = operation;

        JsonNode result = executor.resolveStepOutput(ctx, raw);

        assertTrue(result.isArray(), "result must remain an array");
        assertEquals(2, result.size(), "array must keep both elements");

        assertEquals("IMO-123", result.get(0).path("imo-number").asText(),
                "projected field must be present on first element");
        assertEquals("Star", result.get(0).path("vessel_name").asText(),
                "original fields must be preserved on first element");
        assertEquals("NO", result.get(0).path("flag_code").asText(),
                "original fields must be preserved on first element");

        assertEquals("IMO-456", result.get(1).path("imo-number").asText(),
                "projected field must be present on second element");
        assertEquals("Dawn", result.get(1).path("vessel_name").asText(),
                "original fields must be preserved on second element");
    }

    /**
     * Regression test for #329 — Bug 2: resolveStepMappings must support dot-notation
     * in targetName to create nested objects.
     *
     * When a mapping uses "route.from" as targetName, the result must contain a nested
     * "route" object with a "from" field, rather than a flat "route.from" key.
     */
    @Test
    void resolveStepMappingsShouldCreateNestedObjectsFromDotNotation() throws Exception {
        OperationStepExecutor executor = executorFromYaml("""
                ikanos: "%s"
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
        StepExecutionContext stepContext = new StepExecutionContext();
        stepContext.storeStepOutput("voyage", mapper.readTree("""
                {"departurePort": "Oslo", "arrivalPort": "Singapore", "status": "planned"}
                """));
        stepContext.storeStepOutput("ship", mapper.readTree("""
                {"vessel_name": "Northern Star", "vessel_type": "cargo", "flag_code": "NO"}
                """));

        List<StepOutputMappingSpec> mappings = List.of(
                new StepOutputMappingSpec("status", "$.voyage.status"),
                new StepOutputMappingSpec("route.from", "$.voyage.departurePort"),
                new StepOutputMappingSpec("route.to", "$.voyage.arrivalPort"),
                new StepOutputMappingSpec("ship.name", "$.ship.vessel_name"),
                new StepOutputMappingSpec("ship.type", "$.ship.vessel_type"),
                new StepOutputMappingSpec("ship.flag", "$.ship.flag_code"));

        String result = executor.resolveStepMappings(mappings, stepContext);
        JsonNode json = mapper.readTree(result);

        assertEquals("planned", json.path("status").asText(),
                "flat mapping must still work");
        assertTrue(json.path("route").isObject(),
                "dot-notation must create a nested object");
        assertEquals("Oslo", json.path("route").path("from").asText());
        assertEquals("Singapore", json.path("route").path("to").asText());
        assertTrue(json.path("ship").isObject(),
                "dot-notation must create a nested object for ship");
        assertEquals("Northern Star", json.path("ship").path("name").asText());
        assertEquals("cargo", json.path("ship").path("type").asText());
        assertEquals("NO", json.path("ship").path("flag").asText());
    }

    private static OperationStepExecutor executorFromYaml(String yaml) throws Exception {
        return new OperationStepExecutor(capabilityFromYaml(yaml));
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        IkanosSpec spec = YAML.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }
}

