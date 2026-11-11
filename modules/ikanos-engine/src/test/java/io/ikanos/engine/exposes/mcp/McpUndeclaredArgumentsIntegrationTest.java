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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * An MCP tool argument must not replace a value the operator provides (GHSA-23xc-9c84-r6qv): an
 * {@code in: environment} consumed parameter, whether the body or the URI uses it. {@code PATH}
 * stands for an operator environment value, since it is set in every test JVM;
 * {@value #UNSET_VARIABLE} for one the operator left unset.
 */
public class McpUndeclaredArgumentsIntegrationTest {

    private static final ObjectMapper MAPPER = IkanosMappers.lenientJson();

    private static final String UNSET_VARIABLE = "IKANOS_TEST_GHSA_23XC_UNSET";

    private final String schemaVersion = VersionHelper.getSchemaVersion();

    @Test
    public void handleToolCallShouldNotLetUndeclaredArgumentReplaceEnvironmentParameter()
            throws Exception {
        String sent = callAndRecordBody(environmentCapabilityYaml(false),
                MAPPER.createObjectNode().put("PATH", "admin-user"));

        assertEquals(System.getenv("PATH"), formValue(sent, "who"),
                "an undeclared argument must not replace the operator's environment value");
    }

    @Test
    public void handleToolCallShouldNotLetDeclaredArgumentReplaceEnvironmentParameter()
            throws Exception {
        String sent = callAndRecordBody(environmentCapabilityYaml(true),
                MAPPER.createObjectNode().put("PATH", "admin-user"));

        assertEquals(System.getenv("PATH"), formValue(sent, "who"),
                "an in: environment parameter must come from the environment only");
    }

    @Test
    public void handleToolCallShouldNotLetUndeclaredArgumentFillUriFromUnsetEnvironmentParameter()
            throws Exception {
        assertEquals("/things/t-", callAndRecordPath(false),
                "an undeclared argument must not take the place of an unset environment value");
    }

    @Test
    public void handleToolCallShouldNotLetDeclaredArgumentFillUriFromUnsetEnvironmentParameter()
            throws Exception {
        // The URI used to be resolved before the environment values were applied, so a declared
        // argument with the same name chose the path the request was sent to.
        assertEquals("/things/t-", callAndRecordPath(true),
                "an in: environment parameter must come from the environment only");
    }

    @Test
    public void handleToolCallShouldNotLetDeclaredArgumentFillUriFromResourceLevelEnvironmentParameter()
            throws Exception {
        // A resource can declare input parameters too (#909): its in: environment parameters
        // must be applied before the URI is resolved, like the adapter's and the operation's.
        assertEquals("/things/t-", callAndRecordPath(true, true),
                "an in: environment parameter declared on the resource must come from the environment only");
    }

    @Test
    public void handleToolCallShouldStillPassDeclaredArguments() throws Exception {
        String sent = callAndRecordBody(environmentCapabilityYaml(false),
                MAPPER.createObjectNode().put("name", "Acme").put("extra", "ignored"));

        assertEquals("Acme", formValue(sent, "name"));
        assertEquals("", formValue(sent, "extra"),
                "the body template uses {{extra}}, so a kept undeclared argument would show here");
    }

    private String callAndRecordBody(String yamlTemplate, ObjectNode arguments) throws Exception {
        return callAndRecord(yamlTemplate, "get-token", arguments)[0];
    }

    private String callAndRecordPath(boolean declareVariable) throws Exception {
        return callAndRecordPath(declareVariable, false);
    }

    private String callAndRecordPath(boolean declareVariable, boolean onResource)
            throws Exception {
        assumeTrue(System.getenv(UNSET_VARIABLE) == null, UNSET_VARIABLE + " must be unset");
        ObjectNode arguments = MAPPER.createObjectNode().put("name", "Acme")
                .put(UNSET_VARIABLE, "evil");
        return callAndRecord(pathCapabilityYaml(declareVariable, onResource), "list-things",
                arguments)[1];
    }

    /** Call the tool and return the body and the path the upstream received. */
    private String[] callAndRecord(String yamlTemplate, String tool, ObjectNode arguments)
            throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        int port = findFreePort();
        Component upstream = createRecordingUpstream(port, receivedBody, receivedPath);
        upstream.start();

        try {
            JsonNode result = callTool(capabilityFromYaml(yamlTemplate.formatted(port)), tool,
                    arguments);

            assertFalse(result.path("isError").asBoolean(), "tool call failed: " + result);
            assertNotNull(receivedPath.get(), "the upstream received no request");
            return new String[] {receivedBody.get(), receivedPath.get()};
        } finally {
            upstream.stop();
        }
    }

    private static String formValue(String body, String name) {
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private JsonNode callTool(Capability capability, String tool, ObjectNode arguments) {
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(
                (McpServerAdapter) capability.getServerAdapters().get(0));

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);

        return dispatcher.dispatch(request).responseBody().path("result");
    }

    /**
     * A token request built from the operator's environment, as in the advisory. When
     * {@code declarePath} is set, the tool also declares a {@code PATH} input.
     */
    private String environmentCapabilityYaml(boolean declarePath) {
        String pathInput = declarePath ? """
                            PATH:
                              type: string
                              required: false
                              description: "Same name as the environment parameter"
                """ : "";
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: upstream
                      type: http
                      baseUri: "http://localhost:%%d"
                      resources:
                        token:
                          path: "/token"
                          operations:
                            get-token:
                              method: POST
                              inputParameters:
                                PATH:
                                  in: environment
                              body:
                                type: formUrlEncoded
                                data: "who={{PATH}}&name={{name}}&extra={{extra}}"
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: demo
                      tools:
                        get-token:
                          description: "Request a token with the operator's identity"
                          inputParameters:
                            name:
                              type: string
                              required: false
                              description: "Display name"
                %s
                          call: upstream.get-token
                """.formatted(schemaVersion, pathInput);
    }

    /**
     * The resource path uses an environment parameter the operator left unset. When
     * {@code declareVariable} is set, the tool also declares an input with the same name.
     */
    private String pathCapabilityYaml(boolean declareVariable, boolean onResource) {
        String variableInput = declareVariable ? """
                            %s:
                              type: string
                              required: false
                              description: "Same name as the environment parameter"
                """.formatted(UNSET_VARIABLE) : "";
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: upstream
                      type: http
                      baseUri: "http://localhost:%%d"
                      resources:
                        things:
                          path: "/things/t-{{%s}}"
                %s
                          operations:
                            list-things:
                              method: GET
                %s
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: demo
                      tools:
                        list-things:
                          description: "List the operator's things"
                          inputParameters:
                            name:
                              type: string
                              required: false
                              description: "Display name"
                %s
                          call: upstream.list-things
                """.formatted(schemaVersion, UNSET_VARIABLE,
                        onResource ? environmentInput(10) : "",
                        onResource ? "" : environmentInput(14), variableInput);
    }

    /** An {@code in: environment} input parameter named {@code UNSET_VARIABLE}, at a YAML indent. */
    private static String environmentInput(int indent) {
        String pad = " ".repeat(indent);
        return pad + "inputParameters:\n"
                + pad + "  " + UNSET_VARIABLE + ":\n"
                + pad + "    in: environment";
    }

    private static Component createRecordingUpstream(int port, AtomicReference<String> body,
            AtomicReference<String> path) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attachDefault(new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        path.set(request.getResourceRef().getPath());
                        try {
                            body.set(request.getEntity() != null
                                    ? request.getEntity().getText()
                                    : "");
                        } catch (java.io.IOException e) {
                            body.set("<unreadable: " + e.getMessage() + ">");
                        }
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
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
