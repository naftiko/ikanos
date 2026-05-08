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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

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
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.VersionHelper;

class ResourceHandlerDynamicTest {
    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    void readShouldResolveDynamicResourceWithTemplateParamsAndOutputMapping() throws Exception {
        int port = findFreePort();
        Component server = createJsonServer(port, Map.of(
                "/v1/users/u-1", "{\"id\":\"u-1\",\"name\":\"Alice\"}",
                "/v1/users/u-2", "{\"id\":\"u-2\",\"name\":\"Bob\"}"));
        server.start();

        try {
            Capability capability = capabilityFromYaml("""
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
                      consumes:
                        - type: "http"
                          namespace: "mock-api"
                          baseUri: "http://localhost:%d/v1"
                          resources:
                            - path: "/users/{{userId}}"
                              name: "users"
                              operations:
                                - method: "GET"
                                  name: "get-user"
                    """.formatted(schemaVersion, port));

            OutputParameterSpec mapped = new OutputParameterSpec();
            mapped.setType("string");
            mapped.setMapping("$.name");

            McpServerResourceSpec mappedSpec = new McpServerResourceSpec();
            mappedSpec.setName("user-name");
            mappedSpec.setUri("data://users/{userId}/name");
            mappedSpec.setCall(new ServerCallSpec("mock-api.get-user"));
            mappedSpec.getOutputParameters().add(mapped);

            OutputParameterSpec missing = new OutputParameterSpec();
            missing.setType("string");
            missing.setMapping("$.missing");

            McpServerResourceSpec rawFallbackSpec = new McpServerResourceSpec();
            rawFallbackSpec.setName("user-raw");
            rawFallbackSpec.setUri("data://users/{userId}/raw");
            rawFallbackSpec.setCall(new ServerCallSpec("mock-api.get-user"));
            rawFallbackSpec.getOutputParameters().add(missing);

            ResourceHandler handler =
                    new ResourceHandler(capability, List.of(mappedSpec, rawFallbackSpec), null);

            List<ResourceHandler.ResourceContent> mappedContent =
                    handler.read("data://users/u-1/name");
            assertEquals(1, mappedContent.size());
            assertEquals("\"Alice\"", mappedContent.get(0).text);
            assertEquals("application/json", mappedContent.get(0).mimeType);

            List<ResourceHandler.ResourceContent> rawContent =
                    handler.read("data://users/u-2/raw");
            assertEquals(1, rawContent.size());
            assertTrue(rawContent.get(0).text.contains("\"id\":\"u-2\""));
            assertEquals("application/json", rawContent.get(0).mimeType);
        } finally {
            server.stop();
        }
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
                    public void handle(org.restlet.Request request,
                            org.restlet.Response response) {
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