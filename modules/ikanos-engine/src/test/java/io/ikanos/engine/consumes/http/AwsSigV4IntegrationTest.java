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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Application;
import org.restlet.Client;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Header;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.restlet.util.Series;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.mcp.McpClientException;
import io.ikanos.engine.consumes.mcp.McpStreamableHttpTransport;
import io.ikanos.spec.consumes.http.AwsSigV4AuthenticationSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;

/**
 * Calls an S3-like upstream with {@code awsSigV4} authentication through a capability, and checks
 * on the upstream that the signature matches the request it received (#954).
 */
public class AwsSigV4IntegrationTest {

    private static final String KEY_ID = "AKIAIOSFODNN7EXAMPLE";

    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private final AtomicReference<Request> received = new AtomicReference<>();

    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    /** The request a redirect led to, if the client followed it. */
    private final AtomicReference<Request> redirected = new AtomicReference<>();

    private Component upstream;

    private int upstreamPort;

    private Capability capability;

    @BeforeEach
    void startUpstream() throws Exception {
        upstreamPort = findFreePort();
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, upstreamPort);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.setDefaultMatchingMode(Template.MODE_STARTS_WITH);
                router.attach("/mcp", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            receivedBody.set(request.getEntity().getText());
                        } catch (Exception e) {
                            response.setStatus(Status.SERVER_ERROR_INTERNAL, e);
                            return;
                        }
                        received.set(request);
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                                + "{\"tools\":[]}}", MediaType.APPLICATION_JSON);
                    }
                });
                router.attach("/elsewhere", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        redirected.set(request);
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
                    }
                });
                router.attach("/documents", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        if (request.getResourceRef().getPath().endsWith("/moved")) {
                            received.set(request);
                            response.redirectTemporary(
                                    "http://localhost:" + upstreamPort + "/elsewhere/moved");
                            return;
                        }
                        try {
                            receivedBody.set(request.isEntityAvailable()
                                    ? request.getEntity().getText()
                                    : "");
                        } catch (Exception e) {
                            response.setStatus(Status.SERVER_ERROR_INTERNAL, e);
                            return;
                        }
                        received.set(request);
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"ok\":true}", MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        upstream.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        upstream.stop();
    }

    private int restPort;

    /**
     * Starts a capability whose consumed adapter {@code store} signs with {@code authentication}.
     * It exposes a REST resource forwarding {@code /files/...} to it, on {@link #restPort}.
     */
    private Capability start(String authentication) throws Exception {
        restPort = findFreePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                      resources:
                        - path: "/files/{{path}}"
                          forward:
                            targetNamespace: "store"
                  consumes:
                    - type: "http"
                      namespace: "store"
                      baseUri: "http://localhost:%d/documents"
                      authentication:
                %s
                      resources:
                        object:
                          path: "/{{key}}"
                          operations:
                            get-object:
                              method: "GET"
                              inputParameters:
                                prefix:
                                  in: "query"
                            put-object:
                              method: "PUT"
                              body:
                                type: "json"
                                data:
                                  note: "{{note}}"
                """.formatted(VersionHelper.getSchemaVersion(), restPort, upstreamPort,
                        authentication);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
        return capability;
    }

    private static String auth(String service) {
        return """
                        type: "awsSigV4"
                        accessKeyId: "%s"
                        secretAccessKey: "%s"
                        region: "us-east-1"
                """.formatted(KEY_ID, SECRET)
                + (service == null ? "" : "        service: \"" + service + "\"\n");
    }

    private HttpClientAdapter store() {
        for (ClientAdapter adapter : capability.getClientAdapters()) {
            if (adapter instanceof HttpClientAdapter http) {
                return http;
            }
        }
        throw new IllegalStateException("No HTTP client adapter");
    }

    private ConsumedResult call(String operation, Map<String, Object> parameters)
            throws Exception {
        return store().prepare(operation, new HashMap<>(parameters)).invoke();
    }

    /**
     * Recomputes on the upstream the signature of the request it received, from its method, path,
     * query, the headers named in SignedHeaders and the X-Amz-Content-Sha256 header, and checks it
     * equals the one sent.
     */
    private void assertSignatureMatches(String service, boolean doubleEncode) {
        Request seen = received.get();
        assertNotNull(seen, "The upstream should have been called");
        Series<Header> headers = seen.getHeaders();
        String authorization = headers.getFirstValue("Authorization", true);
        assertNotNull(authorization, "The request should carry an Authorization header");
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=" + KEY_ID + "/"),
                authorization);
        assertTrue(authorization.contains("/us-east-1/" + service + "/aws4_request,"),
                authorization);

        String signedHeaders = authorization.replaceAll(".*SignedHeaders=([^,]+),.*", "$1");
        String signature = authorization.replaceAll(".*Signature=([0-9a-f]+)$", "$1");
        Set<String> signed = Set.of(signedHeaders.split(";"));
        assertTrue(signed.containsAll(Set.of("host", "x-amz-date")), signedHeaders);

        Series<Header> signedSeen = new Series<>(Header.class);
        for (Header header : headers) {
            if (signed.contains(header.getName().toLowerCase())) {
                signedSeen.add(header);
            }
        }
        Map<String, String> canonicalHeaders = AwsSigV4Helper.getCanonicalHeaders(signedSeen);
        assertEquals(signedHeaders, String.join(";", canonicalHeaders.keySet()),
                "Every signed header should have been received");

        String payloadHash = headers.getFirstValue("x-amz-content-sha256", true);
        if (payloadHash == null) {
            payloadHash = sha256Hex(receivedBody.get());
        }
        String amzDate = headers.getFirstValue("x-amz-date", true);
        String scope = amzDate.substring(0, 8) + "/us-east-1/" + service + "/aws4_request";
        String canonicalRequest = AwsSigV4Helper.getCanonicalRequest(seen.getMethod().getName(),
                seen.getResourceRef(), doubleEncode, canonicalHeaders, signedHeaders,
                payloadHash);
        assertEquals(signature,
                AwsSigV4Helper.getSignature(SECRET.toCharArray(), amzDate.substring(0, 8),
                        "us-east-1", service,
                        AwsSigV4Helper.getStringToSign(amzDate, scope, canonicalRequest)),
                "The signature should match the request the upstream received");
    }

    @Test
    void invokeShouldSignAGetWhenItHasAPathAndAQuery() throws Exception {
        start(auth(null));

        ConsumedResult result =
                call("get-object", Map.of("key", "2026 Q3.pdf", "prefix", "a/b c"));

        assertTrue(result.isSuccessful(), "status " + result.status());
        assertEquals(AwsSigV4Helper.EMPTY_PAYLOAD_HASH,
                received.get().getHeaders().getFirstValue("x-amz-content-sha256", true));
        assertSignatureMatches("s3", false);
    }

    @Test
    void invokeShouldSignTheHashOfTheBodyWhenItIsJson() throws Exception {
        start(auth("s3"));

        ConsumedResult result = call("put-object",
                Map.of("key", "notes.json", "note", "He said \"hi\"\nthen left"));

        assertTrue(result.isSuccessful(), "status " + result.status());
        assertEquals(sha256Hex(receivedBody.get()),
                received.get().getHeaders().getFirstValue("x-amz-content-sha256", true),
                "The signed payload hash should be the hash of the body received");
        assertEquals(String.valueOf(receivedBody.get().getBytes(StandardCharsets.UTF_8).length),
                received.get().getHeaders().getFirstValue("Content-Length", true),
                "S3 and MinIO refuse an unsized body that isn't aws-chunked");
        assertSignatureMatches("s3", false);
    }

    @Test
    void invokeShouldSignWithoutThePayloadHeaderWhenTheServiceIsNotS3() throws Exception {
        start(auth("execute-api"));

        ConsumedResult result = call("put-object", Map.of("key", "item", "note", "x"));

        assertTrue(result.isSuccessful(), "status " + result.status());
        assertEquals(null,
                received.get().getHeaders().getFirstValue("x-amz-content-sha256", true),
                "Only S3 is sent X-Amz-Content-Sha256");
        assertSignatureMatches("execute-api", true);
    }

    /** A body passed through by forward can't be read twice: S3 is sent UNSIGNED-PAYLOAD. */
    @Test
    void forwardShouldSignAnUploadWithAnUnsignedPayloadWhenTheTargetIsS3() throws Exception {
        start(auth(null));

        // A caller sending a sized body, as S3 clients do (Restlet's own client sends it chunked)
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest
                .newBuilder(URI.create(
                        "http://localhost:" + restPort + "/files/uploads/report.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("Welcome to Amazon S3."))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        assertEquals("Welcome to Amazon S3.", receivedBody.get());
        assertEquals("21", received.get().getHeaders().getFirstValue("Content-Length", true),
                "S3 and MinIO refuse an unsized body that isn't aws-chunked");
        assertEquals(null,
                received.get().getHeaders().getFirstValue("Transfer-Encoding", true));
        assertEquals("/documents/uploads/report.txt", received.get().getResourceRef().getPath());
        assertEquals(AwsSigV4Helper.UNSIGNED_PAYLOAD,
                received.get().getHeaders().getFirstValue("x-amz-content-sha256", true));
        assertSignatureMatches("s3", false);
    }

    /**
     * AWS reads x-amz-* headers as part of the operation (x-amz-copy-source, x-amz-acl...), and a
     * trusted one is signed with the capability's key: a forward to an awsSigV4 adapter refuses
     * them at load, whatever their case.
     */
    @Test
    void capabilityShouldRefuseAnAmzTrustedHeaderWhenTheForwardTargetSignsWithAwsSigV4()
            throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml()
                .readValue(forwardYaml(auth(null), "X-Amz-Copy-Source"), IkanosSpec.class);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new Capability(spec));
        assertTrue(error.getMessage().contains("Trusted header 'X-Amz-Copy-Source'"),
                error.getMessage());
        assertTrue(error.getMessage().contains("awsSigV4"), error.getMessage());
    }

    /** Another trusted header is still forwarded, and signed. */
    @Test
    void forwardShouldSignAnotherTrustedHeaderWhenTheTargetSignsWithAwsSigV4() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml()
                .readValue(forwardYaml(auth(null), "X-Request-Note"), IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();

        Client client = new Client(new Context(), Protocol.HTTP);
        client.start();
        try {
            Request request = new Request(Method.GET,
                    "http://localhost:" + restPort + "/files/a.txt");
            request.getHeaders().add("X-Request-Note", "quarterly");
            Response response = client.handle(request);
            assertEquals(Status.SUCCESS_OK, response.getStatus());
        } finally {
            client.stop();
        }

        assertEquals("quarterly",
                received.get().getHeaders().getFirstValue("X-Request-Note", true));
        assertTrue(received.get().getHeaders().getFirstValue("Authorization", true)
                .contains("x-request-note"), "The trusted header should be signed");
        assertSignatureMatches("s3", false);
    }

    /** An x-amz-* trusted header stays allowed when the target doesn't sign with awsSigV4. */
    @Test
    void capabilityShouldAcceptAnAmzTrustedHeaderWhenTheTargetDoesNotSignWithAwsSigV4()
            throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml()
                .readValue(forwardYaml(null, "x-amz-meta-owner"), IkanosSpec.class);

        capability = new Capability(spec);
        assertNotNull(capability);
    }

    /** A capability forwarding {@code /files/...} to {@code store}, trusting one header. */
    private String forwardYaml(String authentication, String trustedHeader) throws Exception {
        restPort = findFreePort();
        return """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                      resources:
                        - path: "/files/{{path}}"
                          forward:
                            targetNamespace: "store"
                            trustedHeaders:
                              - "%s"
                  consumes:
                    - type: "http"
                      namespace: "store"
                      baseUri: "http://localhost:%d/documents"
                """.formatted(VersionHelper.getSchemaVersion(), restPort, trustedHeader,
                        upstreamPort)
                + (authentication == null ? "" : "      authentication:\n" + authentication);
    }

    /**
     * S3 reads a query name such as acl, tagging or versionId as part of the operation, and the
     * caller's query would be signed with the operator's credentials: a forward to an awsSigV4
     * target refuses a caller query with 400, and nothing reaches the upstream.
     */
    @ParameterizedTest
    @ValueSource(strings = {"acl", "tagging", "versionId=3", "prefix=a", "acl&x-id=PutObjectAcl"})
    void forwardShouldRefuseACallerQueryWhenTheTargetSignsWithAwsSigV4(String query)
            throws Exception {
        start(auth(null));

        HttpResponse<String> response = forward("/files/report.txt?" + query);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("awsSigV4"), response.body());
        assertNull(received.get(), "The upstream should not have been called");
    }

    /** A forwarded call without a query is still signed and sent. */
    @Test
    void forwardShouldSignACallWithoutAQueryWhenTheTargetSignsWithAwsSigV4() throws Exception {
        start(auth(null));

        HttpResponse<String> response = forward("/files/report.txt");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("/documents/report.txt", received.get().getResourceRef().getPath());
        assertNull(received.get().getResourceRef().getQuery());
        assertSignatureMatches("s3", false);
    }

    /**
     * The query of the base URI and the adapter's own query input parameters are the operator's,
     * not the caller's: they still go out, signed.
     */
    @Test
    void forwardShouldSignTheAdapterQueryWhenTheTargetSignsWithAwsSigV4() throws Exception {
        startSignedForward("", "?list-type=2", """
                      inputParameters:
                        - name: "max-keys"
                          in: "query"
                          value: "10"
                """);

        HttpResponse<String> response = forward("/files/reports");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("/documents/reports", received.get().getResourceRef().getPath());
        assertEquals("list-type=2&max-keys=10", received.get().getResourceRef().getQuery());
        assertSignatureMatches("s3", false);
    }

    /**
     * The exposed API key, sent in the query, never reaches the upstream, so it is not a caller
     * query for the target; any other parameter beside it still is.
     */
    @Test
    void forwardShouldAcceptOnlyTheExposedApiKeyInTheQueryWhenTheTargetSignsWithAwsSigV4()
            throws Exception {
        startSignedForward("""
                      authentication:
                        type: "apikey"
                        key: "api_key"
                        value: "ikanos-secret"
                        placement: "query"
                """, "", "");

        HttpResponse<String> refused = forward("/files/report.txt?api_key=ikanos-secret&acl");
        assertEquals(400, refused.statusCode(), refused.body());
        assertNull(received.get(), "The upstream should not have been called");

        HttpResponse<String> response = forward("/files/report.txt?api_key=ikanos-secret");
        assertEquals(200, response.statusCode(), response.body());
        assertNull(received.get().getResourceRef().getQuery());
        assertSignatureMatches("s3", false);
    }

    /** Sends a GET to the exposed adapter on {@link #restPort}. */
    private HttpResponse<String> forward(String pathAndQuery) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest
                .newBuilder(URI.create("http://localhost:" + restPort + pathAndQuery)).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Starts a capability forwarding {@code /files/...} to {@code store}, which signs for S3.
     *
     * @param exposedAuthentication the exposed adapter's authentication block, or empty
     * @param baseUriQuery a query appended to the base URI, such as {@code ?list-type=2}, or empty
     * @param consumedExtras YAML added to the consumed adapter, or empty
     */
    private void startSignedForward(String exposedAuthentication, String baseUriQuery,
            String consumedExtras) throws Exception {
        restPort = findFreePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                %s
                      resources:
                        - path: "/files/{{path}}"
                          forward:
                            targetNamespace: "store"
                  consumes:
                    - type: "http"
                      namespace: "store"
                      baseUri: "http://localhost:%d/documents%s"
                %s
                      authentication:
                %s
                """.formatted(VersionHelper.getSchemaVersion(), restPort, exposedAuthentication,
                        upstreamPort, baseUriQuery, consumedExtras, auth(null));
        capability = new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        capability.start();
    }

    /** A consumed MCP adapter signs its JSON-RPC POST, payload hash included, like HTTP. */
    @Test
    void mcpTransportShouldSignTheCallWhenTheConsumedMcpAdapterUsesAwsSigV4() throws Exception {
        AwsSigV4AuthenticationSpec auth = new AwsSigV4AuthenticationSpec();
        auth.setAccessKeyId(KEY_ID);
        auth.setSecretAccessKey(SECRET.toCharArray());
        auth.setRegion("us-east-1");
        auth.setService("execute-api");
        Client client = new Client(new Context(), Protocol.HTTP);
        client.start();
        try {
            McpStreamableHttpTransport transport = new McpStreamableHttpTransport(client,
                    "http://localhost:" + upstreamPort + "/mcp", auth);

            JsonNode result = transport.call("tools/list", null,
                    IkanosMappers.json().createObjectNode(), Map.of());

            assertTrue(result.path("tools").isArray(), String.valueOf(result));
        } finally {
            client.stop();
        }

        assertTrue(receivedBody.get().contains("\"tools/list\""), receivedBody.get());
        assertSignatureMatches("execute-api", true);
    }

    @Test
    void callShouldNotFollowARedirectWhenTheAdapterSignsWithAwsSigV4() throws Exception {
        // The signature covers the host and path it was computed for, and the HTTP client would
        // re-send the request to the new location without signing it again.
        start(auth("s3"));

        ConsumedResult result = call("get-object", Map.of("key", "moved"));

        assertNull(redirected.get(), () -> "The redirect was followed, with Authorization "
                + redirected.get().getHeaders().getFirstValue("Authorization", true));
        assertEquals(307, result.status());
        assertSignatureMatches("s3", false);
    }

    /**
     * A consumed MCP adapter gets the same client context as an HTTP one: a signed JSON-RPC POST
     * answered with a redirect must not be re-sent, X-Amz-* headers and session token included, to
     * the host the redirect names. The client streams the POST body, so it could only replay a
     * {@code 307} if a later client made that body reproducible; it already follows a {@code 302}
     * or {@code 303} as a GET without one, with every header of the signed call.
     */
    @ParameterizedTest
    @ValueSource(ints = {302, 303, 307})
    void mcpCallShouldNotFollowARedirectWhenTheAdapterSignsWithAwsSigV4(int status)
            throws Exception {
        int otherPort = findFreePort();
        AtomicReference<Request> otherHost = new AtomicReference<>();
        Component other = new Component();
        other.getServers().add(Protocol.HTTP, otherPort);
        other.getDefaultHost().attachDefault(new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                otherHost.set(request);
                response.setEntity("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],"
                        + "\"resultType\":\"complete\"}}", MediaType.APPLICATION_JSON);
            }
        });
        int firstPort = findFreePort();
        AtomicReference<Request> firstHost = new AtomicReference<>();
        Component first = new Component();
        first.getServers().add(Protocol.HTTP, firstPort);
        first.getDefaultHost().attachDefault(new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                firstHost.set(request);
                response.setLocationRef("http://127.0.0.1:" + otherPort + "/mcp");
                response.setStatus(Status.valueOf(status));
            }
        });
        other.start();
        first.start();
        try {
            String yaml = """
                    ikanos: "%s"
                    capability:
                      consumes:
                        - type: "mcp"
                          namespace: "tools"
                          description: "A signed MCP upstream that redirects"
                          endpoint: "http://localhost:%d/mcp"
                          discovery: "off"
                          authentication:
                    %s
                            sessionToken: "FwoGZXIvYXdzEXAMPLETOKEN"
                          tools:
                            echo:
                              description: "Echoes"
                              hints: { readOnly: true }
                      exposes:
                        - type: "mcp"
                          transport: "http"
                          address: "localhost"
                          port: 0
                          namespace: "relay"
                          tools:
                            relay:
                              description: "Relays to the signed upstream"
                              call: "tools.echo"
                    """.formatted(VersionHelper.getSchemaVersion(), firstPort,
                            auth("execute-api").stripTrailing());
            capability = new Capability(
                    IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
            capability.start();
            ClientAdapter tools = capability.getClientAdapters().get(0);

            McpClientException error = null;
            try {
                tools.prepare("echo", new HashMap<>()).invoke();
            } catch (McpClientException e) {
                error = e;
            }

            assertNull(otherHost.get(), () -> "The redirect was followed, with"
                    + " X-Amz-Security-Token " + otherHost.get().getHeaders().getFirstValue("X-Amz-Security-Token", true));
            assertNotNull(error, "The call should fail on the redirect");
            assertNotNull(firstHost.get(), "The signed call should reach the first host");
            assertNotNull(firstHost.get().getHeaders().getFirstValue("Authorization", true),
                    "The first host should receive the signed call");
            assertTrue(error.getMessage().contains(String.valueOf(status)), error.getMessage());
        } finally {
            first.stop();
            other.stop();
        }
    }

    @Test
    void capabilityShouldRefuseAwsSigV4WhenAnExposedAdapterDeclaresIt() throws Exception {
        int restPort = findFreePort();
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "proxy"
                      authentication:
                %s
                      resources:
                        - path: "/files/{{path}}"
                          forward:
                            targetNamespace: "store"
                  consumes:
                    - type: "http"
                      namespace: "store"
                      baseUri: "http://localhost:%d/documents"
                """.formatted(VersionHelper.getSchemaVersion(), restPort, auth(null),
                        upstreamPort);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new Capability(spec));
        assertTrue(error.getMessage().contains("awsSigV4 authentication of the rest adapter"
                + " 'proxy'"), error.getMessage());
    }

    @Test
    void capabilityShouldRefuseAwsSigV4WhenAStdioMcpAdapterDeclaresIt() throws Exception {
        // stdio builds no authentication chain, so the refusal must not depend on it
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "mcp"
                      transport: "stdio"
                      namespace: "tools"
                      authentication:
                %s
                      tools:
                        - name: "hello"
                          description: "Says hello"
                          outputParameters:
                            - name: "message"
                              type: "string"
                              value: "Hello"
                  consumes:
                    - type: "http"
                      namespace: "store"
                      baseUri: "http://localhost:%d/documents"
                """.formatted(VersionHelper.getSchemaVersion(), auth(null), upstreamPort);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new Capability(spec));
        assertEquals("awsSigV4 authentication of the mcp adapter 'tools' is not supported: it"
                + " only signs the calls to a consumed adapter.", error.getMessage());
    }

    private static String sha256Hex(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
