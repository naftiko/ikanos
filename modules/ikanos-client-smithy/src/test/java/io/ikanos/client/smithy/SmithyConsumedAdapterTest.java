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

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.restlet.representation.OutputRepresentation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.ikanos.engine.consumes.ClientAdapterRegistry;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.contract.ContractResult;
import io.ikanos.engine.observability.TelemetryBootstrap;
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

    /** The secret access key of the awsSigV4 tests, which the upstream checks signatures with. */
    private static final String SECRET = "secret";

    /** Answers the operations of the Tracker service, deterministically. */
    static final class Tracker extends Restlet {

        @Override
        public void handle(Request request, Response response) {
            String path = request.getResourceRef().getPath();
            String query = request.getResourceRef().getQuery();
            if (path.equals("/headers")) {
                json(response, 200, "{\"authorization\":" + quoted(header(request,
                        "Authorization")) + ",\"tenant\":" + quoted(header(request, "X-Tenant"))
                        + ",\"traceparent\":" + (header(request, "traceparent") != null)
                        + ",\"signatureValid\":" + signatureValid(request) + "}");
            } else if (path.equals("/slow")) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                json(response, 200, "{\"done\":true}");
            } else if (path.equals("/slow-body")) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity(new DelayedBody());
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
            } else if (path.equals("/issues/moved")) {
                response.setStatus(Status.REDIRECTION_FOUND);
                response.setLocationRef("http://localhost:" + port + "/issues/elsewhere");
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

        /**
         * Recomputes the SigV4 signature of the request received, from its method, path, query,
         * signed headers and body, with {@code SECRET} and the scope of its Authorization header.
         */
        static boolean signatureValid(Request request) {
            String authorization = header(request, "Authorization");
            String prefix = "AWS4-HMAC-SHA256 ";
            if (authorization == null || !authorization.startsWith(prefix)) {
                return false;
            }
            Map<String, String> parts = new HashMap<>();
            for (String part : authorization.substring(prefix.length()).split(",")) {
                String[] pair = part.trim().split("=", 2);
                parts.put(pair[0], pair.length > 1 ? pair[1] : "");
            }
            String credential = parts.get("Credential");
            String signedHeaders = parts.get("SignedHeaders");
            if (credential == null || signedHeaders == null || !credential.contains("/")) {
                return false;
            }
            String scope = credential.substring(credential.indexOf('/') + 1);
            StringBuilder canonical = new StringBuilder()
                    .append(request.getMethod().getName()).append('\n')
                    .append(request.getResourceRef().getPath()).append('\n')
                    .append(canonicalQuery(request.getResourceRef().getQuery())).append('\n');
            for (String name : signedHeaders.split(";")) {
                String value = request.getHeaders().getValues(name, ",", true);
                if (value == null && name.equals("host")) {
                    value = "localhost:" + port;
                }
                canonical.append(name).append(':')
                        .append(value == null ? "" : value.trim().replaceAll(" +", " "))
                        .append('\n');
            }
            String body = request.isEntityAvailable() ? request.getEntityAsText() : null;
            canonical.append('\n').append(signedHeaders).append('\n')
                    .append(sha256(body == null ? "" : body));
            String stringToSign = "AWS4-HMAC-SHA256\n" + header(request, "X-Amz-Date") + "\n"
                    + scope + "\n" + sha256(canonical.toString());
            // date, region, service, aws4_request
            byte[] key = ("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8);
            for (String segment : scope.split("/")) {
                key = hmac(key, segment);
            }
            return HexFormat.of().formatHex(hmac(key, stringToSign))
                    .equals(parts.get("Signature"));
        }

        private static String canonicalQuery(String query) {
            if (query == null || query.isEmpty()) {
                return "";
            }
            String[] pairs = query.split("&");
            Arrays.sort(pairs);
            return String.join("&", pairs);
        }

        private static String sha256(String text) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] hmac(byte[] key, String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
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

    /** A JSON body whose first byte is sent at once, the rest three seconds later. */
    static final class DelayedBody extends OutputRepresentation {

        DelayedBody() {
            super(MediaType.APPLICATION_JSON);
        }

        @Override
        public void write(OutputStream out) throws IOException {
            out.write('{');
            out.flush();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.write("\"done\":true}".getBytes(StandardCharsets.UTF_8));
            out.flush();
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
        // Relative to the working directory, the capability directory of an adapter built without
        // a capability: contract files outside it are refused.
        return Path.of("").toAbsolutePath().relativize(
                Path.of(SmithyConsumedAdapterTest.class.getClassLoader().getResource(name).toURI()));
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

    private static final String SLOW_BODY = """
            slow-body:
              description: Slow body.
              operation: SlowBodyCall
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
    void wireNameShouldGoThroughAMapKeyIntoAUnionMember() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(CREATE_ISSUE + """
                    severity: { type: string, description: Severity., wireName: fields.severity.text }
                """, ""));
        try {
            ConsumedResult result = call(adapter, "create-issue", Map.of("project-id", "p1",
                    "issue-title", "Crash", "severity", "high"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            String received = result.document().get("received").asString();
            assertTrue(received.contains("\"fields\":{\"severity\":{\"text\":\"high\"}}"),
                    received);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void constructorShouldRefuseTwoMembersOfOneUnion() throws Exception {
        SmithyClientSpec spec = spec(CREATE_ISSUE + """
                    severity: { type: string, description: Severity., wireName: fields.severity.text }
                    level: { type: integer, description: Level., wireName: fields.severity.number }
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'severity' and 'level'"), e.getMessage());
        assertTrue(e.getMessage().contains("union 'FieldValue' at 'fields.severity'"),
                e.getMessage());
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
    void timeoutShouldFailWith504WhenTheBodyComesLate() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(SLOW_BODY, ""));
        try {
            long start = System.nanoTime();
            ConsumedResult result = call(adapter, "slow-body", Map.of());

            assertEquals(504, result.status(), String.valueOf(result.reason()));
            assertTrue(System.nanoTime() - start < 2_500_000_000L,
                    "timeoutMs bounds the body as well as the head");
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

    private static final String SIGV4 = """
            authentication:
              type: awsSigV4
              accessKeyId: AKIDEXAMPLE
              secretAccessKey: %s
              region: eu-west-1
              %s
            inputParameters:
              X-Tenant: { in: header, value: acme }
            """;

    @Test
    void awsSigV4ShouldSignTheRequest() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(HEADERS,
                SIGV4.formatted(SECRET, "service: tracker")));
        try {
            ConsumedResult result = call(adapter, "headers", Map.of());

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            String authorization = result.document().get("authorization").asString();
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"),
                    authorization);
            assertTrue(authorization.contains("/eu-west-1/tracker/aws4_request"), authorization);
            assertTrue(authorization.contains("x-tenant"), "static headers are signed: "
                    + authorization);
            assertTrue(result.document().get("signatureValid").asBoolean(),
                    "the upstream recomputes the signature: " + authorization);
            assertEquals("acme", result.document().get("tenant").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void awsSigV4ShouldSignWithTheModelSigningNameWhenServiceIsOmitted() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(HEADERS, SIGV4.formatted(SECRET, "")));
        try {
            ConsumedResult result = call(adapter, "headers", Map.of());

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            String authorization = result.document().get("authorization").asString();
            assertTrue(authorization.contains("/eu-west-1/issues/aws4_request"),
                    "the name of @aws.auth#sigv4: " + authorization);
            assertTrue(result.document().get("signatureValid").asBoolean(), authorization);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void awsSigV4ShouldFailOnARedirectRatherThanFollowIt() throws Exception {
        SmithyConsumedAdapter adapter = started(spec(GET_ISSUE,
                SIGV4.formatted(SECRET, "service: tracker")));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "moved"));

            assertEquals(302, result.status(), "not followed to /issues/elsewhere: "
                    + result.reason());
            assertFalse(result.isSuccessful());
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
    void constructorShouldRefuseAStreamingBlobOutput() throws Exception {
        SmithyClientSpec spec = spec("""
                download:
                  description: An attachment.
                  operation: DownloadAttachment
                  inputParameters:
                    id: { type: string, required: true, description: Id., wireName: attachmentId }
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'download'")
                && e.getMessage().contains("streaming output member 'data'"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnEventStreamOutput() throws Exception {
        SmithyClientSpec spec = spec("""
                watch:
                  description: Issue events.
                  operation: WatchIssues
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'watch'")
                && e.getMessage().contains("streaming output member 'events'"), e.getMessage());
    }

    @Test
    void callShouldSendTheTraceContextAndSetTheRpcAttributesOfTheClientSpan() throws Exception {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        TelemetryBootstrap.init(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        SmithyConsumedAdapter adapter = started(spec(HEADERS, ""));
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        Span parent = Span.wrap(SpanContext.create(traceId, "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault()));
        try (Scope scope = parent.makeCurrent()) {
            ConsumedResult result = call(adapter, "headers", Map.of());

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            assertTrue(result.document().get("traceparent").asBoolean(),
                    "the upstream saw no traceparent");
            List<SpanData> clients = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getKind() == SpanKind.CLIENT).toList();
            assertEquals(1, clients.size(), String.valueOf(clients));
            SpanData span = clients.get(0);
            assertEquals(traceId, span.getTraceId(), "the client span continues the trace");
            assertEquals("smithy", span.getAttributes().get(SmithyConsumedAdapter.ATTR_RPC_SYSTEM));
            assertEquals("example.tracker#Tracker",
                    span.getAttributes().get(SmithyConsumedAdapter.ATTR_RPC_SERVICE));
            assertEquals("EchoHeaders",
                    span.getAttributes().get(SmithyConsumedAdapter.ATTR_RPC_METHOD));
            assertEquals("aws.protocols#restJson1",
                    span.getAttributes().get(SmithyConsumedAdapter.ATTR_PROTOCOL));
        } finally {
            adapter.stop();
            TelemetryBootstrap.reset();
        }
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
    void constructorShouldRefuseARequiredMemberFilledOnlyByAnOptionalInput() throws Exception {
        SmithyClientSpec spec = spec("""
                create-issue:
                  description: Create an issue.
                  operation: CreateIssue
                  inputParameters:
                    project-id: { type: string, required: true, description: Project., wireName: projectId }
                    title: { type: string, description: Title left out when absent. }
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("[title]"), e.getMessage());
    }

    @Test
    void constructorShouldAcceptARequiredMemberFilledByAnInputWithADefault() throws Exception {
        SmithyConsumedAdapter adapter = new SmithyConsumedAdapter(null, spec("""
                create-issue:
                  description: Create an issue.
                  operation: CreateIssue
                  inputParameters:
                    project-id: { type: string, required: true, description: Project., wireName: projectId }
                    title: { type: string, default: Untitled, description: Title. }
                """, ""), Map.of());

        assertTrue(adapter.declaresOperation("create-issue"));
    }

    @Test
    void constructorShouldRefuseARequiredMemberOfANestedStructureThatNoInputFills()
            throws Exception {
        SmithyClientSpec spec = spec(CREATE_ISSUE + """
                    note: { type: string, description: Review note., wireName: review.note }
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmithyConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("[review.reviewer]"), e.getMessage());
    }

    @Test
    void constructorShouldAcceptANestedStructureWhoseRequiredMembersAreFilled()
            throws Exception {
        SmithyConsumedAdapter adapter = new SmithyConsumedAdapter(null, spec(CREATE_ISSUE + """
                    note: { type: string, description: Review note., wireName: review.note }
                    reviewer: { type: string, required: true, description: Reviewer., wireName: review.reviewer }
                """, ""), Map.of());

        assertTrue(adapter.declaresOperation("create-issue"));
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
