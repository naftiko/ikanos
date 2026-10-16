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

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;

/**
 * A consumed API stand-in for the MCP trust tests (issue #483): answers {@code GET /orders/42}
 * and records the headers of every request it receives, so a test can assert that a refused call
 * never reached the upstream, or that no inbound credential was passed through.
 */
final class RecordingUpstream implements AutoCloseable {

    private final Server server;
    private final int port;
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    RecordingUpstream() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new Server(Protocol.HTTP, "127.0.0.1", port);
        server.setNext(new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                request.getHeaders().forEach(h -> headers.merge(h.getName(), h.getValue(),
                        (a, b) -> a + ", " + b));
                requests.add(headers);
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("{\"status\":\"shipped\"}", MediaType.APPLICATION_JSON);
            }
        });
        server.start();
    }

    String baseUri() {
        return "http://127.0.0.1:" + port;
    }

    /** @return the headers of each request received, in order. */
    List<Map<String, String>> requests() {
        return requests;
    }

    /**
     * The {@code consumes} block (namespace {@code shop}, operation {@code get-order}) pointing at
     * this stub, indented under {@code capability}. Pair it with {@link #orderStatusToolYaml()}.
     */
    String consumesYaml() {
        return """
                  consumes:
                    - type: "http"
                      namespace: "shop"
                      baseUri: "%s"
                      resources:
                        order:
                          path: "/orders/42"
                          operations:
                            get-order:
                              method: "GET"
                """.formatted(baseUri());
    }

    /** An MCP {@code tools} block with {@code order-status} calling {@code shop.get-order}. */
    static String orderStatusToolYaml() {
        return """
                      tools:
                        order-status:
                          description: "Status of order 42"
                          call: "shop.get-order"
                          outputParameters:
                            - type: "string"
                              mapping: "$.status"
                """;
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }
}
