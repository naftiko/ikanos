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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.http.HttpConsumedResult;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.util.OperationStepLookupSpec;
import io.ikanos.engine.util.OperationStepExecutor.StepFailedException;

public class OperationStepExecutorIntegrationTest {
    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void executeStepsShouldStoreLookupOutputsAndResolveCallStepTemplates() throws Exception {
      int port = findFreePort();
      Component server = createJsonServer(port,
        Map.of(
          "/v1/users", """
                [
                  {"id":"u-1","email":"alice@example.com"},
                  {"id":"u-2","email":"bob@example.com"}
                ]
        """,
          "/v1/profiles/u-1", """
                {"profile":{"region":"eu"}}
        """,
          "/v1/echo/eu", """
                {"ok":true}
        """));
        server.start();

        try {
            Capability capability = capabilityFromYaml("""
                    ikanos: "%s"
                    capability:
                      exposes:
                        - type: "rest"
                          address: "localhost"
                          port: 0
                          namespace: "steps"
                          resources:
                            - path: "/workflow"
                              operations:
                                - method: "GET"
                                  name: "workflow"
                                  steps:
                                    - type: call
                                      name: fetch-users
                                      call: testns.list-users
                                    - type: lookup
                                      name: find-user
                                      index: fetch-users
                                      match: email
                                      lookupValue: "{{targetEmail}}"
                                      outputParameters:
                                        - id
                                        - email
                                    - type: call
                                      name: fetch-profile
                                      call: testns.fetch-profile
                                      with:
                                        userId: "{{requestingUserId}}"
                                    - type: call
                                      name: echo-region
                                      call: testns.echo-region
                                      with:
                                        region: "{{fetch-profile.region}}"
                      consumes:
                        - type: "http"
                          namespace: "testns"
                          baseUri: "http://localhost:%d/v1"
                          resources:
                            - path: "/users"
                              name: "users"
                              operations:
                                - method: "GET"
                                  name: "list-users"
                            - path: "/profiles/{{userId}}"
                              name: "profiles"
                              operations:
                                - method: "GET"
                                  name: "fetch-profile"
                                  outputParameters:
                                    - name: region
                                      type: string
                                      mapping: $.profile.region
                            - path: "/echo/{{region}}"
                              name: "echo"
                              operations:
                                - method: "GET"
                                  name: "echo-region"
                    """.formatted(schemaVersion, port));

            RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
                    .getSpec();
            RestServerResourceSpec resourceSpec = serverSpec.getResources().values().iterator().next();
            RestServerOperationSpec operationSpec = resourceSpec.getOperations().values().iterator().next();
            OperationStepExecutor executor = new OperationStepExecutor(capability);

            OperationStepExecutor.StepExecutionResult result = executor.executeSteps(
                    operationSpec.getSteps(),
                    Map.of("targetEmail", "bob@example.com", "requestingUserId", "u-1"));

            assertEquals("u-2", result.stepContext.getStepOutput("find-user").path("id").asText());
            assertEquals("eu",
                    result.stepContext.getStepOutput("fetch-profile").path("region").asText());
            assertNotNull(result.lastResult);
            assertTrue(((HttpConsumedResult) result.lastResult).getRequest().getResourceRef()
                    .toString().endsWith("/echo/eu"));
        } finally {
          server.stop();
        }
    }

    @Test
    public void applyOutputMappingsShouldReturnFirstMappedValue() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "dummy"
                      resources:
                        - path: "/dummy"
                          operations:
                            - method: "GET"
                              name: "dummy"
                  consumes: []
                """.formatted(schemaVersion)));

        OutputParameterSpec missing = new OutputParameterSpec();
        missing.setName("missing");
        missing.setType("string");
        missing.setMapping("$.missing");

        OutputParameterSpec match = new OutputParameterSpec();
        match.setName("id");
        match.setType("string");
        match.setMapping("$.user.id");

        String mapped = executor.applyOutputMappings("{" +
                "\"user\":{\"id\":\"u-1\"}}", List.of(missing, match));

        assertEquals("\"u-1\"", mapped);
    }

    @Test
    public void applyOutputMappingsShouldReturnNullForEmptyInputs() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "dummy"
                      resources:
                        - path: "/dummy"
                          operations:
                            - method: "GET"
                              name: "dummy"
                  consumes: []
                """.formatted(schemaVersion)));

        assertNull(executor.applyOutputMappings(null, List.of()));
        assertNull(executor.applyOutputMappings("", List.of()));
        assertNull(executor.applyOutputMappings("{}", null));
        assertNull(executor.applyOutputMappings("{}", List.of()));
    }

    @Test
    public void executeShouldThrowForInvalidCallAndMissingModes() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "dummy"
                      resources:
                        - path: "/dummy"
                          operations:
                            - method: "GET"
                              name: "dummy"
                  consumes: []
                """.formatted(schemaVersion)));

        IllegalArgumentException invalidCall = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(new ServerCallSpec("bad.call"), null, Map.of(),
                        "Operation 'dummy'"));
        assertEquals("Invalid call for Operation 'dummy': bad.call", invalidCall.getMessage());

        IllegalArgumentException missingMode = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(null, null, Map.of(), "Operation 'dummy'"));
        assertEquals("Operation 'dummy' has neither call nor steps defined",
                missingMode.getMessage());
    }

    /**
     * Regression test for #339: applyOutputMappings assumes JSON and throws
     * JsonParseException when the response is XML. After the fix, the overloaded
     * method accepting outputRawFormat should convert XML to JSON first.
     */
    @Test
    public void applyOutputMappingsShouldThrowJsonParseExceptionForXmlInput() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "dummy"
                      resources:
                        - path: "/dummy"
                          operations:
                            - method: "GET"
                              name: "dummy"
                  consumes: []
                """.formatted(schemaVersion)));

        String xmlResponse = "<vessels><vessel>"
                + "<vesselCode>V001</vesselCode>"
                + "<vesselName>Sea Eagle</vesselName>"
                + "</vessel></vessels>";

        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("string");
        spec.setMapping("$.vessel.vesselCode");

        // Bug #339: this throws JsonParseException instead of converting XML first
        assertThrows(com.fasterxml.jackson.core.JsonParseException.class,
                () -> executor.applyOutputMappings(xmlResponse, List.of(spec)));
    }

    /**
     * Regression test for #339: the 4-arg overload accepting outputRawFormat should
     * convert the XML response to a JSON tree before applying output mappings.
     */
    @Test
    public void applyOutputMappingsShouldMapXmlWhenOutputRawFormatIsXml() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "dummy"
                      resources:
                        - path: "/dummy"
                          operations:
                            - method: "GET"
                              name: "dummy"
                  consumes: []
                """.formatted(schemaVersion)));

        String xmlResponse = "<vessels><vessel>"
                + "<vesselCode>V001</vesselCode>"
                + "<vesselName>Sea Eagle</vesselName>"
                + "</vessel></vessels>";

        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("string");
        spec.setMapping("$.vessel.vesselCode");

        String mapped = executor.applyOutputMappings(xmlResponse, List.of(spec), "xml", null);
        assertEquals("\"V001\"", mapped);
    }

    @Test
    public void executeStepsShouldThrowWhenLookupReferencesMissingIndex() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "dummy"
                      resources:
                        - path: "/dummy"
                          operations:
                            - method: "GET"
                              name: "dummy"
                  consumes: []
                """.formatted(schemaVersion)));

        OperationStepLookupSpec lookup = new OperationStepLookupSpec();
        lookup.setName("find");
        lookup.setIndex("does-not-exist");
        lookup.setMatch("id");
        lookup.setLookupValue("u-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.executeSteps(Map.of(lookup.getName(), lookup), Map.of()));

        assertEquals("Lookup step references non-existent step: does-not-exist",
                error.getMessage());
    }

    /**
     * Regression test for #739: a call step that receives a non-2xx response must stop the
     * sequence. Before the fix, the failed step's error body was stored as its output and the
     * next step ran anyway, with unresolved values from the failed step.
     */
    @Test
    public void executeStepsShouldStopWhenCallStepReturnsServerError() throws Exception {
        int port = findFreePort();
        AtomicInteger secondStepHits = new AtomicInteger();
        Component server = createServer(port, Map.of(
                "/v1/parts/p-1", new StubResponse(Status.SERVER_ERROR_INTERNAL,
                        "{\"message\":\"part p-1 is not payable\"}", null),
                "/v1/charges", new StubResponse(Status.SUCCESS_OK, "{\"ok\":true}", secondStepHits)));
        server.start();
        try {
            Capability capability = twoStepCapability(port);
            OperationStepExecutor executor = new OperationStepExecutor(capability);

            StepFailedException error = assertThrows(StepFailedException.class,
                    () -> executor.executeSteps(twoStepSpec(capability), Map.of("partId", "p-1")),
                    "#739: a 500 on step 'read-part' must fail the sequence");

            assertEquals(0, secondStepHits.get(),
                    "#739: step 'charge' must not run after step 'read-part' failed");
            assertEquals("read-part", error.getStepName());
            assertEquals(500, error.getStatusCode());
        } finally {
            server.stop();
        }
    }

    /**
     * Regression test for #739: a client error (4xx) on a call step also stops the sequence.
     */
    @Test
    public void executeStepsShouldStopWhenCallStepReturnsClientError() throws Exception {
        int port = findFreePort();
        AtomicInteger secondStepHits = new AtomicInteger();
        Component server = createServer(port, Map.of(
                "/v1/parts/p-1", new StubResponse(Status.CLIENT_ERROR_NOT_FOUND,
                        "{\"message\":\"no such part\"}", null),
                "/v1/charges", new StubResponse(Status.SUCCESS_OK, "{\"ok\":true}", secondStepHits)));
        server.start();
        try {
            Capability capability = twoStepCapability(port);
            OperationStepExecutor executor = new OperationStepExecutor(capability);

            StepFailedException error = assertThrows(StepFailedException.class,
                    () -> executor.executeSteps(twoStepSpec(capability), Map.of("partId", "p-1")),
                    "#739: a 404 on step 'read-part' must fail the sequence");

            assertEquals(0, secondStepHits.get(),
                    "#739: step 'charge' must not run after step 'read-part' failed");
            assertEquals(404, error.getStatusCode());
        } finally {
            server.stop();
        }
    }

    /**
     * Regression test for #739 (the reported scenario): the last step fails while the
     * mappings would only read an earlier, successful step. The sequence must still fail,
     * instead of the caller mapping the earlier output into a success.
     */
    @Test
    public void executeStepsShouldFailWhenLaterStepFailsEvenIfEarlierStepSucceeded() throws Exception {
        int port = findFreePort();
        Component server = createServer(port, Map.of(
                "/v1/parts/p-1", new StubResponse(Status.SUCCESS_OK,
                        "{\"amount\":500000}", null),
                "/v1/charges", new StubResponse(Status.SERVER_ERROR_SERVICE_UNAVAILABLE,
                        "{\"message\":\"down\"}", null)));
        server.start();
        try {
            Capability capability = twoStepCapability(port);
            OperationStepExecutor executor = new OperationStepExecutor(capability);

            StepFailedException error = assertThrows(StepFailedException.class,
                    () -> executor.executeSteps(twoStepSpec(capability), Map.of("partId", "p-1")),
                    "#739: a 503 on the last step must fail the sequence");

            assertEquals("charge", error.getStepName());
            assertEquals(503, error.getStatusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    public void executeStepsShouldPassStepOutputToNextStepWhenAllStepsSucceed() throws Exception {
        int port = findFreePort();
        AtomicInteger secondStepHits = new AtomicInteger();
        Component server = createServer(port, Map.of(
                "/v1/parts/p-1", new StubResponse(Status.SUCCESS_OK, "{\"amount\":500000}", null),
                "/v1/charges", new StubResponse(Status.SUCCESS_CREATED, "{\"ok\":true}", secondStepHits)));
        server.start();
        try {
            Capability capability = twoStepCapability(port);
            OperationStepExecutor executor = new OperationStepExecutor(capability);

            OperationStepExecutor.StepExecutionResult result =
                    executor.executeSteps(twoStepSpec(capability), Map.of("partId", "p-1"));

            assertEquals(1, secondStepHits.get());
            assertTrue(result.stepContext.getStepOutput("charge").path("ok").asBoolean());
        } finally {
            server.stop();
        }
    }

    /** Two steps: read a part, then charge its amount. Mappings would only read step 1. */
    private Capability twoStepCapability(int port) throws Exception {
        return capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "steps"
                      resources:
                        - path: "/pay"
                          operations:
                            - method: "POST"
                              name: "pay"
                              steps:
                                - type: call
                                  name: read-part
                                  call: shop.get-part
                                  with:
                                    partId: "{{partId}}"
                                - type: call
                                  name: charge
                                  call: shop.create-charge
                                  with:
                                    amount: "{{read-part.amount}}"
                              mappings:
                                - target: amount
                                  value: "$.read-part.amount"
                  consumes:
                    - type: "http"
                      namespace: "shop"
                      baseUri: "http://localhost:%d/v1"
                      resources:
                        - path: "/parts/{{partId}}"
                          name: "parts"
                          operations:
                            - method: "GET"
                              name: "get-part"
                        - path: "/charges"
                          name: "charges"
                          operations:
                            - method: "POST"
                              name: "create-charge"
                              body: |
                                {"amount": "{{amount}}"}
                """.formatted(schemaVersion, port));
    }

    private static Map<String, io.ikanos.spec.util.OperationStepSpec> twoStepSpec(
            Capability capability) {
        RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters()
                .get(0).getSpec();
        RestServerResourceSpec resourceSpec = serverSpec.getResources().values().iterator().next();
        return resourceSpec.getOperations().values().iterator().next().getSteps();
    }

    /** Canned upstream response; {@code hits} (optional) counts how often the path was called. */
    private record StubResponse(Status status, String body, AtomicInteger hits) {
    }

    private static Component createServer(int port, Map<String, StubResponse> pathToResponse)
            throws Exception {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                pathToResponse.forEach((path, stub) -> router.attach(path, new Restlet() {
                    @Override
                    public void handle(org.restlet.Request request, org.restlet.Response response) {
                        if (stub.hits() != null) {
                            stub.hits().incrementAndGet();
                        }
                        response.setStatus(stub.status());
                        response.setEntity(stub.body(), MediaType.APPLICATION_JSON);
                    }
                }));
                return router;
            }
        });
        return component;
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static Component createJsonServer(int port, Map<String, String> pathToBody)
        throws Exception {
      Component component = new Component();
      component.getServers().add(Protocol.HTTP, port);
      component.getDefaultHost().attach(new Application() {
            @Override
        public Restlet createInboundRoot() {
          Router router = new Router(getContext());
          pathToBody.forEach((path, responseBody) -> router.attach(path, new Restlet() {
            @Override
            public void handle(org.restlet.Request request, org.restlet.Response response) {
              response.setStatus(Status.SUCCESS_OK);
              response.setEntity(responseBody, MediaType.APPLICATION_JSON);
            }
          }));
          return router;
        }
      });
      return component;
    }

    private static int findFreePort() throws Exception {
      try (ServerSocket socket = new ServerSocket(0)) {
        return socket.getLocalPort();
      }
    }
  }


