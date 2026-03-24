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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
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
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;

public class OperationStepExecutorIntegrationTest {

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
                    naftiko: "1.0.0-alpha1"
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
                    """.formatted(port));

            RestServerSpec serverSpec = (RestServerSpec) capability.getServerAdapters().get(0)
                    .getSpec();
            RestServerResourceSpec resourceSpec = serverSpec.getResources().get(0);
            RestServerOperationSpec operationSpec = resourceSpec.getOperations().get(0);
            OperationStepExecutor executor = new OperationStepExecutor(capability);

            OperationStepExecutor.StepExecutionResult result = executor.executeSteps(
                    operationSpec.getSteps(),
                    Map.of("targetEmail", "bob@example.com", "requestingUserId", "u-1"));

            assertEquals("u-2", result.stepContext.getStepOutput("find-user").path("id").asText());
            assertEquals("eu",
                    result.stepContext.getStepOutput("fetch-profile").path("region").asText());
            assertNotNull(result.lastContext);
            assertTrue(result.lastContext.clientRequest.getResourceRef().toString().endsWith("/echo/eu"));
        } finally {
          server.stop();
        }
    }

    @Test
    public void applyOutputMappingsShouldReturnFirstMappedValue() throws Exception {
        OperationStepExecutor executor = new OperationStepExecutor(capabilityFromYaml("""
                naftiko: "1.0.0-alpha1"
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
                """));

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

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);
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