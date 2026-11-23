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
package io.ikanos.client.smithy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import io.ikanos.engine.consumes.ClientAdapterRegistry;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.contract.ContractResult;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.smithy.SmithyClientSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The Smithy consumed adapter against an in-process Restlet upstream that answers the restJson1
 * HTTP bindings of {@code tracker.smithy}.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SmithyConsumedAdapterTest {

    private static Component upstream;
    private static int port;
    private static Path model;

    /** Answers the operations of the Tracker service, deterministically. */
    static final class Tracker extends Restlet {

        @Override
        public void handle(Request request, Response response) {
            String path = request.getResourceRef().getPath();
            String query = request.getResourceRef().getQuery();
            if (path.equals("/headers")) {
                json(response, 200, "{\"authorization\":" + quoted(header(request,
                        "Authorization")) + ",\"tenant\":" + quoted(header(request, "X-Tenant"))
                        + ",\"traceparent\":" + (header(request, "traceparent") != null) + "}");
            } else if (path.equals("/slow")) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                json(response, 200, "{\"done\":true}");
            } else if (path.startsWith("/projects/")) {
                String body = request.getEntityAsText();
                String received = request.getMethod() + " " + path + "?" + query + " " + body;
                json(response, 200, "{\"issue\":{\"id\":\"new-1\"},\"received\":"
                        + quoted(received) + "}");
            } else if (path.equals("/issues/missing")) {
                json(response, 404, "{\"__type\":\"IssueNotFound\","
                        + "\"message\":\"Issue missing not found\"}");
            } else if (path.equals("/issues/fault")) {
                json(response, 500, "{\"__type\":\"ServerFault\",\"message\":\"Store down\"}");
            } else if (path.equals("/issues/teapot")) {
                response.setStatus(Status.valueOf(418));
            } else if (path.startsWith("/issues/")) {
                String id = path.substring("/issues/".length());
                json(response, 200, "{\"issue\":{\"id\":\"" + id + "\",\"title\":\"Bug " + id
                        + (query != null ? " " + query : "") + "\",\"priority\":2,"
                        + "\"createdAt\":1700000000,\"attachment\":\"aGVsbG8=\","
                        + "\"points\":123456789012345678901234567890,\"ratio\":0.1}}");
            } else {
                response.setStatus(Status.CLIENT_ERROR_NOT_FOUND);
            }
        }

        private static String header(Request request, String name) {
            return request.getHeaders().getFirstValue(name, true);
        }

        private static String quoted(String text) {
            return text == null ? "null" : IkanosMappers.json().getNodeFactory()
                    .stringNode(text).toString();
        }

        private static void json(Response response, int status, String body) {
            response.setStatus(Status.valueOf(status));
            response.setEntity(body, MediaType.APPLICATION_JSON);
        }
    }

    @BeforeAll
    static void startUpstream() throws Exception {
        model = resource("tracker.smithy");
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, 0);
        upstream.getDefaultHost().attachDefault(new Tracker());
        upstream.start();
        port = upstream.getServers().get(0).getActualPort();
    }

    @AfterAll
    static void stopUpstream() throws Exception {
        upstream.stop();
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(SmithyConsumedAdapterTest.class.getClassLoader().getResource(name).toURI());
    }

    private static SmithyClientSpec spec(String operations, String extra) throws Exception {
        String yaml = """
                type: smithy
                namespace: tracker
                baseUri: "%s"
                model: "%s"
                service: "example.tracker#Tracker"
                %s
                operations:
                %s
                """.formatted("http://localhost:" + port, model.toString().replace('\\', '/'),
                extra, operations.indent(2).stripTrailing());
        return (SmithyClientSpec) IkanosMappers.yaml().readValue(yaml, ClientSpec.class);
    }

    private static final String GET_ISSUE = """
            get-issue:
              description: One issue.
              operation: GetIssue
              inputParameters:
                id: { type: string, required: true, description: Issue id., wireName: issueId }
                verbose: { type: boolean, description: Verbose. }
            """;

    private static final String CREATE_ISSUE = """
            create-issue:
              description: Create an issue.
              operation: example.tracker#CreateIssue
              inputParameters:
                project-id: { type: string, required: true, description: Project., wireName: projectId }
                notify: { type: boolean, description: Notify. }
                issue-title: { type: string, required: true, description: Title., wireName: title }
                priority: { type: integer, description: Priority. }
                assignee: { type: string, description: Assignee., wireName: details.assignee }
                team: { type: string, description: Team label., wireName: labels.team }
            """;

    private static final String SLOW = """
            slow:
              description: Slow.
              operation: SlowCall
              timeoutMs: 300
            """;

    private static final String HEADERS = """
            headers:
              description: Headers seen upstream.
              operation: EchoHeaders
            """;

    private static SmithyConsumedAdapter started(SmithyClientSpec spec) throws Exception {
        SmithyConsumedAdapter adapter = new SmithyConsumedAdapter(null, spec, Map.of());
        adapter.start();
        return adapter;
    }

    private static ConsumedResult call(SmithyConsumedAdapter adapter, String operation,
            Map<String, Object> values) throws Exception {
        return adapter.prepare(operation, new HashMap<>(values)).invoke();
    }

    @Test
    void prepareShouldSendPathQueryAndBodyBindingsByMemberName() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(CREATE_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "create-issue", Map.of("project-id", "p1",
                    "notify", true, "issue-title", "Crash", "priority", "3", "assignee", "ana",
                    "team", "core"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            assertEquals("new-1", result.document().at("/issue/id").asString());
            String received = result.document().get("received").asString();
            assertTrue(received.startsWith("POST /projects/p1/issues?notify=true "), received);
            assertTrue(received.contains("\"title\":\"Crash\""), received);
            assertTrue(received.contains("\"prio\":3"), "the protocol applies @jsonName: "
                    + received);
            assertTrue(received.contains("\"details\":{\"assignee\":\"ana\"}"), received);
            assertTrue(received.contains("\"labels\":{\"team\":\"core\"}"), received);
            assertTrue(result.nativeDocument());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void prepareShouldLeaveAbsentOptionalInputsOut() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(CREATE_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "create-issue",
                    Map.of("project-id", "p1", "issue-title", "Crash"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            String received = result.document().get("received").asString();
            assertTrue(received.contains("?null "), "no query: " + received);
            assertFalse(received.contains("prio"), received);
            assertFalse(received.contains("details"), received);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void outputShouldUseMemberNamesAndJsonFriendlyValues() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(GET_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "get-issue",
                    Map.of("id", "42", "verbose", true));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            assertEquals("Bug 42 verbose=true",
                    result.document().at("/issue/title").asString());
            assertEquals("2023-11-14T22:13:20Z",
                    result.document().at("/issue/createdAt").asString());
            assertEquals("aGVsbG8=", result.document().at("/issue/attachment").asString());
            assertEquals("123456789012345678901234567890",
                    result.document().at("/issue/points").bigIntegerValue().toString());
            assertEquals("0.1", result.document().at("/issue/ratio").decimalValue()
                    .toPlainString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void modeledClientErrorShouldFailWithItsHttpErrorStatus() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(GET_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "missing"));

            assertEquals(404, result.status());
            assertEquals("Issue missing not found", result.reason());
            assertEquals("IssueNotFound", ((ContractResult) result).nativeCode());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void modeledServerErrorWithoutHttpErrorShouldFailWith502() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(GET_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "fault"));

            assertEquals(502, result.status());
            assertEquals("ServerFault", ((ContractResult) result).nativeCode());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void unmodeledErrorShouldFailWithTheHttpStatus() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(GET_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "teapot"));

            assertEquals(418, result.status(), String.valueOf(result.reason()));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void timeoutShouldFailWith504() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(SLOW, ""));
        try {
            long start = System.nanoTime();
            ConsumedResult result = call(adapter, "slow", Map.of());

            assertEquals(504, result.status(), String.valueOf(result.reason()));
            assertTrue(System.nanoTime() - start < 2_500_000_000L, "one attempt, not retried");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void connectionFailureShouldReportNoResponse() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        SmithyClientSpec spec = spec(GET_ISSUE, "");
        spec.setBaseUri("http://localhost:" + closed);
        SmithyConsumedAdapter adapter = started(spec);
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "1"));

            assertTrue(result.noResponseReceived(), "status " + result.status());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void credentialAndStaticHeadersShouldReachTheUpstream() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(HEADERS, """
                authentication:
                  type: bearer
                  token: secret-token
                inputParameters:
                  X-Tenant: { in: header, value: acme }
                """));
        try {
            ConsumedResult result = call(adapter, "headers", Map.of());

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            assertEquals("Bearer secret-token",
                    result.document().get("authorization").asString());
            assertEquals("acme", result.document().get("tenant").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void awsSigV4ShouldSignTheRequest() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(HEADERS, """
                authentication:
                  type: awsSigV4
                  accessKeyId: AKIDEXAMPLE
                  secretAccessKey: secret
                  region: eu-west-1
                  service: tracker
                inputParameters:
                  X-Tenant: { in: header, value: acme }
                """));
        try {
            ConsumedResult result = call(adapter, "headers", Map.of());

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            String authorization = result.document().get("authorization").asString();
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"),
                    authorization);
            assertTrue(authorization.contains("/eu-west-1/tracker/aws4_request"), authorization);
            assertTrue(authorization.contains("x-tenant"), "static headers are signed: "
                    + authorization);
            assertEquals("acme", result.document().get("tenant").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void readOnlyShouldComeFromTheReadonlyTrait() throws Exception {
        SmithyConsumedAdapter adapter = new SmithyConsumedAdapter(null,
                spec(GET_ISSUE + CREATE_ISSUE, ""), Map.of());

        assertFalse(adapter.isWriteOperation("get-issue"), "@readonly reads");
        assertTrue(adapter.isWriteOperation("create-issue"), "no @readonly writes");
        assertTrue(adapter.declaresOperation("create-issue"));
        assertFalse(adapter.declaresOperation("other"));
    }

    @Test
    void constructorShouldRefuseReadOnlyOnAnOperationWithoutReadonly() throws Exception {
        SmithyClientSpec spec = spec(CREATE_ISSUE.replace("  operation:",
                "  readOnly: true\n  operation:"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'create-issue'"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnUnknownOperation() throws Exception {
        SmithyClientSpec spec = spec(GET_ISSUE.replace("GetIssue", "GetIssues"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'tracker'"), e.getMessage());
        assertTrue(e.getMessage().contains("GetIssues"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAWireNameTheInputDoesNotDefine() throws Exception {
        SmithyClientSpec spec = spec(CREATE_ISSUE.replace("details.assignee",
                "details.owner"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("details.owner"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAJsonNameAsWireName() throws Exception {
        SmithyClientSpec spec = spec(CREATE_ISSUE.replace("priority: { type: integer,",
                "priority: { type: integer, wireName: prio,"), "");

        assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void constructorShouldRefuseARequiredMemberThatNoInputFills() throws Exception {
        SmithyClientSpec spec = spec("""
                create-issue:
                  description: Create an issue.
                  operation: CreateIssue
                  inputParameters:
                    project-id: { type: string, required: true, description: Project., wireName: projectId }
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("[title]"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAProtocolTheServiceDoesNotDeclare() throws Exception {
        SmithyClientSpec spec = spec(GET_ISSUE, "protocol: \"aws.protocols#awsJson1_0\"");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("awsJson1_0"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnUnknownService() throws Exception {
        SmithyClientSpec spec = spec(GET_ISSUE, "");
        spec.setService("example.tracker#Other");

        assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void constructorShouldAcceptTheProtocolByName() throws Exception {
        SmithyConsumedAdapter adapter = new SmithyConsumedAdapter(null,
                spec(GET_ISSUE, "protocol: restJson1"), Map.of());

        assertEquals("aws.protocols#restJson1", adapter.protocolId().toString());
    }

    @Test
    void factoryShouldBeFoundThroughServiceLoader() {
        assertTrue(ClientAdapterRegistry.getDefault().registeredTypes().contains("smithy"));
    }
}
