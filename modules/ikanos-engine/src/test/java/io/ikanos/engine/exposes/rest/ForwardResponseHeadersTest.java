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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.CookieSetting;
import org.restlet.data.Dimension;
import org.restlet.data.Method;
import org.restlet.data.Warning;
import org.restlet.representation.StringRepresentation;

/**
 * Unit tests for {@link ForwardResponseHeaders} (#1002).
 */
public class ForwardResponseHeadersTest {

    private static final String BASE_URI = "http://upstream:9090/v1";

    private static Request incoming() {
        return incoming("http://localhost:8080/proxy/items", "items");
    }

    private static Request incoming(String uri, String pathAttribute) {
        Request request = new Request(Method.GET, uri);
        request.getAttributes().put("path", pathAttribute);
        return request;
    }

    private static Response upstreamResponse() {
        return new Response(new Request(Method.GET, BASE_URI + "/items"));
    }

    private static Response relayed(Response from) {
        return relayed(from, List.of());
    }

    private static Response relayed(Response from, List<String> trusted) {
        Response to = new Response(incoming());
        to.setEntity(from.getEntity());
        ForwardResponseHeaders.relay(from, to, trusted, BASE_URI);
        return to;
    }

    private static String location(String raw) {
        return ForwardResponseHeaders.relayedLocation(raw, BASE_URI + "/items", BASE_URI,
                incoming());
    }

    // --- Location: rewrite under the base URI, as a path-absolute reference ---

    @ParameterizedTest
    @CsvSource({
            "http://upstream:9090/v1/items/42, /proxy/items/42",
            "http://upstream:9090/v1/items?page=2, /proxy/items?page=2",
            "http://upstream:9090/v1, /proxy",
            "/v1/items/43, /proxy/items/43",
            "items/44, /proxy/items/44",
            "HTTP://UPSTREAM:9090/v1/items/45, /proxy/items/45"})
    public void relayedLocationShouldBePathAbsoluteUnderTheExposedResourceWhenUnderBaseUri(
            String raw, String expected) {
        assertEquals(expected, location(raw));
    }

    @Test
    public void relayedLocationShouldIgnoreATrailingSlashOnTheBaseUri() {
        assertEquals("/proxy/items/42", ForwardResponseHeaders.relayedLocation(
                "http://upstream:9090/v1/items/42", BASE_URI + "/items", BASE_URI + "/",
                incoming()));
    }

    @Test
    public void relayedLocationShouldIgnoreAnExplicitDefaultPortOnTheLocation() {
        assertEquals("/proxy/items/42", ForwardResponseHeaders.relayedLocation(
                "http://upstream:80/v1/items/42", "http://upstream/v1/items",
                "http://upstream/v1", incoming()));
    }

    @Test
    public void relayedLocationShouldIgnoreAnExplicitDefaultPortOnTheBaseUri() {
        assertEquals("/proxy/items/42", ForwardResponseHeaders.relayedLocation(
                "https://upstream/v1/items/42", "https://upstream:443/v1/items",
                "https://upstream:443/v1", incoming()));
    }

    @Test
    public void relayedLocationShouldRewriteEveryPathWhenTheBaseUriHasNoPath() {
        assertEquals("/proxy/items/1", ForwardResponseHeaders.relayedLocation(
                "http://upstream:9090/items/1", "http://upstream:9090/items",
                "http://upstream:9090", incoming()));
    }

    @Test
    public void relayedLocationShouldNotDoubleTheSlashWhenTheForwardedPathIsEmpty() {
        assertEquals("/proxy/a", ForwardResponseHeaders.relayedLocation(BASE_URI + "/a",
                BASE_URI, BASE_URI, incoming("http://localhost:8080/proxy/", "")));
    }

    // --- Location: outside the base URI ---

    @Test
    public void relayedLocationShouldKeepAnAbsoluteLocationOnAnotherHost() {
        assertEquals("https://elsewhere.example/x", location("https://elsewhere.example/x"));
    }

    @Test
    public void relayedLocationShouldKeepARelativeLocationOutsideTheBaseUriUnchanged() {
        assertEquals("/other/1", location("/other/1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://upstream:9090/v10/x", "http://upstream:9090/admin",
            "http://UPSTREAM:9090/admin"})
    public void relayedLocationShouldDropAnAbsoluteLocationOnTheUpstreamOutsideTheBaseUri(
            String raw) {
        assertNull(location(raw), "an upstream URL outside the base must not reach the caller");
    }

    @Test
    public void relayedLocationShouldDropALocationUnderBaseUriWhenTheExposedPathIsUnknown() {
        assertNull(ForwardResponseHeaders.relayedLocation(BASE_URI + "/items/42",
                BASE_URI + "/items", BASE_URI, incoming("http://localhost:8080/proxy/items",
                        "other")));
    }

    @Test
    public void relayShouldWriteThePathAbsoluteLocation() {
        Response from = upstreamResponse();
        from.getHeaders().add("Location", BASE_URI + "/items/42");

        assertEquals("/proxy/items/42", relayed(from).getLocationRef().getTargetRef().toString());
    }

    @Test
    public void relayShouldDropALocationOnTheUpstreamOutsideTheBaseUri() {
        Response from = upstreamResponse();
        from.getHeaders().add("Location", "http://upstream:9090/admin");

        assertNull(relayed(from).getLocationRef());
    }

    @Test
    public void relayShouldWriteThePathAbsoluteContentLocation() {
        Response from = upstreamResponse();
        from.getHeaders().add("Content-Location", BASE_URI + "/items/42");
        from.setEntity(new StringRepresentation("{}"));

        assertEquals("/proxy/items/42",
                relayed(from).getEntity().getLocationRef().getTargetRef().toString());
    }

    @Test
    public void relayShouldDropAContentLocationOnTheUpstreamOutsideTheBaseUri() {
        Response from = upstreamResponse();
        from.getHeaders().add("Content-Location", "http://upstream:9090/admin");
        StringRepresentation entity = new StringRepresentation("{}");
        entity.setLocationRef("http://upstream:9090/admin");
        from.setEntity(entity);

        assertNull(relayed(from).getEntity().getLocationRef());
    }

    // --- Other standard headers ---

    @Test
    public void relayShouldCopyRetryAfter() {
        Response from = upstreamResponse();
        Date retryAfter = new Date(System.currentTimeMillis() + 120_000);
        from.setRetryAfter(retryAfter);

        assertEquals(retryAfter, relayed(from).getRetryAfter());
    }

    @Test
    public void relayShouldCopyCacheDirectives() {
        Response from = upstreamResponse();
        from.getCacheDirectives().add(CacheDirective.maxAge(60));

        assertEquals(List.of(CacheDirective.maxAge(60)), relayed(from).getCacheDirectives());
    }

    @Test
    public void relayShouldCopyAllowedMethods() {
        Response from = upstreamResponse();
        from.getAllowedMethods().add(Method.GET);

        assertTrue(relayed(from).getAllowedMethods().contains(Method.GET));
    }

    @Test
    public void relayShouldCopyAcceptRanges() {
        Response from = upstreamResponse();
        from.getServerInfo().setAcceptingRanges(true);

        assertTrue(relayed(from).getServerInfo().isAcceptingRanges());
    }

    @Test
    public void relayShouldCopyAge() {
        Response from = upstreamResponse();
        from.setAge(30);

        assertEquals(30, relayed(from).getAge());
    }

    @Test
    public void relayShouldNotCopyWarnings() {
        Response from = upstreamResponse();
        from.getWarnings().add(new Warning());

        assertTrue(relayed(from).getWarnings().isEmpty(), "Warning is obsolete (RFC 9111)");
    }

    // --- Vary ---

    @Test
    public void relayShouldCopyAVaryDimensionRestletKnows() {
        Response from = upstreamResponse();
        from.getHeaders().add("Vary", "Accept");
        from.getDimensions().add(Dimension.MEDIA_TYPE);

        assertEquals(List.of(Dimension.MEDIA_TYPE), new ArrayList<>(relayed(from).getDimensions()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cookie", "X-Tenant-Id", "Accept, X-Tenant-Id"})
    public void relayShouldVaryOnEverythingWhenAFieldHasNoRestletDimension(String vary) {
        Response from = upstreamResponse();
        from.getHeaders().add("Vary", vary);

        assertTrue(relayed(from).getDimensions().contains(Dimension.UNSPECIFIED),
                "Vary: " + vary + " must be relayed as Vary: *");
    }

    // --- WWW-Authenticate ---

    @Test
    public void relayShouldKeepEachChallengeAsReceived() {
        Response from = upstreamResponse();
        from.getHeaders().add("WWW-Authenticate", "Basic realm=\"up\"");
        from.getHeaders().add("WWW-Authenticate", "Bearer realm=\"up\", error=\"invalid_token\"");

        assertEquals(List.of("Basic realm=\"up\"",
                "Bearer realm=\"up\", error=\"invalid_token\""),
                relayed(from).getChallengeRequests().stream()
                        .map(c -> c.getScheme().getTechnicalName() + " " + c.getRawValue())
                        .toList());
    }

    @Test
    public void relayShouldKeepABareChallenge() {
        Response from = upstreamResponse();
        from.getHeaders().add("WWW-Authenticate", "Negotiate");

        assertEquals(List.of("Negotiate"), relayed(from).getChallengeRequests().stream()
                .map(c -> c.getScheme().getTechnicalName()).toList());
    }

    // --- Opt-in and extension headers ---

    @Test
    public void relayShouldNotCopyCookiesWhenSetCookieIsNotTrusted() {
        Response from = upstreamResponse();
        from.getCookieSettings().add(new CookieSetting("session", "abc"));

        assertTrue(relayed(from).getCookieSettings().isEmpty(), "Set-Cookie needs an opt-in");
    }

    @Test
    public void relayShouldCopyCookiesWhenSetCookieIsTrusted() {
        Response from = upstreamResponse();
        from.getCookieSettings().add(new CookieSetting("session", "abc"));

        assertEquals("abc", relayed(from, List.of("set-cookie")).getCookieSettings()
                .getFirstValue("session"));
    }

    @Test
    public void relayShouldCopyCookiesOnceWhenSetCookieIsListedTwice() {
        Response from = upstreamResponse();
        from.getCookieSettings().add(new CookieSetting("session", "abc"));

        assertEquals(1, relayed(from, List.of("Set-Cookie", "set-cookie")).getCookieSettings()
                .size());
    }

    @Test
    public void relayShouldCopyTrustedExtensionHeaderWithEveryValue() {
        Response from = upstreamResponse();
        from.getHeaders().add("X-Request-Id", "r-1");
        from.getHeaders().add("x-request-id", "r-2");

        assertEquals(List.of("r-1", "r-2"), Arrays.asList(relayed(from, List.of("X-Request-Id"))
                .getHeaders().getValuesArray("X-Request-Id", true)));
    }

    @Test
    public void relayShouldCopyAnExtensionHeaderOnceWhenListedTwiceInDifferentCase() {
        Response from = upstreamResponse();
        from.getHeaders().add("X-Request-Id", "r-1");

        assertEquals(List.of("r-1"), Arrays.asList(relayed(from,
                List.of("X-Request-Id", "x-request-id")).getHeaders()
                .getValuesArray("X-Request-Id", true)));
    }

    @Test
    public void relayShouldNotCopyExtensionHeaderThatIsNotTrusted() {
        Response from = upstreamResponse();
        from.getHeaders().add("X-Internal-Node", "node-7");

        assertNull(relayed(from, List.of("X-Request-Id")).getHeaders()
                .getFirstValue("X-Internal-Node", true));
    }

    // --- Load-time validation ---

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
    @ValueSource(strings = {"Server", "Date", "Via", "Warning", "Access-Control-Allow-Origin",
            "Authorization"})
    public void validateShouldRejectStandardHeaderThatCannotBeRelayed(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ForwardResponseHeaders.validate(List.of(name), "REST resource '/p'"));
        assertTrue(e.getMessage().contains(name), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    public void validateShouldRejectBlankEntry(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ForwardResponseHeaders.validate(List.of(name), "REST resource '/p'"));
        assertTrue(e.getMessage().contains("blank"), e.getMessage());
    }

    @Test
    public void validateShouldRejectNullEntry() {
        List<String> names = new ArrayList<>();
        names.add(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ForwardResponseHeaders.validate(names, "REST resource '/p'"));
        assertTrue(e.getMessage().contains("blank"), e.getMessage());
    }

    @Test
    public void validateShouldAcceptNullList() {
        assertDoesNotThrow(() -> ForwardResponseHeaders.validate(null, "resource"));
    }
}
