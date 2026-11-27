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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.CacheDirective;
import org.restlet.data.Dimension;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import io.ikanos.engine.exposes.mcp.ServerCardBuilder.Template;
import io.ikanos.spec.InfoSpec;
import io.ikanos.spec.exposes.mcp.McpServerCardSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link ServerCardRestlet} and {@link ServerCardRoute}: the card's status codes,
 * its URL built from the request, caching and CORS headers, and which requests reach the card.
 */
class ServerCardRestletTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final Predicate<Request> UNTRUSTED = request -> false;
    private static final Predicate<Request> TRUSTED = request -> true;

    private static Template template(String fixedUrl) {
        McpServerSpec server = new McpServerSpec("10.0.1.12", 3001, "shipyard-tools",
                "Shipyard MCP tools for fleet management");
        server.setPath("/mcp");
        McpServerCardSpec card = new McpServerCardSpec();
        card.setUrl(fixedUrl);
        server.setCard(card);
        InfoSpec info = new InfoSpec("Shipyard Fleet Management", "Fleet management", null, null);
        info.setVersion("1.4.0");
        info.setPublisher("io.naftiko");
        return ServerCardBuilder.build(server, info, List.of("2026-07-28"), warning -> { });
    }

    private static final Template TEMPLATE = template(null);

    private static Request request(Method method, String host, String... headers) {
        Request request = new Request(method, "http://localhost:3001/mcp/server-card");
        if (host != null) {
            request.getHeaders().add("Host", host);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.getHeaders().add(headers[i], headers[i + 1]);
        }
        return request;
    }

    private static Response send(Restlet restlet, Request request) {
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }

    private static Response send(Restlet restlet, Method method, String path) {
        Request request = new Request(method, "http://localhost:3001" + path);
        request.getHeaders().add("Host", "fleet.example.com");
        return send(restlet, request);
    }

    private static String remoteUrl(Response response) throws Exception {
        return JSON.readTree(response.getEntity().getText()).path("remotes").path(0).path("url")
                .asString();
    }

    private static ServerCardRestlet publicCard() {
        return new ServerCardRestlet(new Context(), TEMPLATE, false, null, UNTRUSTED);
    }

    @Test
    void getShouldServeTheCardWithItsMediaTypeAndEtag() throws Exception {
        Response response = send(publicCard(), Method.GET, "/mcp/server-card");
        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals(ServerCardBuilder.MEDIA_TYPE,
                response.getEntity().getMediaType().getName());
        assertEquals(new Tag(TEMPLATE.render("http://fleet.example.com").etag(), false),
                response.getEntity().getTag());
        assertEquals(TEMPLATE.render("http://fleet.example.com").json(),
                response.getEntity().getText());
    }

    @Test
    void getShouldBuildTheUrlFromTheHostTheClientAddressed() throws Exception {
        Response response = send(publicCard(), request(Method.GET, "Fleet.Example.com:8443"));
        assertEquals("http://fleet.example.com:8443/mcp", remoteUrl(response));
    }

    @Test
    void getShouldNeverPublishTheBindAddress() throws Exception {
        Response response = send(publicCard(), request(Method.GET, "fleet.example.com"));
        String card = response.getEntity().getText();
        assertFalse(card.contains("10.0.1.12"), card);
        assertFalse(card.contains(":3001"), card);
    }

    @Test
    void getShouldChangeTheEtagWithTheUrl() {
        ServerCardRestlet restlet = publicCard();
        Tag first = send(restlet, request(Method.GET, "a.example.com")).getEntity().getTag();
        Tag second = send(restlet, request(Method.GET, "b.example.com")).getEntity().getTag();
        Tag again = send(restlet, request(Method.GET, "a.example.com")).getEntity().getTag();
        assertNotEquals(first, second);
        assertEquals(first, again);
    }

    @Test
    void getShouldAnswerBadRequestWithoutAHost() {
        Response response = send(publicCard(), request(Method.GET, null));
        assertEquals(Status.CLIENT_ERROR_BAD_REQUEST, response.getStatus());
        assertEquals(null, response.getEntity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example/path", "user@evil.example", "a b", "evil.example:",
        "-evil.example", "evil.example:123456"})
    void getShouldAnswerBadRequestOnAnInvalidHost(String host) {
        Response response = send(publicCard(), request(Method.GET, host));
        assertEquals(Status.CLIENT_ERROR_BAD_REQUEST, response.getStatus());
    }

    @Test
    void getShouldAcceptAnIpv6Host() throws Exception {
        Response response = send(publicCard(), request(Method.GET, "[::1]:3001"));
        assertEquals("http://[::1]:3001/mcp", remoteUrl(response));
    }

    @Test
    void getShouldIgnoreForwardedHeadersFromAnUntrustedPeer() throws Exception {
        Response response = send(publicCard(), request(Method.GET, "fleet.example.com",
                "X-Forwarded-Host", "evil.example", "X-Forwarded-Proto", "https"));
        assertEquals("http://fleet.example.com/mcp", remoteUrl(response));
        assertTrue(response.getDimensions().isEmpty(), response.getDimensions().toString());
    }

    @Test
    void getShouldTakeForwardedHeadersFromATrustedPeerAndVaryOnEverything() throws Exception {
        ServerCardRestlet restlet =
                new ServerCardRestlet(new Context(), TEMPLATE, false, null, TRUSTED);
        Response response = send(restlet, request(Method.GET, "ikanos.internal:3001",
                "X-Forwarded-Host", "public.example.com, ikanos.internal",
                "X-Forwarded-Proto", "HTTPS"));
        assertEquals("https://public.example.com/mcp", remoteUrl(response));
        assertTrue(response.getDimensions().contains(Dimension.UNSPECIFIED));
    }

    @Test
    void getShouldRefuseAnUnusableForwardedHeaderFromATrustedPeer() {
        ServerCardRestlet restlet =
                new ServerCardRestlet(new Context(), TEMPLATE, false, null, TRUSTED);
        assertEquals(Status.CLIENT_ERROR_BAD_REQUEST, send(restlet, request(Method.GET,
                "ikanos.internal", "X-Forwarded-Proto", "ftp")).getStatus());
        assertEquals(Status.CLIENT_ERROR_BAD_REQUEST, send(restlet, request(Method.GET,
                "ikanos.internal", "X-Forwarded-Host", "evil.example/x")).getStatus());
    }

    @Test
    void getShouldPreferCardUrlOverTheRequest() throws Exception {
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(),
                template("https://fleet.example.com/mcp"), false, null, TRUSTED);
        Response response = send(restlet, request(Method.GET, null,
                "X-Forwarded-Host", "other.example"));
        assertEquals(Status.SUCCESS_OK, response.getStatus());
        assertEquals("https://fleet.example.com/mcp", remoteUrl(response));
        assertTrue(response.getDimensions().isEmpty(), response.getDimensions().toString());
    }

    @Test
    void headShouldAnswerLikeGet() throws Exception {
        Response get = send(publicCard(), Method.GET, "/mcp/server-card");
        Response head = send(publicCard(), Method.HEAD, "/mcp/server-card");
        assertEquals(Status.SUCCESS_OK, head.getStatus());
        assertEquals(get.getEntity().getTag(), head.getEntity().getTag());
        assertEquals(get.getEntity().getMediaType(), head.getEntity().getMediaType());
        assertEquals(get.getCacheDirectives(), head.getCacheDirectives());
    }

    @Test
    void headShouldAnswerNotModifiedWhenIfNoneMatchNamesTheCard() {
        Tag etag = send(publicCard(), Method.GET, "/mcp/server-card").getEntity().getTag();
        Request request = request(Method.HEAD, "fleet.example.com");
        request.getConditions().setNoneMatch(List.of(etag));
        assertEquals(Status.REDIRECTION_NOT_MODIFIED, send(publicCard(), request).getStatus());
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
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(), TEMPLATE, true,
                "Authorization", UNTRUSTED);
        List<CacheDirective> cache = send(restlet, Method.GET, "/mcp/server-card")
                .getCacheDirectives();
        assertTrue(cache.contains(CacheDirective.privateInfo()), cache.toString());
    }

    @Test
    void getShouldAnswerNotModifiedWhenIfNoneMatchNamesTheCard() {
        Tag etag = send(publicCard(), Method.GET, "/mcp/server-card").getEntity().getTag();
        Request request = request(Method.GET, "fleet.example.com");
        request.getConditions().setNoneMatch(List.of(etag));
        assertEquals(Status.REDIRECTION_NOT_MODIFIED, send(publicCard(), request).getStatus());
    }

    @Test
    void getShouldServeTheCardWhenIfNoneMatchNamesAnotherVersion() {
        Request request = request(Method.GET, "fleet.example.com");
        request.getConditions().setNoneMatch(List.of(new Tag("old", false)));
        assertEquals(Status.SUCCESS_OK, send(publicCard(), request).getStatus());
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
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(), TEMPLATE, true,
                "X-Api-Key", UNTRUSTED);
        Response response = send(restlet, Method.OPTIONS, "/mcp/server-card");
        assertTrue(response.getAccessControlAllowHeaders().contains("X-Api-Key"));
    }

    @Test
    void optionsShouldNotAllowACredentialsHeaderOnAPublicCard() {
        ServerCardRestlet restlet = new ServerCardRestlet(new Context(), TEMPLATE, false,
                "Authorization", UNTRUSTED);
        Response response = send(restlet, Method.OPTIONS, "/mcp/server-card");
        assertFalse(response.getAccessControlAllowHeaders().contains("Authorization"));
    }

    @Test
    void postShouldBeRejectedAsMethodNotAllowed() {
        Response response = send(publicCard(), Method.POST, "/mcp/server-card");
        assertEquals(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED, response.getStatus());
        assertTrue(response.getAllowedMethods().contains(Method.GET));
        assertTrue(response.getAllowedMethods().contains(Method.HEAD));
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
