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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import org.restlet.data.Warning;
import org.restlet.representation.StringRepresentation;
import io.ikanos.engine.LogCapture;

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
        return relayed(from, trusted, incoming());
    }

    private static Response relayed(Response from, List<String> trusted, Request request) {
        Response to = new Response(request);
        to.setEntity(from.getEntity());
        ForwardResponseHeaders.relay(from, to, trusted, BASE_URI, true, new HashSet<>());
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

    @ParameterizedTest
    @ValueSource(strings = {"/other/1", "../../other", "/internal/x"})
    public void relayedLocationShouldDropARelativeLocationThatResolvesOutsideTheBaseUri(
            String raw) {
        assertNull(location(raw), "a relative location outside the base is on the upstream");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://upstream:9090/v10/x", "http://upstream:9090/admin",
            "http://UPSTREAM:9090/admin", "//upstream:9090/admin", "https://upstream/v1/items",
            "http://upstream:8443/v1/items", "http://upstream/v1/items",
            "http://upstream:9090/v1/../admin"})
    public void relayedLocationShouldDropAnAbsoluteLocationOnTheUpstreamOutsideTheBaseUri(
            String raw) {
        assertNull(location(raw), "an upstream URL outside the base must not reach the caller");
    }

    @Test
    public void relayedLocationShouldRewriteALocationUnderAnUnderscoreHostBaseUri() {
        assertEquals("/proxy/items/42", ForwardResponseHeaders.relayedLocation(
                "http://orders_api:8080/v1/items/42", "http://orders_api:8080/v1/items",
                "http://orders_api:8080/v1", incoming()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://orders_api:8080/admin", "http://ORDERS_API:8080/admin",
            "https://orders_api/v1/items"})
    public void relayedLocationShouldDropALocationOnAnUnderscoreHostOutsideTheBaseUri(
            String raw) {
        assertNull(ForwardResponseHeaders.relayedLocation(raw, "http://orders_api:8080/v1/items",
                "http://orders_api:8080/v1", incoming()));
    }

    @Test
    public void relayedLocationShouldKeepTheFragmentWhenUnderBaseUri() {
        assertEquals("/proxy/items/42#top", location(BASE_URI + "/items/42#top"));
    }

    @Test
    public void relayedLocationShouldRewriteADotDotLocationThatResolvesInsideTheBaseUri() {
        assertEquals("/proxy/x", ForwardResponseHeaders.relayedLocation("../x",
                BASE_URI + "/items/7", BASE_URI, incoming()));
    }

    @Test
    public void relayedLocationShouldKeepPercentEncodingInThePath() {
        assertEquals("/proxy/items/a%20b%2Fc", location(BASE_URI + "/items/a%20b%2Fc"));
    }

    @Test
    public void relayedLocationShouldDropAnUnparseableLocation() {
        assertNull(location("http://upstream:9090/v1/a b"));
    }

    @Test
    public void relayedLocationShouldDropALocationWhenTheBaseUriIsUnparseable() {
        assertNull(ForwardResponseHeaders.relayedLocation(BASE_URI + "/items/42",
                BASE_URI + "/items", "http://upstream:9090/v 1", incoming()));
    }

    @Test
    public void relayedLocationShouldRewriteAContentLocationEqualToTheRequestUri() {
        assertEquals("/proxy/items", location(BASE_URI + "/items"));
    }

    @ParameterizedTest
    @CsvSource({
            "'?page=2', /proxy/items?page=2",
            "'', /proxy/items",
            "'#top', /proxy/items#top"})
    public void relayedLocationShouldKeepTheRequestPathForASameDocumentReference(String raw,
            String expected) {
        assertEquals(expected, location(raw == null ? "" : raw));
    }

    @Test
    public void relayedLocationShouldNeverInheritTheUpstreamQueryOfASameDocumentReference() {
        String upstreamRequestUri = BASE_URI + "/items?api_key=SECRET";

        assertEquals("/proxy/items#top", ForwardResponseHeaders.relayedLocation("#top",
                upstreamRequestUri, BASE_URI, incoming()));
        assertEquals("/proxy/items?q=1", ForwardResponseHeaders.relayedLocation("",
                upstreamRequestUri, BASE_URI,
                incoming("http://localhost:8080/proxy/items?q=1", "items")));
        assertEquals("/proxy/items?page=2", ForwardResponseHeaders.relayedLocation("?page=2",
                upstreamRequestUri, BASE_URI, incoming()));
    }

    @ParameterizedTest
    @CsvSource({
            "'?page=2', /proxy/items?page=2",
            "'', /proxy/items?q=1",
            "'#top', /proxy/items?q=1#top"})
    public void relayShouldRewriteASameDocumentLocationAndContentLocationWithoutTheUpstreamQuery(
            String raw, String expected) {
        String value = raw == null ? "" : raw;
        Response from = new Response(new Request(Method.GET, BASE_URI + "/items?api_key=SECRET"));
        from.getHeaders().add("Location", value);
        from.getHeaders().add("Content-Location", value);
        from.setEntity(new StringRepresentation("{}"));

        Response to = relayed(from, List.of(),
                incoming("http://localhost:8080/proxy/items?q=1", "items"));

        assertEquals(expected, to.getLocationRef().getTargetRef().toString());
        assertEquals(expected, to.getEntity().getLocationRef().getTargetRef().toString());
    }

    @Test
    public void relayShouldResolveARelativeLocationAgainstTheBaseUriWhenTheRequestHasNoUri() {
        Response from = new Response(new Request());
        from.getHeaders().add("Location", "/v1/items/42");

        assertEquals("/proxy/items/42", relayed(from).getLocationRef().getTargetRef().toString());
    }

    /** Relays a 201 (or {@code status}) whose Location is dropped, on its own exposed path. */
    private static void relayDroppedLocation(Status status, String exposedPath) {
        relayDroppedLocation(status, exposedPath, new HashSet<>());
    }

    /** Same, with the warning flags of the exposed resource. */
    private static void relayDroppedLocation(Status status, String exposedPath,
            Set<String> warned) {
        Response from = upstreamResponse();
        from.setStatus(status);
        from.getHeaders().add("Location", "http://upstream:9090/secret-admin-path");
        Response to = new Response(incoming("http://localhost:8080" + exposedPath + "/items",
                "items"));
        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, true, warned);
    }

    /** Levels of the events about a dropped Location, with the Restlet logger at DEBUG. */
    private static List<ch.qos.logback.classic.Level> droppedLocationLevels(LogCapture logs) {
        return logs.events().stream()
                .filter(e -> e.getFormattedMessage().contains("did not relay the upstream Location"))
                .map(e -> e.getLevel()).toList();
    }

    private static ch.qos.logback.classic.Logger restletLogger() {
        return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.restlet.Context.getCurrentLogger().getName());
    }

    @Test
    public void relayShouldLogADroppedLocationWithItsHeaderAndStatusButNotItsValue() {
        try (LogCapture logs = new LogCapture()) {
            relayDroppedLocation(Status.SUCCESS_CREATED, "/log-value");

            List<String> logged = new ArrayList<>(logs.messages());
            logs.events().forEach(e -> {
                if (e.getArgumentArray() != null) {
                    for (Object argument : e.getArgumentArray()) {
                        logged.add(String.valueOf(argument));
                    }
                }
            });
            String all = String.join(" | ", logged);
            assertTrue(all.contains("Location") && all.contains("201"), all);
            assertFalse(all.contains("secret-admin-path"),
                    "neither the message nor its parameters may carry the dropped value: " + all);
        }
    }

    @Test
    public void relayShouldWarnOnceThenLogAtFineWhenARedirectLocationIsDropped() {
        ch.qos.logback.classic.Logger logger = restletLogger();
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (LogCapture logs = new LogCapture()) {
            Set<String> warned = new HashSet<>();
            relayDroppedLocation(Status.REDIRECTION_FOUND, "/log-3xx", warned);
            relayDroppedLocation(Status.REDIRECTION_FOUND, "/log-3xx", warned);

            assertEquals(List.of(ch.qos.logback.classic.Level.WARN,
                    ch.qos.logback.classic.Level.DEBUG), droppedLocationLevels(logs));
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    public void relayShouldKeepOneWarningFlagPerHeaderWhateverPathTheCallerSends() {
        Set<String> warned = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            relayDroppedLocation(Status.SUCCESS_CREATED, "/tenants/t" + i + "/proxy", warned);
        }

        assertEquals(Set.of("Location"), warned,
                "caller-supplied path values must not grow the warning flags");
    }

    @Test
    public void relayShouldLogAtFineWhenALocationOfA200IsDropped() {
        ch.qos.logback.classic.Logger logger = restletLogger();
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (LogCapture logs = new LogCapture()) {
            relayDroppedLocation(Status.SUCCESS_OK, "/log-200");

            assertEquals(List.of(ch.qos.logback.classic.Level.DEBUG),
                    droppedLocationLevels(logs));
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    public void relayedLocationShouldDropALeadingDotDotSegmentWhenTheBaseUriHasNoPath() {
        assertNull(ForwardResponseHeaders.relayedLocation("/../admin", "http://up/items",
                "http://up", incoming()));
    }

    @Test
    public void relayedLocationShouldDropPercentEncodedDotSegments() {
        assertNull(ForwardResponseHeaders.relayedLocation("/api/%2e%2e/%2e%2e/admin",
                "http://up/api/items", "http://up/api", incoming()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/%2E%2E/admin", "/v1/.%2e/admin", "/v1/%2e./admin",
            "/v1/items/%2e/x", "/v1/items/%2E"})
    public void relayedLocationShouldDropAnyEncodedDotSegment(String raw) {
        assertNull(location(raw), raw);
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

    @Test
    public void relayShouldDropChallengesOnTheConsumedAdaptersOwnCredential() {
        Response from = upstreamResponse();
        from.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
        from.getHeaders().add("WWW-Authenticate",
                "Bearer realm=\"billing-internal\", error=\"invalid_token\"");
        Response to = new Response(incoming());

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, false, new HashSet<>());

        assertTrue(to.getChallengeRequests().isEmpty(),
                "a challenge on Ikanos' own credential must not reach the caller");
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

    // --- Fields nominated by Connection (RFC 9110 section 7.6.1) ---

    @Test
    public void relayShouldNotCopyATrustedExtensionHeaderThatConnectionNominates() {
        Response from = upstreamResponse();
        from.getHeaders().add("Connection", "keep-alive, X-REQUEST-ID");
        from.getHeaders().add("X-Request-Id", "r-1");
        from.getHeaders().add("X-Trace", "t-1");

        Response to = relayed(from, List.of("X-Request-Id", "X-Trace"));

        assertNull(to.getHeaders().getFirstValue("X-Request-Id", true));
        assertEquals("t-1", to.getHeaders().getFirstValue("X-Trace", true));
    }

    @Test
    public void relayShouldReadEveryConnectionHeaderLine() {
        Response from = upstreamResponse();
        from.getHeaders().add("Connection", "close");
        from.getHeaders().add("connection", "x-request-id");
        from.getHeaders().add("X-Request-Id", "r-1");

        assertNull(relayed(from, List.of("X-Request-Id")).getHeaders()
                .getFirstValue("X-Request-Id", true));
    }

    @Test
    public void relayShouldNotCopyTrustedCookiesWhenConnectionNominatesSetCookie() {
        Response from = upstreamResponse();
        from.getHeaders().add("Connection", "Set-Cookie");
        from.getCookieSettings().add(new CookieSetting("session", "abc"));

        assertTrue(relayed(from, List.of("set-cookie")).getCookieSettings().isEmpty());
    }

    @Test
    public void relayShouldNotCopyDefaultHeadersThatConnectionNominates() {
        Response from = upstreamResponse();
        from.getHeaders().add("Connection", "Location, Cache-Control, Age, Retry-After");
        from.getHeaders().add("Location", BASE_URI + "/items/42");
        from.getCacheDirectives().add(CacheDirective.noStore());
        from.setAge(30);
        from.setRetryAfter(new Date(System.currentTimeMillis() + 120_000));

        Response to = relayed(from);

        assertNull(to.getLocationRef());
        assertTrue(to.getCacheDirectives().isEmpty());
        assertEquals(0, to.getAge());
        assertNull(to.getRetryAfter());
    }

    @Test
    public void relayShouldRemoveEntityMetadataThatConnectionNominatesButKeepTheContentType() {
        Response from = upstreamResponse();
        from.getHeaders().add("Connection", "ETag, Last-Modified, Content-Location");
        from.getHeaders().add("Content-Location", BASE_URI + "/items/42");
        StringRepresentation entity = new StringRepresentation("{}", MediaType.APPLICATION_JSON);
        entity.setTag(new Tag("v1"));
        entity.setModificationDate(new Date());
        entity.setLocationRef(BASE_URI + "/items/42");
        from.setEntity(entity);

        Response to = relayed(from);

        assertNull(to.getEntity().getTag());
        assertNull(to.getEntity().getModificationDate());
        assertNull(to.getEntity().getLocationRef());
        assertEquals(MediaType.APPLICATION_JSON, to.getEntity().getMediaType());
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
