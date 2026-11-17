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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.spec.IkanosSpec;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

class ProtocolDispatcherCoverageTest {

    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    @Test
    void dispatchShouldReturnInternalErrorWhenRequestIsNull() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        ObjectNode response = dispatcher.dispatch(null).responseBody();

        assertNotNull(response);
        assertEquals(-32600, response.path("error").path("code").asInt());
        assertEquals("Invalid Request: request body is missing",
                response.path("error").path("message").asString());
        assertEquals("2.0", response.path("jsonrpc").asString());
    }

    @Test
    void dispatchShouldAnswerWhenMessageIsNotAnObject() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        DispatchResult result = dispatcher.dispatch(JSON.readTree("""
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"}]
                """));

        assertNotNull(result.responseBody(), "#949: only a Request object without id is a"
                + " notification; a batch array must still be answered with an error");
        assertTrue(result.isOnError(), "#949: a batch array is an invalid request, not a"
                + " notification");
    }

    @Test
    void dispatchShouldAnswerWhenIdIsNull() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        DispatchResult result = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":null,"method":"tools/list","params":{}}
                """)));

        assertNotNull(result.responseBody(), "#949: a message with an id member, even null, is a"
                + " request and must be answered");
    }

    @Test
    void dispatchShouldRejectNoIdMessageWhenJsonRpcVersionIsMissing() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        DispatchResult result = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {"method":"tools/list","params":{}}
                """)));

        assertNotNull(result.responseBody(), "#949: a message without jsonrpc is not a"
                + " notification and must be answered with an error");
        assertEquals(-32600, result.responseBody().path("error").path("code").asInt());
    }

    @Test
    void dispatchShouldRejectNoIdMessageWhenJsonRpcVersionIsWrong() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        DispatchResult result = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {"jsonrpc":"1.0","method":"tools/list","params":{}}
                """)));

        assertNotNull(result.responseBody(), "#949: a message whose jsonrpc is not '2.0' is not a"
                + " notification and must be answered with an error");
        assertEquals(-32600, result.responseBody().path("error").path("code").asInt());
    }

    @Test
    void dispatchShouldReturnNoResponseWhenMessageHasNoId() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        DispatchResult result = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}
                """)));

        assertNull(result.responseBody(), "#949: JSON-RPC forbids answering a notification"
                + " (a message without id), but got " + result.responseBody());
        assertFalse(result.isOnError(), "#949: a notification is not an error");
    }

    @Test
    void serverDiscoverShouldAdvertiseOnlyToolsWhenNoResourcesOrPrompts() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        JsonNode response = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}
                """))).responseBody();

        JsonNode capabilities = response.path("result").path("capabilities");
        assertFalse(capabilities.path("tools").isMissingNode());
        assertTrueMissing(capabilities.path("resources"));
        assertTrueMissing(capabilities.path("prompts"));
    }

    @Test
    void toolsCallUnknownToolShouldReturnInvalidParams() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        JsonNode response = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "method":"tools/call",
                  "params":{"name":"does-not-exist","arguments":{}}
                }
                """))).responseBody();

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertFalse(response.path("error").path("message").asString().isBlank());
    }

    @Test
    void resourcesAndPromptsInvalidParamsShouldReturnExpectedErrors() throws Exception {
        ProtocolDispatcher dispatcher =
                dispatcherFrom("src/test/resources/mcp/mcp-resources-prompts-capability.yaml");

        JsonNode readNullParams = dispatcher.dispatch(JSON.readTree(withProtocolVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\"}"))).responseBody();
        assertEquals(-32602, readNullParams.path("error").path("code").asInt());

        JsonNode readUnknownUri = dispatcher.dispatch(JSON.readTree(withProtocolVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"data://unknown\"}}")))
                .responseBody();
        assertEquals(-32602, readUnknownUri.path("error").path("code").asInt());

        JsonNode promptsNullParams = dispatcher.dispatch(JSON.readTree(withProtocolVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\"}"))).responseBody();
        assertEquals(-32602, promptsNullParams.path("error").path("code").asInt());
    }

    @Test
    void toolsCallShouldReturnIsErrorResultOnUnexpectedExecutionFailure() throws Exception {
        ProtocolDispatcher dispatcher = dispatcherFrom("src/test/resources/mcp/mcp-capability.yaml");

        JsonNode response = dispatcher.dispatch(JSON.readTree(withProtocolVersion("""
                {
                    "jsonrpc":"2.0",
                    "id":15,
                    "method":"tools/call",
                    "params":{
                        "name":"query-database",
                        "arguments":{"name":"x"}
                    }
                }
                """))).responseBody();

        assertEquals("2.0", response.path("jsonrpc").asString());
        assertEquals(15, response.path("id").asInt());
        assertEquals(true, response.path("result").path("isError").asBoolean());
        assertEquals("text", response.path("result").path("content").get(0).path("type")
                        .asString());
    }

    /**
     * A legacy list tool without a name gets a generated map key and its name from its aggregate
     * flow: a {@code tools/call} of that name must still get the tool's own authorization (#999).
     */
    @Test
    void requirementForShouldFindANamelessRefToolByItsFlowName() throws Exception {
        String yaml = "ikanos: " + VersionHelper.getSchemaVersion() + "\n" + """
                info:
                  display: Nameless ref tool
                  description: A legacy list tool without a name
                  tags: [Test]
                  created: '2026-11-14'
                  modified: '2026-11-14'
                capability:
                  aggregates:
                  - display: Records
                    namespace: records
                    flows:
                      update-record:
                        description: Update a record.
                        call: records-api.update
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9102
                    namespace: records-mcp
                    authentication:
                      type: oauth2
                      authorizationServerUri: https://idp.example.org
                      resource: https://app.example.org/mcp
                      scopes: [records.read, records.write]
                    authorization:
                      scopes: [records.read]
                    tools:
                    - ref: records.update-record
                      authorization:
                        scopes: [records.write]
                  consumes:
                  - type: http
                    namespace: records-api
                    baseUri: http://localhost:8080/v1
                    resources:
                      records:
                        path: records
                        operations:
                          update:
                            method: PUT
                """;
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        ProtocolDispatcher dispatcher =
                new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));

        JsonNode call = JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"update-record\"}}");
        assertEquals(java.util.List.of("records.write"),
                dispatcher.requirementFor(call).getScopes());
    }

    /**
     * {@code tasks/update}, {@code tasks/get} and {@code tasks/cancel} of a task answer to the
     * authorization of the tool the task runs, so a token of the same owner that lost the
     * tool's scopes cannot resume it; another owner's task gets the adapter's (#999).
     */
    @Test
    void requirementForShouldGiveATaskMethodTheAuthorizationOfTheTaskTool() throws Exception {
        String yaml = "ikanos: " + VersionHelper.getSchemaVersion() + "\n" + """
                info:
                  display: Task tool authorization
                  description: A task of a protected tool
                  tags: [Test]
                  created: '2026-11-17'
                  modified: '2026-11-17'
                capability:
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9103
                    namespace: records-mcp
                    authentication:
                      type: oauth2
                      authorizationServerUri: https://idp.example.org
                      resource: https://app.example.org/mcp
                      scopes: [records.read, records.write]
                    authorization:
                      scopes: [records.read]
                    tools:
                      update-record:
                        description: Update a record.
                        authorization:
                          scopes: [records.write]
                        outputParameters:
                        - type: string
                          value: ok
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: memory
                """;
        Capability capability = new Capability(
                IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().stream()
                .filter(McpServerAdapter.class::isInstance).findFirst().orElseThrow();
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);
        org.restlet.Request alice = requestOf("alice");
        String owner = AgentIdentityResolver.ownerOf("records-mcp", alice,
                java.util.Optional.empty());
        String taskId = adapter.getTaskService().create("mcp:records-mcp/update-record", owner,
                JSON.createObjectNode(), JSON::createObjectNode).path("taskId")
                .asString(null);
        assertNotNull(taskId);

        for (String method : java.util.List.of("tasks/update", "tasks/get", "tasks/cancel")) {
            JsonNode request = JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                    + method + "\",\"params\":{\"taskId\":\"" + taskId + "\"}}");
            assertEquals(java.util.List.of("records.write"),
                    dispatcher.requirementFor(request, alice).getScopes(), method);
            // Another owner's task: the adapter's, so the answer never tells it exists
            assertEquals(java.util.List.of("records.read"),
                    dispatcher.requirementFor(request, requestOf("bob")).getScopes(), method);
        }
    }

    private static org.restlet.Request requestOf(String subject) {
        org.restlet.Request request = new org.restlet.Request(org.restlet.data.Method.POST,
                "http://localhost/mcp");
        request.getAttributes().put(
                io.ikanos.engine.exposes.OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("https://idp.example.org")
                        .subject(subject).build());
        return request;
    }

    private static ProtocolDispatcher dispatcherFrom(String resourcePath) throws Exception {
        ObjectMapper yaml = IkanosMappers.lenientYaml();
        IkanosSpec spec = yaml.readValue(new File(resourcePath), IkanosSpec.class);
        Capability capability = new Capability(spec);
        return new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));
    }

    private static String withProtocolVersion(String requestJson) throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree(requestJson);
        ObjectNode params = request.has("params") && request.get("params").isObject()
                ? (ObjectNode) request.get("params")
                : request.putObject("params");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return JSON.writeValueAsString(request);
    }

    private static void assertTrueMissing(JsonNode node) {
        assertEquals(true, node.isMissingNode());
    }
}