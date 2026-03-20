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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.restlet.Restlet;
import org.restlet.security.ChallengeAuthenticator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;

public class RestServerAdapterTest {

    @Test
    public void basicAuthShouldBuildChallengeAuthenticatorChain() throws Exception {
        RestServerAdapter adapter = adapterFromYaml("""
                naftiko: "0.5"
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
                """);

        Restlet chain = adapter.getServer().getNext();
        assertTrue(chain instanceof ChallengeAuthenticator);
    }

    @Test
    public void digestAuthShouldBuildChallengeAuthenticatorChain() throws Exception {
        RestServerAdapter adapter = adapterFromYaml("""
                naftiko: "0.5"
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
                """);

        Restlet chain = adapter.getServer().getNext();
        assertTrue(chain instanceof ChallengeAuthenticator);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void extractAllowedVariablesShouldReturnAllExternalRefKeys() throws Exception {
        NaftikoSpec spec = parseYaml("""
                naftiko: "0.5"
                externalRefs:
                  - type: variables
                    keys:
                      auth_token: AUTH_TOKEN
                      api_key: API_KEY
                capability:
                  exposes: []
                  consumes: []
                """);

        Method method = RestServerAdapter.class
                .getDeclaredMethod("extractAllowedVariables", io.naftiko.spec.NaftikoSpec.class);
        method.setAccessible(true);
        Set<String> keys = (Set<String>) method.invoke(null, spec);

        assertEquals(2, keys.size());
        assertTrue(keys.contains("auth_token"));
        assertTrue(keys.contains("api_key"));
    }

    private static RestServerAdapter adapterFromYaml(String yaml) throws Exception {
        NaftikoSpec spec = parseYaml(yaml);
        Capability capability = new Capability(spec);
        return (RestServerAdapter) capability.getServerAdapters().get(0);
    }

    private static NaftikoSpec parseYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(yaml, NaftikoSpec.class);
    }
}