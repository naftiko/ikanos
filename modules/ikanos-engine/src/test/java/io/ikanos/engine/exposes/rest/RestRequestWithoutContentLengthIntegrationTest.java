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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;

/**
 * End-to-end tests of REST requests that carry no body and no {@code Content-Length} (issue #897).
 *
 * <p>With {@code Connection: close} and no {@code Content-Length}, as HTTPie Desktop sends every
 * request, Restlet gives the request an empty entity of unknown size that has no reader. The REST
 * adapter used to fail on it and leave the client waiting for an answer. Requests are written on
 * a raw socket so that no client library adds a {@code Content-Length} header.</p>
 */
class RestRequestWithoutContentLengthIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private Capability capability;
    private int port;

    @AfterEach
    void stopCapability() throws Exception {
        if (capability != null) {
            capability.stop();
        }
    }

    @Test
    void getWithoutContentLengthShouldBeAnswered() throws Exception {
        start("");

        Reply reply = send("GET /hello?name=Ada HTTP/1.1\r\nHost: localhost\r\n"
                + "Connection: close\r\n\r\n");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("Hello, Ada!", JSON.readTree(reply.body()).path("message").asString());
    }

    @Test
    void postWithoutBodyNorContentLengthShouldBeAnswered() throws Exception {
        start("");

        Reply reply = send("POST /hello?name=Ada HTTP/1.1\r\nHost: localhost\r\n"
                + "Connection: close\r\n\r\n");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("Hello, Ada!", JSON.readTree(reply.body()).path("message").asString());
    }

    @Test
    void getWithoutContentLengthShouldBeAnsweredOnASharedListener() throws Exception {
        start("path: \"/api\"");

        Reply reply = send("GET /api/hello?name=Ada HTTP/1.1\r\nHost: localhost\r\n"
                + "Connection: close\r\n\r\n");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("Hello, Ada!", JSON.readTree(reply.body()).path("message").asString());
    }

    // ── fixture ──

    /**
     * Starts a REST adapter that greets by name on GET and POST. With a {@code path}, an MCP
     * adapter shares its listener.
     */
    private void start(String restPath) throws Exception {
        port = TestUtils.freePorts(1)[0];
        String mcp = restPath.isEmpty() ? "" : """
                    - type: "mcp"
                      address: "localhost"
                      port: %d
                      namespace: "greeting-mcp"
                      path: "/mcp"
                      tools:
                        hello:
                          description: "Return a greeting."
                          ref: "greeting.hello"
                """.formatted(port);
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Request Without Content-Length Test"
                capability:
                  aggregates:
                    - namespace: "greeting"
                      flows:
                        hello:
                          description: "Builds a greeting."
                          inputParameters:
                            name:
                              type: "string"
                              description: "Name to greet"
                          outputParameters:
                            message:
                              type: "string"
                              value: "Hello, {{name}}!"
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "greeting-rest"
                      %s
                      resources:
                        hello:
                          path: "/hello"
                          operations:
                            hello:
                              method: "GET"
                              ref: "greeting.hello"
                              inputParameters:
                                name:
                                  in: "query"
                                  type: "string"
                                  description: "Name to greet"
                            hello-post:
                              method: "POST"
                              ref: "greeting.hello"
                              inputParameters:
                                name:
                                  in: "query"
                                  type: "string"
                                  description: "Name to greet"
                %s""".formatted(VersionHelper.getSchemaVersion(), port, restPath, mcp);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(spec);
        capability.start();
    }

    // ── raw HTTP ──

    private record Reply(int status, String body) {
    }

    private Reply send(String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            String response;
            try {
                response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("issue #897: the request got no answer within 10 s", e);
            }
            int status = Integer.parseInt(response.substring(9, 12));
            int bodyStart = response.indexOf("\r\n\r\n");
            String body = bodyStart >= 0 ? response.substring(bodyStart + 4) : "";
            return new Reply(status, body);
        }
    }
}
