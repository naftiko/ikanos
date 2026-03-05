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
package io.naftiko.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;
import io.naftiko.spec.exposes.ApiServerSpec;

public class ApiServerAuthenticationDeserializationTest {

    @Test
    public void shouldDeserializeApiServerBearerAuthentication() throws Exception {
        String yaml = """
                naftiko: 0.4
                info:
                  label: Test
                  description: Test
                capability:
                  exposes:
                    - type: api
                      namespace: test
                      port: 8080
                      authentication:
                        type: bearer
                        token: "{{api_token}}"
                      resources:
                        - path: /health
                          description: health endpoint
                          operations:
                            - method: GET
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);

        ApiServerSpec apiServer = (ApiServerSpec) spec.getCapability().getExposes().get(0);

        assertNotNull(apiServer.getAuthentication(), "Authentication should be present");
        assertInstanceOf(BearerAuthenticationSpec.class, apiServer.getAuthentication(),
                "Authentication should be bearer type");
        assertEquals("bearer", apiServer.getAuthentication().getType());
        assertEquals("{{api_token}}", ((BearerAuthenticationSpec) apiServer.getAuthentication()).getToken());
    }

    @Test
    public void shouldDeserializeApiServerApiKeyAuthentication() throws Exception {
        String yaml = """
                naftiko: 0.4
                info:
                  label: Test
                  description: Test
                capability:
                  exposes:
                    - type: api
                      namespace: test
                      port: 8080
                      authentication:
                        type: apikey
                        key: X-API-Key
                        value: abc123
                        placement: header
                      resources:
                        - path: /health
                          description: health endpoint
                          operations:
                            - method: GET
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);

        ApiServerSpec apiServer = (ApiServerSpec) spec.getCapability().getExposes().get(0);

        assertNotNull(apiServer.getAuthentication(), "Authentication should be present");
        assertInstanceOf(ApiKeyAuthenticationSpec.class, apiServer.getAuthentication(),
                "Authentication should be api key type");
        ApiKeyAuthenticationSpec apiKey = (ApiKeyAuthenticationSpec) apiServer.getAuthentication();
        assertEquals("apikey", apiKey.getType());
        assertEquals("X-API-Key", apiKey.getKey());
        assertEquals("abc123", apiKey.getValue());
        assertEquals("header", apiKey.getPlacement());
    }
}