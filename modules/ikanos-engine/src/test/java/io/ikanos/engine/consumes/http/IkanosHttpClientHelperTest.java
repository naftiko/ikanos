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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.restlet.Client;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.engine.Engine;
import org.restlet.representation.InputRepresentation;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.Router;

/**
 * The consumed HTTP client helper on Restlet's Netty connector: a 401 keeps its body (#925), an
 * entity of known size is sent with Content-Length (#954), and {@link
 * IkanosHttpClientHelper#NO_REDIRECTS} returns a redirect as is (#1002).
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class IkanosHttpClientHelperTest {

    private static Component upstream;
    private static int port;

    @BeforeAll
    static void startUpstream() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, port);
        Router router = new Router();
        router.attach("/unauthorized", new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
                response.setEntity("{\"error\":\"token expired\"}", MediaType.APPLICATION_JSON);
            }
        });
        router.attach("/length", new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                String length = request.getHeaders().getFirstValue("Content-Length", true);
                String encoding = request.getHeaders().getFirstValue("Transfer-Encoding", true);
                // Read the whole body before answering: an answer sent while the client is still
                // writing a chunked body can reach it as a connector error instead.
                try {
                    request.getEntity().exhaust();
                } catch (java.io.IOException e) {
                    response.setStatus(Status.SERVER_ERROR_INTERNAL, e);
                    return;
                }
                response.setEntity("length=" + length + ";encoding=" + encoding,
                        MediaType.TEXT_PLAIN);
            }
        });
        router.attach("/redirect", new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.redirectTemporary("http://localhost:" + port + "/target");
            }
        });
        router.attach("/target", new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setEntity("target", MediaType.TEXT_PLAIN);
            }
        });
        upstream.getDefaultHost().attach(router);
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() throws Exception {
        upstream.stop();
    }

    private static Client newClient() throws Exception {
        IkanosHttpClientHelper.ensureRegistered();
        Client client = new Client(new Context(), List.of(Protocol.HTTP, Protocol.HTTPS),
                HttpClientAdapter.HTTP_CLIENT_HELPER_CLASS_NAME);
        client.start();
        return client;
    }

    private static String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ensureRegisteredShouldRegisterTheHelperOnce() {
        IkanosHttpClientHelper.ensureRegistered();
        IkanosHttpClientHelper.ensureRegistered();

        assertEquals(1, Engine.getInstance().getRegisteredClients().stream()
                .filter(h -> h.getClass() == IkanosHttpClientHelper.class).count());
        Client client = new Client(new Context(), List.of(Protocol.HTTP, Protocol.HTTPS),
                HttpClientAdapter.HTTP_CLIENT_HELPER_CLASS_NAME);
        assertTrue(client.isAvailable(), "Restlet should find the registered helper");
    }

    @Test
    void unauthorizedWithoutChallengeShouldReachTheCallerWithItsBody() throws Exception {
        Client client = newClient();
        try {
            Response response = client.handle(new Request(Method.GET, url("/unauthorized")));

            assertEquals(401, response.getStatus().getCode(), "#925: no 1002 for a bare 401");
            assertTrue(response.getEntityAsText().contains("token expired"));
        } finally {
            client.stop();
        }
    }

    @Test
    void entityOfKnownSizeShouldBeSentWithContentLength() throws Exception {
        Client client = newClient();
        try {
            Request request = new Request(Method.POST, url("/length"));
            request.setEntity(new StringRepresentation("hello"));

            assertEquals("length=5;encoding=null",
                    client.handle(request).getEntityAsText(), "#954");
        } finally {
            client.stop();
        }
    }

    @Test
    void entityOfUnknownSizeShouldBeSentChunked() throws Exception {
        Client client = newClient();
        try {
            Request request = new Request(Method.POST, url("/length"));
            InputRepresentation entity = new InputRepresentation(
                    new java.io.ByteArrayInputStream("hello".getBytes()));
            request.setEntity(entity);

            Response response = client.handle(request);

            assertEquals(200, response.getStatus().getCode(), String.valueOf(response.getStatus()));
            assertEquals("length=null;encoding=chunked", response.getEntityAsText());
        } finally {
            client.stop();
        }
    }

    @Test
    void redirectShouldBeFollowedByDefault() throws Exception {
        Client client = newClient();
        try {
            Response response = client.handle(new Request(Method.GET, url("/redirect")));

            assertEquals(200, response.getStatus().getCode());
            assertEquals("target", response.getEntityAsText());
        } finally {
            client.stop();
        }
    }

    @Test
    void noRedirectsShouldReturnTheRedirectAsIs() throws Exception {
        Client client = newClient();
        try {
            Request request = new Request(Method.GET, url("/redirect"));
            request.getAttributes().put(IkanosHttpClientHelper.NO_REDIRECTS, Boolean.TRUE);
            Response response = client.handle(request);

            assertEquals(307, response.getStatus().getCode(), "#1002");
            assertEquals(url("/target"), response.getLocationRef().toString());

            Response next = client.handle(new Request(Method.GET, url("/redirect")));
            assertEquals(200, next.getStatus().getCode(),
                    "the flag applies to its own request only");
        } finally {
            client.stop();
        }
    }
}
