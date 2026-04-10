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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.util.VersionHelper;

public class AuthenticationIntegrationTest {
    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void bearerAuthenticationShouldReturnUnauthorizedWithoutTokenAndOkWithToken()
            throws Exception {
        String yaml = """
                naftiko: "%s"
                info:
                  label: "Auth test"
                  description: "Auth test"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "sample"
                      authentication:
                        type: "bearer"
                        token: "token-123"
                      resources:
                        - path: "/hello"
                          description: "hello"
                          operations:
                            - method: "GET"
                              outputParameters:
                                - type: "string"
                                  value: "ok"
                """.formatted(schemaVersion);

        Restlet root = buildRootRestlet(yaml);

        Request unauthorizedRequest = new Request(Method.GET, "/hello");
        Response unauthorizedResponse = new Response(unauthorizedRequest);
        root.handle(unauthorizedRequest, unauthorizedResponse);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, unauthorizedResponse.getStatus(),
                "Missing bearer token should be rejected");

        Request authorizedRequest = new Request(Method.GET, "/hello");
        authorizedRequest.getHeaders().set("Authorization", "Bearer token-123");
        Response authorizedResponse = new Response(authorizedRequest);
        root.handle(authorizedRequest, authorizedResponse);

        assertEquals(Status.SUCCESS_OK, authorizedResponse.getStatus(),
                "Valid bearer token should be accepted");
        assertNotNull(authorizedResponse.getEntity(), "Authorized response should contain payload");
        assertEquals(MediaType.APPLICATION_JSON, authorizedResponse.getEntity().getMediaType(),
                "Output mapping should return json payload");
        String payload = authorizedResponse.getEntity().getText();
        assertTrue(payload.contains("ok"), "Payload should contain mapped output value");
    }

    @Test
    public void apiKeyAuthenticationShouldReturnUnauthorizedWithoutHeaderAndOkWithHeader()
            throws Exception {
        String yaml = """
                naftiko: "%s"
                info:
                  label: "Auth test"
                  description: "Auth test"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "sample"
                      authentication:
                        type: "apikey"
                        key: "X-API-Key"
                        value: "abc123"
                        placement: "header"
                      resources:
                        - path: "/hello"
                          description: "hello"
                          operations:
                            - method: "GET"
                              outputParameters:
                                - type: "string"
                                  value: "ok"
                """.formatted(schemaVersion);

        Restlet root = buildRootRestlet(yaml);

        Request unauthorizedRequest = new Request(Method.GET, "/hello");
        Response unauthorizedResponse = new Response(unauthorizedRequest);
        root.handle(unauthorizedRequest, unauthorizedResponse);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, unauthorizedResponse.getStatus(),
                "Missing API key header should be rejected");

        Request authorizedRequest = new Request(Method.GET, "/hello");
        authorizedRequest.getHeaders().set("X-API-Key", "abc123");
        Response authorizedResponse = new Response(authorizedRequest);
        root.handle(authorizedRequest, authorizedResponse);

        assertEquals(Status.SUCCESS_OK, authorizedResponse.getStatus(),
                "Valid API key header should be accepted");
    }

    private Restlet buildRootRestlet(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);

        Capability capability = new Capability(spec);
        RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(0);
        return adapter.getServer().getNext();
    }
}