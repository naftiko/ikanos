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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.engine.LogCapture;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration tests proving that a failing REST operation does not disclose internal exception
 * detail to the HTTP caller, while the full detail stays in the server log under a correlation
 * identifier that is also returned to the caller.
 */
public class RestErrorDisclosureIntegrationTest {

    /**
     * Upstream payload that is not valid JSON. Its leading token is echoed verbatim in Jackson's
     * parse error message, so it stands in for any upstream detail that ends up in an exception.
     */
    private static final String UPSTREAM_MARKER = "UpstreamSecretToken7f3a";
    private static final String MALFORMED_UPSTREAM_BODY = UPSTREAM_MARKER + " not-json";

    /** A 32-hex OTel trace id, or a UUID when telemetry is disabled. */
    private static final Pattern CORRELATION_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}");

    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void handleShouldNotDiscloseInternalDetailWhenOutputMappingFails() throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, MALFORMED_UPSTREAM_BODY);
        upstream.start();

        try {
            Response response = invokeGetItems(capabilityFromYaml(capabilityYaml(port)));

            assertEquals(Status.SERVER_ERROR_INTERNAL, response.getStatus());
            String body = response.getEntity().getText();
            assertFalse(body.contains(UPSTREAM_MARKER), "upstream payload leaked: " + body);
            assertFalse(body.contains("Exception"), "exception class leaked: " + body);
            assertFalse(body.contains("com.fasterxml"), "package path leaked: " + body);
            assertFalse(body.contains("io.ikanos"), "package path leaked: " + body);
            assertFalse(body.contains("\tat "), "stack frame leaked: " + body);
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleShouldReturnCorrelationIdWhenOutputMappingFails() throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, MALFORMED_UPSTREAM_BODY);
        upstream.start();

        try {
            Response response = invokeGetItems(capabilityFromYaml(capabilityYaml(port)));

            assertTrue(CORRELATION_ID.matcher(response.getEntity().getText()).find(),
                    "expected a correlation id in the body");
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleShouldLogFullDetailUnderTheReturnedCorrelationIdWhenOutputMappingFails()
            throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, MALFORMED_UPSTREAM_BODY);
        upstream.start();

        LogCapture logs = new LogCapture();

        try {
            Response response = invokeGetItems(capabilityFromYaml(capabilityYaml(port)));

            Matcher id = CORRELATION_ID.matcher(response.getEntity().getText());
            assertTrue(id.find(), "expected a correlation id in the body");
            String correlationId = id.group();

            List<String> lines = logs.messages();
            assertTrue(lines.stream().anyMatch(
                    m -> m.contains(correlationId) && m.contains(UPSTREAM_MARKER)),
                    "expected a log line carrying both the id and the full detail, got: " + lines);
        } finally {
            upstream.stop();
            logs.close();
        }
    }

    private Response invokeGetItems(Capability capability) {
        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().values().iterator().next());

        Request request = new Request(Method.GET, "http://localhost/items");
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "items"
                      resources:
                        - path: "/items"
                          name: "items"
                          operations:
                            - method: "GET"
                              name: "list-items"
                              call: "upstream.list-items"
                              outputParameters:
                                - type: "object"
                                  properties:
                                    name:
                                      type: "string"
                                      mapping: "$.name"
                  consumes:
                    - type: "http"
                      namespace: "upstream"
                      baseUri: "http://localhost:%d"
                      resources:
                        - path: "/items"
                          name: "items"
                          operations:
                            - method: "GET"
                              name: "list-items"
                """.formatted(schemaVersion, port);
    }

    private static Component createUpstream(int port, String body) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/items", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(body, MediaType.APPLICATION_JSON);
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
