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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.CookieSetting;
import org.restlet.data.Dimension;
import org.restlet.data.Method;
import org.restlet.representation.StringRepresentation;

/**
 * Unit tests for {@link ForwardResponseHeaders} (#1002).
 */
public class ForwardResponseHeadersTest {

    private static final String BASE_URI = "http://upstream:9090/v1";

    private static Request incoming(String uri, String pathAttribute) {
        Request request = new Request(Method.GET, uri);
        request.getAttributes().put("path", pathAttribute);
        return request;
    }

    private static Response upstreamResponse() {
        return new Response(new Request(Method.GET, BASE_URI + "/items"));
    }

    @Test
    public void rewriteLocationShouldPointToExposedPathWhenUnderBaseUri() {
        Request request = incoming("http://localhost:8080/proxy/items", "items");

        assertEquals("http://localhost:8080/proxy/items/42",
                ForwardResponseHeaders.rewriteLocation(BASE_URI + "/items/42", BASE_URI, request));
    }

    @Test
    public void rewriteLocationShouldKeepQueryWhenUnderBaseUri() {
        Request request = incoming("http://localhost:8080/proxy/items", "items");

        assertEquals("http://localhost:8080/proxy/items?page=2",
                ForwardResponseHeaders.rewriteLocation(BASE_URI + "/items?page=2", BASE_URI,
                        request));
    }

    @Test
    public void rewriteLocationShouldPointToExposedRootWhenEqualToBaseUri() {
        Request request = incoming("http://localhost:8080/proxy/items", "items");

        assertEquals("http://localhost:8080/proxy",
                ForwardResponseHeaders.rewriteLocation(BASE_URI, BASE_URI, request));
    }

    @Test
    public void rewriteLocationShouldNotDoubleTheSlashWhenForwardedPathIsEmpty() {
        Request request = incoming("http://localhost:8080/proxy/", "");

        assertEquals("http://localhost:8080/proxy/a",
                ForwardResponseHeaders.rewriteLocation(BASE_URI + "/a", BASE_URI, request));
    }

    @Test
    public void rewriteLocationShouldKeepLocationOutsideBaseUri() {
        Request request = incoming("http://localhost:8080/proxy/items", "items");

        assertEquals("https://elsewhere.example/x",
                ForwardResponseHeaders.rewriteLocation("https://elsewhere.example/x", BASE_URI,
                        request));
    }

    @Test
    public void rewriteLocationShouldKeepLocationThatOnlySharesAPrefixWithBaseUri() {
        Request request = incoming("http://localhost:8080/proxy/items", "items");

        assertEquals("http://upstream:9090/v10/x",
                ForwardResponseHeaders.rewriteLocation("http://upstream:9090/v10/x", BASE_URI,
                        request));
    }

    @Test
    public void rewriteLocationShouldKeepLocationWhenIncomingPathDoesNotEndWithForwardedPath() {
        Request request = incoming("http://localhost:8080/proxy/items", "other");

        assertEquals(BASE_URI + "/items/42",
                ForwardResponseHeaders.rewriteLocation(BASE_URI + "/items/42", BASE_URI, request));
    }

    @Test
    public void relayShouldCopyStandardResponseHeadersByDefault() {
        Response from = upstreamResponse();
        from.setLocationRef(BASE_URI + "/items/42");
        Date retryAfter = new Date(System.currentTimeMillis() + 120_000);
        from.setRetryAfter(retryAfter);
        from.getCacheDirectives().add(CacheDirective.maxAge(60));
        from.getDimensions().add(Dimension.MEDIA_TYPE);
        from.getAllowedMethods().add(Method.GET);
        from.getServerInfo().setAcceptingRanges(true);
        from.setAge(30);
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI);

        assertEquals("http://localhost:8080/proxy/items/42", to.getLocationRef().toString());
        assertEquals(retryAfter, to.getRetryAfter());
        assertEquals(List.of(CacheDirective.maxAge(60)), to.getCacheDirectives());
        assertTrue(to.getDimensions().contains(Dimension.MEDIA_TYPE));
        assertTrue(to.getAllowedMethods().contains(Method.GET));
        assertTrue(to.getServerInfo().isAcceptingRanges());
        assertEquals(30, to.getAge());
    }

    @Test
    public void relayShouldKeepEachChallengeAsReceived() {
        Response from = upstreamResponse();
        from.getHeaders().add("WWW-Authenticate", "Basic realm=\"up\"");
        from.getHeaders().add("WWW-Authenticate", "Bearer realm=\"up\", error=\"invalid_token\"");
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI);

        assertEquals(List.of("Basic realm=\"up\"",
                "Bearer realm=\"up\", error=\"invalid_token\""),
                to.getChallengeRequests().stream().map(c -> c.getScheme().getTechnicalName()
                        + " " + c.getRawValue()).toList());
    }

    @Test
    public void relayShouldRewriteContentLocationUnderBaseUri() {
        Response from = upstreamResponse();
        StringRepresentation entity = new StringRepresentation("{}");
        entity.setLocationRef(BASE_URI + "/items/42");
        from.setEntity(entity);
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));
        to.setEntity(from.getEntity());

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI);

        assertEquals("http://localhost:8080/proxy/items/42",
                to.getEntity().getLocationRef().toString());
    }

    @Test
    public void relayShouldNotCopyCookiesWhenSetCookieIsNotTrusted() {
        Response from = upstreamResponse();
        from.getCookieSettings().add(new CookieSetting("session", "abc"));
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI);

        assertTrue(to.getCookieSettings().isEmpty(), "Set-Cookie needs an explicit opt-in");
    }

    @Test
    public void relayShouldCopyCookiesWhenSetCookieIsTrusted() {
        Response from = upstreamResponse();
        from.getCookieSettings().add(new CookieSetting("session", "abc"));
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of("set-cookie"), BASE_URI);

        assertEquals("abc", to.getCookieSettings().getFirstValue("session"));
    }

    @Test
    public void relayShouldCopyTrustedExtensionHeaderWithEveryValue() {
        Response from = upstreamResponse();
        from.getHeaders().add("X-Request-Id", "r-1");
        from.getHeaders().add("x-request-id", "r-2");
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of("X-Request-Id"), BASE_URI);

        assertEquals(List.of("r-1", "r-2"),
                List.of(to.getHeaders().getValuesArray("X-Request-Id", true)));
    }

    @Test
    public void relayShouldNotCopyExtensionHeaderThatIsNotTrusted() {
        Response from = upstreamResponse();
        from.getHeaders().add("X-Internal-Node", "node-7");
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of("X-Request-Id"), BASE_URI);

        assertNull(to.getHeaders().getFirstValue("X-Internal-Node", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Request-Id", "Set-Cookie", "set-cookie", "Location", "ETag",
            "Retry-After", "Pragma"})
    public void validateShouldAcceptRelayableHeader(String name) {
        assertDoesNotThrow(() -> ForwardResponseHeaders.validate(List.of(name), "resource"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Connection", "keep-alive", "Transfer-Encoding", "Upgrade", "TE",
            "Trailer", "Proxy-Authenticate", "Content-Length"})
    public void validateShouldRejectHopByHopHeader(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ForwardResponseHeaders.validate(List.of(name), "REST resource '/p'"));
        assertTrue(e.getMessage().contains(name) && e.getMessage().contains("REST resource '/p'"),
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Server", "Date", "Via", "Access-Control-Allow-Origin",
            "Authorization"})
    public void validateShouldRejectStandardHeaderThatCannotBeRelayed(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ForwardResponseHeaders.validate(List.of(name), "REST resource '/p'"));
        assertTrue(e.getMessage().contains(name), e.getMessage());
    }

    @Test
    public void validateShouldAcceptNullList() {
        assertDoesNotThrow(() -> ForwardResponseHeaders.validate(null, "resource"));
    }
}
