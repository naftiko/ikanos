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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.CacheDirective;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import io.ikanos.engine.exposes.mcp.ServerCardBuilder.ServerCard;

/**
 * Unit tests for {@link ServerCardRestlet} and {@link ServerCardRoute}: the card's status codes,
 * caching and CORS headers, and which requests reach the card.
 */
class ServerCardRestletTest {

    private static final ServerCard CARD = new ServerCard("{\"name\":\"io.example/tools\"}",
            "abc123");

    private static Response send(Restlet restlet, Method method, String path) {
        Request request = new Request(method, "http://localhost:3001" + path);
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }

    private static ServerCardRestlet publicCard() {
        return new ServerCardRestlet(new Context(), CARD, false, null);
    }

    @Test
    void getShouldServeTheCardWithItsMediaTypeAndEtag() throws Exception {
        Response response = send(publicCard(), Method.GET, "/mcp/server-card");
        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals(ServerCardBuilder.MEDIA_TYPE,
                response.getEntity().getMediaType().getName());
        assertEquals(new Tag("abc123", false), response.getEntity().getTag());
        assertEquals(CARD.json(), response.getEntity().getText());
    }

    @Test
    void getShouldAllowAnyOriginAndExposeTheEtag() {
        Response response = send(publicCard(), Method.GET, "/mcp/server-card");
        assertEquals("*", response.getAccessControlAllowOrigin());
        assertTrue(response.getAccessControlExposeHeaders().contains("ETag"));
    }

    @Test
    void getShouldBePubliclyCacheableForAnHour() {
        List<CacheDirective> cache = send(publicCard(), Method.GET, "/mcp/server-card")
                .getCacheDirectives();
        assertTrue(cache.contains(CacheDirective.publicInfo()), cache.toString());
        assertTrue(cache.contains(CacheDirective.maxAge(3600)), cache.toString());
    }

    @Test
    void getShouldBePrivatelyCacheableWhenTheCardIsAuthenticated() {
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(), CARD, true,
                "Authorization");
        List<CacheDirective> cache = send(restlet, Method.GET, "/mcp/server-card")
                .getCacheDirectives();
        assertTrue(cache.contains(CacheDirective.privateInfo()), cache.toString());
    }

    @Test
    void getShouldAnswerNotModifiedWhenIfNoneMatchNamesTheCard() {
        Request request = new Request(Method.GET, "http://localhost:3001/mcp/server-card");
        request.getConditions().setNoneMatch(List.of(new Tag("abc123", false)));
        Response response = new Response(request);
        publicCard().handle(request, response);
        assertEquals(Status.REDIRECTION_NOT_MODIFIED, response.getStatus());
    }

    @Test
    void getShouldServeTheCardWhenIfNoneMatchNamesAnotherVersion() {
        Request request = new Request(Method.GET, "http://localhost:3001/mcp/server-card");
        request.getConditions().setNoneMatch(List.of(new Tag("old", false)));
        Response response = new Response(request);
        publicCard().handle(request, response);
        assertEquals(Status.SUCCESS_OK, response.getStatus());
    }

    @Test
    void optionsShouldAnswerThePreflight() {
        Response response = send(publicCard(), Method.OPTIONS, "/mcp/server-card");
        assertEquals(Status.SUCCESS_NO_CONTENT, response.getStatus());
        assertEquals("*", response.getAccessControlAllowOrigin());
        assertTrue(response.getAccessControlAllowMethods().contains(Method.GET));
        assertTrue(response.getAccessControlAllowHeaders().contains("If-None-Match"));
    }

    @Test
    void optionsShouldAllowTheCredentialsHeaderWhenTheCardIsAuthenticated() {
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(), CARD, true,
                "X-Api-Key");
        Response response = send(restlet, Method.OPTIONS, "/mcp/server-card");
        assertTrue(response.getAccessControlAllowHeaders().contains("X-Api-Key"));
    }

    @Test
    void optionsShouldNotAllowACredentialsHeaderOnAPublicCard() {
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(), CARD, false,
                "Authorization");
        Response response = send(restlet, Method.OPTIONS, "/mcp/server-card");
        assertFalse(response.getAccessControlAllowHeaders().contains("Authorization"));
    }

    @Test
    void postShouldBeRejectedAsMethodNotAllowed() {
        Response response = send(publicCard(), Method.POST, "/mcp/server-card");
        assertEquals(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED, response.getStatus());
        assertTrue(response.getAllowedMethods().contains(Method.GET));
    }

    /** Records whether it handled a request. */
    private static final class Probe extends Restlet {
        private Request handled;

        @Override
        public void handle(Request request, Response response) {
            this.handled = request;
            response.setStatus(Status.SUCCESS_OK);
        }
    }

    @Test
    void routeShouldSendTheCardPathToTheCard() {
        Probe card = new Probe();
        Probe next = new Probe();
        ServerCardRoute route = new ServerCardRoute(new Context(), "/mcp", card, false, next);
        Request request = new Request(Method.GET, "http://localhost:3001/mcp/server-card");
        route.handle(request, new Response(request));
        assertSame(request, card.handled);
        assertEquals(null, next.handled);
    }

    @Test
    void routeShouldSendTheMcpEndpointToTheRestOfTheChain() {
        Probe card = new Probe();
        Probe next = new Probe();
        ServerCardRoute route = new ServerCardRoute(new Context(), "/mcp", card, false, next);
        Request request = new Request(Method.POST, "http://localhost:3001/mcp");
        route.handle(request, new Response(request));
        assertSame(request, next.handled);
    }

    @Test
    void routeShouldNotMatchTheCardSuffixUnderAnotherPathWhenAPathIsDeclared() {
        ServerCardRoute route = new ServerCardRoute(new Context(), "/mcp", new Probe(), false,
                new Probe());
        assertFalse(route.isCardPath("/other/server-card"));
    }

    @Test
    void routeShouldMatchAnyServerCardSegmentWhenNoPathIsDeclared() {
        ServerCardRoute route = new ServerCardRoute(new Context(), null, new Probe(), false,
                new Probe());
        assertTrue(route.isCardPath("/server-card"));
        assertTrue(route.isCardPath("/mcp/server-card"));
    }

    @Test
    void routeShouldSendOnlyThePreflightToTheCardWhenItIsAuthenticated() {
        Probe card = new Probe();
        Probe next = new Probe();
        ServerCardRoute route = new ServerCardRoute(new Context(), "/mcp", card, true, next);
        Request get = new Request(Method.GET, "http://localhost:3001/mcp/server-card");
        route.handle(get, new Response(get));
        Request options = new Request(Method.OPTIONS, "http://localhost:3001/mcp/server-card");
        route.handle(options, new Response(options));
        assertSame(get, next.handled);
        assertSame(options, card.handled);
    }
}
