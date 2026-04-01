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
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Restlet;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;

public class ForwardHeaderIntegrationTest {

    @Test
    public void forwardShouldSetNotionVersionHeaderFromValueField() throws Exception {
        AtomicReference<String> receivedVersion = new AtomicReference<>();

      int mockPort = findFreePort();
      Component mockServer = new Component();
      mockServer.getServers().add(Protocol.HTTP, mockPort);
      mockServer.getDefaultHost().attach(new Application() {
            @Override
        public Restlet createInboundRoot() {
          Router router = new Router(getContext());
          router.attach("/v1/pages", new Restlet() {
            @Override
            public void handle(Request request, Response response) {
              receivedVersion.set(request.getHeaders().getFirstValue("Notion-Version",
                  true));
              response.setStatus(Status.SUCCESS_OK);
              response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
                    }
          });
          return router;
            }
        });
        mockServer.start();

        try {
            String yaml = """
                    naftiko: \"0.4\"
                    capability:
                      exposes:
                        - type: \"rest\"
                          address: \"localhost\"
                          port: 0
                          namespace: \"sample\"
                          resources:
                            - path: \"/notion/{{path}}\"
                              forward:
                                targetNamespace: notion
                                trustedHeaders:
                                  - Notion-Version

                      consumes:
                        - type: \"http\"
                          namespace: \"notion\"
                          baseUri: \"http://localhost:%d/v1\"
                          inputParameters:
                            - name: \"Notion-Version\"
                              in: \"header\"
                              value: \"2025-09-03\"
                    """.formatted(mockPort);

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            NaftikoSpec spec = mapper.readValue(yaml, NaftikoSpec.class);

            Capability capability = new Capability(spec);
            RestServerAdapter adapter = (RestServerAdapter) capability.getServerAdapters().get(0);
            RestServerSpec serverSpec = (RestServerSpec) adapter.getSpec();
            RestServerResourceSpec resourceSpec = serverSpec.getResources().get(0);
            ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec, resourceSpec);

                Request request = new Request(org.restlet.data.Method.GET,
                  "http://localhost/notion/pages");
            request.getAttributes().put("path", "pages");
            Response response = new Response(request);

            Method forwardMethod = ResourceRestlet.class.getDeclaredMethod("handleFromForwardSpec",
                    Request.class, Response.class);
            forwardMethod.setAccessible(true);
            boolean handled = (boolean) forwardMethod.invoke(restlet, request, response);

            assertTrue(handled, "Forward request should be handled");
            assertEquals(Status.SUCCESS_OK, response.getStatus(), "Forward response should be 200");
            assertNotNull(response.getEntity(), "Forward response should include downstream entity");
            assertEquals(MediaType.APPLICATION_JSON, response.getEntity().getMediaType(),
                    "Forward response should be json");

            assertEquals("2025-09-03", receivedVersion.get(),
                    "Notion-Version should be set from inputParameters.value in forward mode");
        } finally {
          mockServer.stop();
        }
    }

      private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
          return socket.getLocalPort();
        }
      }
}
