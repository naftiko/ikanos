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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test proving the REST adapter honors {@code outputRawFormat} declared on the consumed
 * operation. Before the fix, the REST adapter read the format from the server (exposes) operation —
 * which never declares it — causing XML responses to be parsed as JSON and failing with
 * {@code JsonParseException}.
 */
public class RestXmlOutputFormatIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void handleShouldMapXmlResponseWhenConsumedOperationDeclaresOutputRawFormatXml()
            throws Exception {
        int port = findFreePort();
        Component upstream = createXmlServer(port);
        upstream.start();

        try {
            Capability capability = capabilityFromYaml("""
                    ikanos: "%s"
                    capability:
                      exposes:
                        - type: "rest"
                          address: "localhost"
                          port: 0
                          namespace: "test-rest"
                          resources:
                            - path: "/legacy/vessels"
                              name: "legacy-vessels"
                              operations:
                                - method: "GET"
                                  name: "list-legacy-vessels"
                                  call: "legacy.list-vessels"
                                  outputParameters:
                                    - type: "array"
                                      mapping: "$.vessel"
                                      items:
                                        type: "object"
                                        properties:
                                          code:
                                            type: "string"
                                            mapping: "$.vesselCode"
                                          name:
                                            type: "string"
                                            mapping: "$.vesselName"
                      consumes:
                        - type: "http"
                          namespace: "legacy"
                          baseUri: "http://localhost:%d"
                          resources:
                            - path: "/vessels"
                              name: "vessels"
                              operations:
                                - method: "GET"
                                  name: "list-vessels"
                                  outputRawFormat: "xml"
                    """.formatted(schemaVersion, port));

            RestServerSpec serverSpec =
                    (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
            ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                    serverSpec.getResources().values().iterator().next());

            Request request = new Request(Method.GET, "http://localhost/legacy/vessels");
            Response response = new Response(request);

            restlet.handle(request, response);

            assertEquals(Status.SUCCESS_OK, response.getStatus(),
                    "REST adapter should return 200 for mapped XML response");
            assertNotNull(response.getEntity(), "Response entity should not be null");

            JsonNode payload = JSON.readTree(response.getEntity().getText());
            assertTrue(payload.isArray(), "Mapped response should be a JSON array");
            assertEquals(2, payload.size(), "Should contain 2 vessels");
            assertEquals("V001", payload.get(0).path("code").asText());
            assertEquals("Sea Eagle", payload.get(0).path("name").asText());
            assertEquals("V002", payload.get(1).path("code").asText());
            assertEquals("Harbor Star", payload.get(1).path("name").asText());
        } finally {
            upstream.stop();
        }
    }

    private static Component createXmlServer(int port) throws Exception {
        String xmlBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <vessels>
                  <vessel>
                    <vesselCode>V001</vesselCode>
                    <vesselName>Sea Eagle</vesselName>
                  </vessel>
                  <vessel>
                    <vesselCode>V002</vesselCode>
                    <vesselName>Harbor Star</vesselName>
                  </vessel>
                </vessels>
                """;

        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/vessels", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(xmlBody, MediaType.APPLICATION_XML);
                    }
                });
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

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
