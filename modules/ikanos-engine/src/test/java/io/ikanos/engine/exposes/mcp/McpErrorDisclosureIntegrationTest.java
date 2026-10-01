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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.engine.LogCapture;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration tests proving that a failing MCP tool call does not disclose internal exception
 * detail to the caller, while the full detail stays in the server log under a correlation
 * identifier that is also returned to the caller.
 */
public class McpErrorDisclosureIntegrationTest {

    /**
     * Upstream payload that is not valid JSON. Its leading token is echoed verbatim in Jackson's
     * parse error message, so it stands in for any upstream detail that ends up in an exception.
     */
    private static final String UPSTREAM_MARKER = "UpstreamSecretToken7f3a";
    private static final String MALFORMED_UPSTREAM_BODY = UPSTREAM_MARKER + " not-json";

    /** A 32-hex OTel trace id, or a UUID when telemetry is disabled. */
    private static final Pattern CORRELATION_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}");

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldNotDiscloseInternalDetailWhenOutputMappingFails()
            throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, MALFORMED_UPSTREAM_BODY);
        upstream.start();

        try {
            String text = callListItems(port);

            assertFalse(text.contains(UPSTREAM_MARKER), "upstream payload leaked: " + text);
            assertFalse(text.contains("Exception"), "exception class leaked: " + text);
            assertFalse(text.contains("com.fasterxml"), "package path leaked: " + text);
            assertFalse(text.contains("io.ikanos"), "package path leaked: " + text);
            assertFalse(text.contains("\tat "), "stack frame leaked: " + text);
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldReturnCorrelationIdWhenOutputMappingFails() throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, MALFORMED_UPSTREAM_BODY);
        upstream.start();

        try {
            assertTrue(CORRELATION_ID.matcher(callListItems(port)).find(),
                    "expected a correlation id in the tool result");
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldLogFullDetailUnderTheReturnedCorrelationId()
            throws Exception {
        int port = findFreePort();
        Component upstream = createUpstream(port, MALFORMED_UPSTREAM_BODY);
        upstream.start();

        LogCapture logs = new LogCapture();

        try {
            Matcher id = CORRELATION_ID.matcher(callListItems(port));
            assertTrue(id.find(), "expected a correlation id in the tool result");
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

    private String callListItems(int upstreamPort) throws Exception {
        Capability capability = capabilityFromYaml(capabilityYaml(upstreamPort));
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "list-items");
        params.putObject("arguments");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        JsonNode response = dispatcher.dispatch(request).responseBody();

        assertTrue(response.path("result").path("isError").asBoolean(),
                "tool call should be an error, got: " + response);
        return response.path("result").path("content").get(0).path("text").asText();
    }

    private String capabilityYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: upstream
                      type: http
                      baseUri: "http://localhost:%d"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: GET
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: items-tools
                      tools:
                        list-items:
                          description: "List items"
                          call: upstream.list-items
                          outputParameters:
                            - type: object
                              properties:
                                name:
                                  type: string
                                  mapping: "$.name"
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
