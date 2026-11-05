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

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Encoding;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.engine.application.EncodeRepresentation;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration tests proving that the body of an upstream error response reaches the MCP tool
 * result, so that the agent can tell an expired key from a missing scope or a rate limit (#925).
 */
public class McpUpstreamErrorBodyIntegrationTest {

    private static final String ERROR_BODY = "{\"errors\":[{\"message\":"
            + "\"Authentication required, not authenticated\",\"extensions\":"
            + "{\"code\":\"AUTHENTICATION_ERROR\"}}]}";

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    public void toolResultShouldCarryTheUpstreamErrorBody(int status) throws Exception {
        String text = callFailingTool(response -> {
            response.setStatus(Status.valueOf(status));
            response.setEntity(ERROR_BODY, MediaType.APPLICATION_JSON);
        });

        assertTrue(text.contains("AUTHENTICATION_ERROR"), "HTTP " + status + ": " + text);
    }

    @Test
    public void toolResultShouldCarryTheBodyOfAChallengingUnauthorizedResponse()
            throws Exception {
        String text = callFailingTool(response -> {
            response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
            response.getChallengeRequests()
                    .add(new ChallengeRequest(ChallengeScheme.HTTP_BASIC, "upstream"));
            response.setEntity(ERROR_BODY, MediaType.APPLICATION_JSON);
        });

        assertTrue(text.contains("AUTHENTICATION_ERROR"), text);
    }

    @Test
    public void toolResultShouldCarryACompressedErrorBody() throws Exception {
        String text = callFailingTool(response -> {
            response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
            response.setEntity(new EncodeRepresentation(Encoding.GZIP,
                    new StringRepresentation(ERROR_BODY, MediaType.APPLICATION_JSON)));
        });

        assertTrue(text.contains("AUTHENTICATION_ERROR"), text);
    }

    private String callFailingTool(Consumer<Response> upstreamResponse) throws Exception {
        int upstreamPort = findFreePort();
        Component upstream = createUpstream(upstreamPort, upstreamResponse);
        upstream.start();
        try {
            Capability capability = capabilityFromYaml(capabilityYaml(upstreamPort));
            ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                    (McpServerAdapter) capability.getServerAdapters().get(0));
            ObjectMapper mapper = IkanosMappers.lenientJson();

            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 1);
            request.put("method", "tools/call");
            ObjectNode params = request.putObject("params");
            params.put("name", "query");
            params.putObject("arguments");
            params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                    ProtocolDispatcher.MCP_PROTOCOL_VERSION);

            JsonNode response = dispatcher.dispatch(request).responseBody();

            assertTrue(response.path("result").path("isError").asBoolean(),
                    "tool call should be an error, got: " + response);
            return response.path("result").path("content").get(0).path("text").asString();
        } finally {
            upstream.stop();
        }
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
                        graphql:
                          path: "/graphql"
                          operations:
                            query:
                              method: POST
                              body:
                                type: json
                                data: '{"query":"{ viewer { id } }"}'
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: upstream-tools
                      tools:
                        query:
                          description: "Query the upstream"
                          call: upstream.query
                """.formatted(schemaVersion, port);
    }

    private static Component createUpstream(int port, Consumer<Response> upstreamResponse) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/graphql", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        upstreamResponse.accept(response);
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
