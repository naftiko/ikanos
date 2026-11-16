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

import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import org.restlet.Restlet;
import org.restlet.routing.TemplateRoute;
import org.restlet.security.ChallengeAuthenticator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.ikanos.spec.util.IkanosMappers;

public class RestServerAdapterTest {
  private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void basicAuthShouldBuildChallengeAuthenticatorChain() throws Exception {
        RestServerAdapter adapter = adapterFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "api"
                      authentication:
                        type: basic
                        username: demo
                        password: demo
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                  consumes: []
                """.formatted(schemaVersion));

        Restlet chain = adapter.getServer().getNext();
        assertTrue(chain instanceof ChallengeAuthenticator);
    }

    @Test
    public void digestAuthShouldBuildChallengeAuthenticatorChain() throws Exception {
        RestServerAdapter adapter = adapterFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "api"
                      authentication:
                        type: digest
                        username: demo
                        password: demo
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                  consumes: []
                """.formatted(schemaVersion));

        Restlet chain = adapter.getServer().getNext();
        assertTrue(chain instanceof ChallengeAuthenticator);
    }

    @Test
    public void hyphenatedPathParameterShouldBeRoutedAsAUriTemplateVariable() throws Exception {
        RestServerAdapter adapter = adapterFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "api"
                      resources:
                        - path: "/crew/{{crew-id}}"
                          inputParameters:
                            - name: "crew-id"
                              in: "path"
                              type: "string"
                          operations:
                            - method: "GET"
                              name: "get-crew"
                  consumes: []
                """.formatted(schemaVersion));

        // The declared resource's route: the adapter attaches its own input-request route first
        TemplateRoute route = adapter.getRouter().getRoutes().stream()
                .map(TemplateRoute.class::cast)
                .filter(r -> r.getTemplate().getPattern().startsWith("/crew/"))
                .findFirst().orElseThrow();
        Map<String, Object> variables = new HashMap<>();

        assertEquals("/crew/{crew-id}", route.getTemplate().getPattern(),
                "#978: the router converts a hyphenated name, as the OpenAPI export does");
        assertTrue(route.getTemplate().parse("/crew/c-42", variables) > 0);
        assertEquals("c-42", variables.get("crew-id"));
    }

    private static RestServerAdapter adapterFromYaml(String yaml) throws Exception {
        IkanosSpec spec = parseYaml(yaml);
        Capability capability = new Capability(spec);
        return (RestServerAdapter) capability.getServerAdapters().get(0);
    }

    private static IkanosSpec parseYaml(String yaml) throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        return mapper.readValue(yaml, IkanosSpec.class);
    }
}