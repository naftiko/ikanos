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
                router.attach("/documents", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
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
    void signsAGetWithAPathAndAQuery() throws Exception {
        start(auth(null));

        ConsumedResult result =
                call("get-object", Map.of("key", "2026 Q3.pdf", "prefix", "a/b c"));

        assertTrue(result.isSuccessful(), "status " + result.status());
        assertEquals(AwsSigV4Helper.EMPTY_PAYLOAD_HASH,
                received.get().getHeaders().getFirstValue("x-amz-content-sha256", true));
        assertSignatureMatches("s3", false);
    }

    @Test
    void signsTheHashOfAJsonBody() throws Exception {
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
    void signsForAnotherServiceWithoutThePayloadHeader() throws Exception {
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
    void signsAForwardedUploadWithAnUnsignedPayload() throws Exception {
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
    void refusesAnAmzTrustedHeaderOnAForwardToAwsSigV4() throws Exception {
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
    void forwardsAndSignsAnotherTrustedHeader() throws Exception {
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
    void acceptsAnAmzTrustedHeaderWithoutAwsSigV4() throws Exception {
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

    /** A consumed MCP adapter signs its JSON-RPC POST, payload hash included, like HTTP. */
    @Test
    void signsACallToAConsumedMcpAdapter() throws Exception {
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
    void refusesAwsSigV4OnAnExposedAdapter() throws Exception {
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
