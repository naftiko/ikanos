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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.utils.TestUtils;

/**
 * End-to-end tests over a real HTTP connector: an exception that escapes an adapter's handler
 * chain gets a {@code 500}, on a dedicated listener and on a shared one (issue #897). Before, the
 * connector logged it and never committed the response, so the client waited until it gave up.
 */
class UncaughtExceptionIntegrationTest {

    private static final String DETAIL = "detail that must stay in the log";

    private ServerAdapter dedicated;
    private ServerHost host;

    @AfterEach
    void stop() throws Exception {
        if (dedicated != null) {
            dedicated.stop();
        }
        if (host != null) {
            host.stop();
        }
    }

    @Test
    void dedicatedListenerShouldAnswer500WhenTheHandlerThrows() throws Exception {
        int port = TestUtils.freePorts(1)[0];
        dedicated = new FailingAdapter(port, null);
        dedicated.start();

        Reply reply = get(port, "/anything");

        assertEquals(500, reply.status(), reply.body());
        assertFalse(reply.body().contains(DETAIL), reply.body());
    }

    @Test
    void sharedListenerShouldAnswer500WhenTheHandlerThrows() throws Exception {
        int port = TestUtils.freePorts(1)[0];
        host = ServerHost.build(List.of(new FailingAdapter(port, "/a"),
                new FailingAdapter(port, "/b")), Map.of());
        host.start();

        Reply reply = get(port, "/a/anything");

        assertEquals(500, reply.status(), reply.body());
        assertFalse(reply.body().contains(DETAIL), reply.body());
    }

    /**
     * A handler that fails before reading a POST body must not break the keep-alive connection:
     * the body is discarded like a rejected one, so the next request on the connection is served
     * (see {@link RejectedRequestBodies}, issue #878).
     */
    @Test
    void connectionShouldStayReusableWhenTheHandlerThrowsBeforeABodyArrives() throws Exception {
        int port = TestUtils.freePorts(1)[0];
        dedicated = new FailingAdapter(port, null);
        dedicated.start();

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            String body = "{\"name\":\"Ada\"}";

            out.write(("POST /anything HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length() + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(100);
            out.write(body.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals(500, readStatusCode(in));

            out.write(("GET " + FailingAdapter.OK_PATH + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals(200, readStatusCode(in),
                    "the next request on the same connection should be served after a 500");
        }
    }

    /** An adapter whose handler chain fails on every request but {@link #OK_PATH}. */
    private static final class FailingAdapter extends ServerAdapter {

        static final String OK_PATH = "/ok";

        FailingAdapter(int port, String path) {
            super(null, spec(port, path));
            initServer("localhost", port, new Restlet() {
                @Override
                public void handle(Request request, Response response) {
                    if (OK_PATH.equals(request.getResourceRef().getPath())) {
                        response.setEntity("ok", MediaType.TEXT_PLAIN);
                        return;
                    }
                    throw new IllegalStateException(DETAIL);
                }
            });
        }

        private static RestServerSpec spec(int port, String path) {
            RestServerSpec spec = new RestServerSpec("localhost", port, "failing");
            spec.setPath(path);
            return spec;
        }
    }

    // ── raw HTTP ──

    private record Reply(int status, String body) {
    }

    /**
     * Reads one HTTP/1.1 response with a {@code Content-Length} body and returns its status code,
     * or -1 if the server closed the connection before sending a status line.
     */
    private static int readStatusCode(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b < 0) {
                return -1;
            }
            head.append((char) b);
        }
        int contentLength = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        in.readNBytes(contentLength);
        return Integer.parseInt(head.substring(9, 12));
    }

    private static Reply get(int port, String path) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            String response;
            try {
                response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("issue #897: the failed request got no answer", e);
            }
            int status = Integer.parseInt(response.substring(9, 12));
            int bodyStart = response.indexOf("\r\n\r\n");
            String body = bodyStart >= 0 ? response.substring(bodyStart + 4) : "";
            return new Reply(status, body);
        }
    }
}
