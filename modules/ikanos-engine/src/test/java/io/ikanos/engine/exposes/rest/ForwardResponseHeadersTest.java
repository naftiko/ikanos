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

    @ParameterizedTest
    @CsvSource({
            "http://upstream:9090/v1/items//42, /proxy/items//42",
            "/v1/items//42, /proxy/items//42",
            "items//42, /proxy/items//42",
            "http://upstream:9090/v1/items//42/../43, /proxy/items//43",
            "http://upstream:9090/v1/items/./42/, /proxy/items/42/"})
    public void relayedLocationShouldKeepEmptyPathSegments(String raw, String expected) {
        assertEquals(expected, location(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http:/admin", "HTTP:/admin", "https:/admin", "http:admin",
            "urn:example:admin", "mailto:ops@example.com"})
    public void relayedLocationShouldDropASchemeQualifiedLocationWithoutAHost(String raw) {
        assertNull(location(raw), raw);
    }

    @Test
    public void relayShouldKeepEmptyPathSegmentsOfLocationAndContentLocation() {
        Response from = upstreamResponse();
        from.getHeaders().add("Location", BASE_URI + "/items//42");
        from.getHeaders().add("Content-Location", BASE_URI + "/items//42");
        StringRepresentation entity = new StringRepresentation("{}");
        entity.setLocationRef(BASE_URI + "/items//42");
        from.setEntity(entity);

        Response relayed = relayed(from);

        assertEquals("/proxy/items//42", relayed.getLocationRef().getTargetRef().toString());
        assertEquals("/proxy/items//42",
                relayed.getEntity().getLocationRef().getTargetRef().toString());
    }

    @Test
    public void relayShouldDropAHostlessLocationAndContentLocation() {
        Response from = upstreamResponse();
        from.getHeaders().add("Location", "http:/admin");
        from.getHeaders().add("Content-Location", "http:/admin");
        StringRepresentation entity = new StringRepresentation("{}");
        entity.setLocationRef("http:/admin");
        from.setEntity(entity);

        Response relayed = relayed(from);

        assertNull(relayed.getLocationRef());
        assertNull(relayed.getEntity().getLocationRef());
    }

    @ParameterizedTest
    @CsvSource({
            "http://upstream:9090/v1//evil.example/x, http://upstream:9090/v1",
            "/v1//evil.example/x, http://upstream:9090/v1",
            ".//evil.example/x, http://upstream:9090/v1",
            "http://upstream:9090/v1//, http://upstream:9090/v1",
            "http://upstream:9090//evil.example/x, http://upstream:9090"})
    public void relayedLocationShouldDropALocationThatWouldBeSchemeRelativeUnderTheRootRoute(
            String raw, String baseUri) {
        assertNull(ForwardResponseHeaders.relayedLocation(raw, baseUri + "/items", baseUri,
                incoming("http://localhost:8080/items", "items")), raw);
    }

    @Test
    public void relayedLocationShouldKeepEmptyPathSegmentsAfterTheFirstUnderTheRootRoute() {
        assertEquals("/items//42", ForwardResponseHeaders.relayedLocation(
                BASE_URI + "/items//42", BASE_URI + "/items", BASE_URI,
                incoming("http://localhost:8080/items", "items")));
    }

    @Test
    public void relayShouldDropASchemeRelativeLocationAndContentLocationUnderTheRootRoute() {
        Response from = upstreamResponse();
        from.getHeaders().add("Location", BASE_URI + "//evil.example/x");
        from.getHeaders().add("Content-Location", BASE_URI + "//evil.example/x");
        StringRepresentation entity = new StringRepresentation("{}");
        entity.setLocationRef(BASE_URI + "//evil.example/x");
        from.setEntity(entity);

        Response relayed = relayed(from, List.of(),
                incoming("http://localhost:8080/items", "items"));

        assertNull(relayed.getLocationRef());
        assertNull(relayed.getEntity().getLocationRef());
    }

    private static Request incomingWithoutPath(String uri) {
        return new Request(Method.GET, uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://upstream:9090/v1/items/42", "/v1/items", "items/42"})
    public void relayedLocationShouldDropALocationBelowTheBaseUriUnderAResourceWithoutPath(
            String raw) {
        assertNull(ForwardResponseHeaders.relayedLocation(raw, BASE_URI, BASE_URI,
                incomingWithoutPath("http://localhost:8080/status")), raw);
    }

    @ParameterizedTest
    @CsvSource({"http://upstream:9090/v1, /status", "http://upstream:9090/v1?page=2, /status?page=2",
            "http://upstream:9090/v1/, /status", "/v1/, /status"})
    public void relayedLocationShouldRewriteTheBaseUriUnderAResourceWithoutPath(String raw,
            String expected) {
        assertEquals(expected, ForwardResponseHeaders.relayedLocation(raw, BASE_URI, BASE_URI,
                incomingWithoutPath("http://localhost:8080/status")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://upstream:9090/", "http://upstream:9090", "/"})
    public void relayedLocationShouldRewriteTheRootBaseUriUnderAResourceWithoutPath(String raw) {
        assertEquals("/status", ForwardResponseHeaders.relayedLocation(raw,
                "http://upstream:9090", "http://upstream:9090",
                incomingWithoutPath("http://localhost:8080/status")), raw);
    }

    @Test
    public void relayedLocationShouldKeepTheTrailingSlashOfAResourceWithoutPath() {
        assertEquals("/status/", ForwardResponseHeaders.relayedLocation(BASE_URI, BASE_URI,
                BASE_URI, incomingWithoutPath("http://localhost:8080/status/")),
                "the exposed route /status/ is written as routed");
    }

    @Test
    public void relayedLocationShouldKeepTheTextAfterPathInTheResourcePath() {
        assertEquals("/proxy/items/42/file", ForwardResponseHeaders.relayedLocation(
                BASE_URI + "/items/42", BASE_URI + "/items", BASE_URI,
                incoming("http://localhost:8080/proxy/items/file", "items"),
                new ForwardResponseHeaders.Exposure("/proxy/{{path}}/file", Set.of(), false)),
                "/proxy/items/42/file forwards to /v1/items/42 under /proxy/{{path}}/file");
    }

    @Test
    public void relayedLocationShouldDropALocationWhenTheTextAfterPathHasAVariable() {
        assertNull(ForwardResponseHeaders.relayedLocation(BASE_URI + "/items/42",
                BASE_URI + "/items", BASE_URI,
                incoming("http://localhost:8080/proxy/items/x", "items"),
                new ForwardResponseHeaders.Exposure("/proxy/{{path}}/{{id}}", Set.of(), false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://upstream:9090/v1/items/42?api_key=s3cret",
            "/v1/items/42?page=2&api_key=s3cret", "?api_key=s3cret",
            "http://other.example/x?api%5Fkey=s3cret", "//other.example/x?api_key=s3cret"})
    public void relayedLocationShouldDropALocationThatCarriesTheAdaptersQueryCredential(
            String raw) {
        assertNull(ForwardResponseHeaders.relayedLocation(raw, BASE_URI + "/items", BASE_URI,
                incoming(), new ForwardResponseHeaders.Exposure(null, Set.of("api_key"), false)), raw);
    }

    @Test
    public void injectedQueryNamesShouldNameWhatTheEngineAddedToTheUpstreamQuery() {
        assertEquals(Set.of("api_key", "tenant"), ForwardResponseHeaders.injectedQueryNames(
                "page=2&api%5Fkey=s3cret&tenant=t1", "page=2"),
                "an apikey and an adapter input are added by the engine, page by the caller");
    }

    @Test
    public void injectedQueryNamesShouldNameAParameterWhoseValueTheEngineReplacedOrAdded() {
        assertEquals(Set.of("api_key"), ForwardResponseHeaders.injectedQueryNames(
                "api_key=ignored&api_key=s3cret&page=1", "api_key=ignored&page=1"),
                "a caller parameter of the same name must not unprotect the adapter's credential");
        assertEquals(Set.of("api_key"), ForwardResponseHeaders.injectedQueryNames(
                "api_key=s3cret", "api_key=ignored"));
    }

    @Test
    public void injectedQueryNamesShouldNotNameAParameterTheCallerSentAsIs() {
        assertTrue(ForwardResponseHeaders.injectedQueryNames("q=a%20b&page=1", "page=1&q=a%20b")
                .isEmpty());
        assertTrue(ForwardResponseHeaders.injectedQueryNames("q=a%2Bb", "q=a+b").isEmpty());
    }

    /** The engine encodes a space as %20 (RFC 3986): a caller's plus sign is a literal plus. */
    @Test
    public void injectedQueryNamesShouldNameAParameterWhoseSpaceTheCallerSentAsAPlus() {
        assertEquals(Set.of("api_key"),
                ForwardResponseHeaders.injectedQueryNames("api_key=a%20b", "api_key=a+b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://upstream:9090/v1/items/42?API_KEY=s3cret",
            "http://upstream:9090/v1/items/42?Api%5FKey=s3cret",
            "https://login.example/?return_url=https%3A%2F%2Fapi.example%2Fx%3Fapi_key%3Ds3cret",
            "https://login.example/?return_url=https%253A%252F%252Fapi.example%252Fx%253FAPI_KEY"
                    + "%253Ds3cret",
            "https://login.example/?next=/x?api_key=s3cret",
            "/v1/items/42?state=a%3D1%26api_key%3Ds3cret",
            "https://login.example/?n=%25252525252525252525"})
    public void relayedLocationShouldDropAnInjectedNameRenamedOrNestedInAValue(String raw) {
        assertNull(ForwardResponseHeaders.relayedLocation(raw, BASE_URI + "/items", BASE_URI,
                incoming(), new ForwardResponseHeaders.Exposure(null, Set.of("api_key"),
                        false)));
    }

    @Test
    public void relayedLocationShouldKeepANestedUrlWithoutAnInjectedName() {
        String raw = "https://login.example/?return_url=https%3A%2F%2Fapi.example%2Fx%3Fpage%3D2";
        assertEquals(raw, ForwardResponseHeaders.relayedLocation(raw, BASE_URI + "/items",
                BASE_URI, incoming(), new ForwardResponseHeaders.Exposure(null,
                        Set.of("api_key"), false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Location", "Content-Location"})
    public void relayShouldDropALocationEchoingACredentialWhoseNameTheCallerAlsoSent(
            String header) {
        Response from = new Response(new Request(Method.POST,
                BASE_URI + "/items?api_key=ignored&api_key=s3cret"));
        from.getHeaders().add(header, BASE_URI + "/items/42?api_key=s3cret");
        from.setEntity(new StringRepresentation("{}"));
        Request request = incoming("http://localhost:8080/proxy/items?api_key=ignored", "items");
        Response to = new Response(request);
        to.setEntity(from.getEntity());

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null,
                        ForwardResponseHeaders.injectedQueryNames(
                                from.getRequest().getResourceRef().getQuery(),
                                request.getResourceRef().getQuery()),
                        false));

        assertNull("Location".equals(header) ? to.getLocationRef()
                : to.getEntity().getLocationRef(), header);
    }

    // --- Listed headers that carry a URI: the same protection as Location ---

    private static Response relayedListed(String header, String value, Set<String> injected) {
        Response from = new Response(new Request(Method.GET,
                BASE_URI + "/items?api_key=s3cret"));
        from.getHeaders().add(header, value);
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));
        ForwardResponseHeaders.relay(from, to, List.of(header), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null, injected, false));
        return to;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Link | <http://upstream:9090/v1/items?api_key=s3cret&page=2>; rel=\"next\"",
            "Link | <http://upstream:9090/v1/items?page=2>; rel=\"next\","
                    + " <http://upstream:9090/v1/items?API_KEY=s3cret>; rel=\"last\"",
            "Refresh | 0; url=http://upstream:9090/v1/items?api_key=s3cret",
            "X-Next-Page | http%3A%2F%2Fupstream%3A9090%2Fv1%2Fitems%3Fapi_key%3Ds3cret",
            "X-Next-Page | /v1/items?return=%2Fv1%2Fitems%3Fapi_key%3Ds3cret",
            "X-Next-Query | api_key=s3cret&page=2"})
    public void relayShouldDropAListedHeaderThatCarriesAnInjectedQueryParameter(String header,
            String value) {
        Response to = relayedListed(header, value, Set.of("api_key"));

        assertNull(to.getHeaders().getFirstValue(header, true),
                "#1002: a listed " + header + " must not hand the caller the adapter's query"
                        + " credential");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Link | <http://upstream:9090/v1/items?page=2>; rel=\"next\"",
            "Refresh | 5; url=http://upstream:9090/v1/items?page=2",
            "X-Request-Id | r-1"})
    public void relayShouldRelayAListedHeaderWithoutAnInjectedQueryParameter(String header,
            String value) {
        assertEquals(value,
                relayedListed(header, value, Set.of("api_key")).getHeaders()
                        .getFirstValue(header, true));
    }

    @Test
    public void relayShouldRelayAListedLinkAsIsWhenTheEngineAddedNoQueryParameter() {
        String value = "<http://upstream:9090/v1/items?api_key=caller&page=2>; rel=\"next\"";

        assertEquals(value,
                relayedListed("Link", value, Set.of()).getHeaders().getFirstValue("Link", true));
    }

    @Test
    public void relayShouldDropOnlyTheLineOfAListedHeaderThatCarriesAnInjectedQueryParameter() {
        Response from = new Response(new Request(Method.GET, BASE_URI + "/items?api_key=s3cret"));
        from.getHeaders().add("Link", "<http://upstream:9090/v1/items?page=2>; rel=\"next\"");
        from.getHeaders().add("Link",
                "<http://upstream:9090/v1/items?api_key=s3cret>; rel=\"self\"");
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of("Link"), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null, Set.of("api_key"), false));

        assertEquals("<http://upstream:9090/v1/items?page=2>; rel=\"next\"",
                to.getHeaders().getValues("Link"));
    }

    @Test
    public void relayShouldDropAListedHeaderThatCarriesAnAmzQueryForASigningAdapter() {
        Response from = new Response(new Request(Method.GET, BASE_URI + "/items"));
        from.getHeaders().add("Link",
                "<http://bucket.s3.example/k?X-Amz-Security-Token=tok>; rel=\"alternate\"");
        Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

        ForwardResponseHeaders.relay(from, to, List.of("Link"), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null, Set.of(), false, true));

        assertNull(to.getHeaders().getFirstValue("Link", true));
    }

    // --- Why a location is dropped ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://upstream:9090/secret-admin-path | OUTSIDE_BASE",
            "/internal/x | OUTSIDE_BASE",
            "http://upstream:8443/v1/items/42 | OUTSIDE_BASE",
            "http:/admin | NO_HOST",
            "urn:isbn:0451450523 | NO_HOST",
            "/v1/%2e%2e/admin | DOT_SEGMENT",
            "/v1/items/42?api_key=s3cret | PROTECTED_QUERY",
            "http://upstream:9090/v1 | NO_ROUTE",
            "'http://up stream/v1' | UNPARSEABLE"})
    public void relayLocationShouldNameWhyALocationIsDropped(String raw, String reason) {
        ForwardResponseHeaders.Relayed relayed = ForwardResponseHeaders.relayLocation(raw,
                BASE_URI + "/items", BASE_URI,
                incoming("http://localhost:8080/proxy/items", "items"),
                new ForwardResponseHeaders.Exposure(null, Set.of("api_key"), false));

        assertNull(relayed.value(), raw);
        assertEquals(ForwardResponseHeaders.Drop.valueOf(reason), relayed.dropped(), raw);
    }

    @Test
    public void relayLocationShouldGiveNoReasonForARelayedLocation() {
        ForwardResponseHeaders.Relayed relayed = ForwardResponseHeaders.relayLocation(
                "/v1/items/42", BASE_URI + "/items", BASE_URI,
                incoming("http://localhost:8080/proxy/items", "items"),
                new ForwardResponseHeaders.Exposure(null, Set.of("api_key"), false));

        assertEquals("/proxy/items/42", relayed.value());
        assertNull(relayed.dropped());
    }

    @Test
    public void relayShouldLogWhyALocationIsDroppedButNotItsValue() {
        try (LogCapture logs = new LogCapture()) {
            Response from = new Response(new Request(Method.POST,
                    BASE_URI + "/items?api_key=s3cret"));
            from.setStatus(Status.SUCCESS_CREATED);
            from.getHeaders().add("Location", BASE_URI + "/items/42?api_key=s3cret");
            Response to = new Response(incoming("http://localhost:8080/proxy/items", "items"));

            ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, true, new HashSet<>(),
                    new ForwardResponseHeaders.Exposure(null, Set.of("api_key"), false));

            String all = String.join(" | ", logs.messages());
            assertTrue(all.contains(ForwardResponseHeaders.Drop.PROTECTED_QUERY.reason()), all);
            assertFalse(all.contains("baseUri"), "the reason must be the actual one: " + all);
            assertFalse(all.contains("s3cret"), all);
        }
    }

    // --- Location: a signing (awsSigV4) adapter's query-string signature and session token ---

    private static ForwardResponseHeaders.Exposure signed() {
        return new ForwardResponseHeaders.Exposure(null, Set.of(), false, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://upstream:9090/v1/items/42?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIDEXAMPLE%2F20261117%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924",
            "https://bucket.s3.amazonaws.com/report.pdf?X-Amz-Security-Token=FwoGZXIvYXdzEXAMPLE",
            "/v1/items/42?x-amz-signature=abc",
            "?X-Amz-Security-Token=FwoGZXIvYXdzEXAMPLE",
            "//cdn.example/x?X%2DAmz%2DSignature=abc",
            "https://login.example/?next=https%3A%2F%2Fb.s3.amazonaws.com%2Fk%3FX-Amz-Signature"
                    + "%3Dabc"})
    public void relayedLocationShouldDropAnAmzQueryWhenTheAdapterSignsWithAwsSigV4(String raw) {
        assertNull(ForwardResponseHeaders.relayedLocation(raw, BASE_URI + "/items", BASE_URI,
                incoming(), signed()));
    }

    @Test
    public void relayedLocationShouldKeepAnAmzQueryWhenTheAdapterDoesNotSign() {
        assertEquals("https://bucket.s3.amazonaws.com/report.pdf?X-Amz-Signature=abc",
                ForwardResponseHeaders.relayedLocation(
                        "https://bucket.s3.amazonaws.com/report.pdf?X-Amz-Signature=abc",
                        BASE_URI + "/items", BASE_URI, incoming(),
                        new ForwardResponseHeaders.Exposure(null, Set.of(), false)));
    }

    @Test
    public void relayedLocationShouldKeepAnAmzQueryTheCallerSentWhenTheAdapterSigns() {
        assertEquals("/proxy/items/42?X-Amz-Meta-Note=draft",
                ForwardResponseHeaders.relayedLocation(
                        "http://upstream:9090/v1/items/42?X-Amz-Meta-Note=draft",
                        BASE_URI + "/items?X-Amz-Meta-Note=draft", BASE_URI,
                        incoming("http://localhost:8080/proxy/items?X-Amz-Meta-Note=draft",
                                "items"),
                        signed()));
        assertEquals("/proxy/items/42?page=2", ForwardResponseHeaders.relayedLocation(
                "http://upstream:9090/v1/items/42?page=2", BASE_URI + "/items", BASE_URI,
                incoming(), signed()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Location", "Content-Location"})
    public void relayShouldDropALocationEchoingTheSessionTokenWhenTheAdapterSigns(
            String header) {
        Response from = upstreamResponse();
        from.setStatus(Status.REDIRECTION_FOUND);
        from.getHeaders().add(header,
                BASE_URI + "/items/42?X-Amz-Security-Token=FwoGZXIvYXdzEXAMPLE");
        from.setEntity(new StringRepresentation("{}"));
        Response to = new Response(incoming());
        to.setEntity(from.getEntity());

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, false, new HashSet<>(),
                signed());

        assertNull("Location".equals(header) ? to.getLocationRef()
                : to.getEntity().getLocationRef(), header);
    }

    @Test
    public void injectedQueryNamesShouldBeEmptyWithoutAnUpstreamQuery() {
        assertTrue(ForwardResponseHeaders.injectedQueryNames(null, "page=1").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"/proxy/item-{{path}}, http://localhost:8080/proxy/item-abc, abc, /proxy/item-def",
            "/proxy//{{path}}, http://localhost:8080/proxy//abc, abc, /proxy//def"})
    public void relayedLocationShouldKeepThePrefixBeforePathAsRouted(String template,
            String uri, String forwarded, String expected) {
        assertEquals(expected, ForwardResponseHeaders.relayedLocation(BASE_URI + "/def",
                BASE_URI + "/abc", BASE_URI, incoming(uri, forwarded),
                new ForwardResponseHeaders.Exposure(template, Set.of(), false)), template);
    }

    @Test
    public void relayedLocationShouldKeepALocationWhoseQueryHasNoCredential() {
        assertEquals("/proxy/items/42?page=2", ForwardResponseHeaders.relayedLocation(
                BASE_URI + "/items/42?page=2", BASE_URI + "/items", BASE_URI, incoming(),
                new ForwardResponseHeaders.Exposure(null, Set.of("api_key"), false)));
    }

    @Test
    public void relayedLocationShouldKeepTheUpstreamSchemeOfASchemeRelativeLocation() {
        assertEquals("https://cdn.example/download?token=t",
                ForwardResponseHeaders.relayedLocation("//cdn.example/download?token=t",
                        "https://upstream:9090/v1/items", "https://upstream:9090/v1",
                        incoming()),
                "the caller must not resolve it against the scheme it reached the gateway with");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/%2e%2e/../admin", "/v1/items/%2E%2e/../../admin",
            "http://upstream:9090/v1/.%2e/../admin"})
    public void relayedLocationShouldDropAnEncodedDotSegmentThatAPlainDotDotRemoves(String raw) {
        assertNull(location(raw), raw);
    }

    @Test
    public void relayShouldMarkTheAnswerToAnAuthenticatedCallerPrivate() {
        Response from = upstreamResponse();
        from.getCacheDirectives().add(CacheDirective.publicInfo());
        from.getCacheDirectives().add(CacheDirective.maxAge(60));
        from.getCacheDirectives().add(CacheDirective.sharedMaxAge(120));
        Response to = new Response(incoming());

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null, Set.of(), true));

        assertEquals(List.of(CacheDirective.maxAge(60), CacheDirective.privateInfo()),
                to.getCacheDirectives(),
                "a shared cache must not serve it to a caller without the gateway credential");
    }

    @Test
    public void relayShouldMarkTheAnswerToAnAuthenticatedCallerPrivateWithoutDirectives() {
        Response to = new Response(incoming());

        ForwardResponseHeaders.relay(upstreamResponse(), to, List.of(), BASE_URI, true,
                new HashSet<>(), new ForwardResponseHeaders.Exposure(null, Set.of(), true));

        assertEquals(List.of(CacheDirective.privateInfo()), to.getCacheDirectives());
    }

    @Test
    public void relayShouldKeepNoStoreForAnAuthenticatedCaller() {
        Response from = upstreamResponse();
        from.getCacheDirectives().add(CacheDirective.noStore());
        Response to = new Response(incoming());

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null, Set.of(), true));

        assertEquals(List.of(CacheDirective.noStore()), to.getCacheDirectives());
    }

    @Test
    public void relayShouldAddNoDirectiveToANotModifiedWithoutDirectives() {
        Response from = upstreamResponse();
        from.setStatus(Status.REDIRECTION_NOT_MODIFIED);
        Response to = new Response(incoming());
        to.setStatus(Status.REDIRECTION_NOT_MODIFIED);

        ForwardResponseHeaders.relay(from, to, List.of(), BASE_URI, true, new HashSet<>(),
                new ForwardResponseHeaders.Exposure(null, Set.of(), true));

        assertTrue(to.getCacheDirectives().isEmpty(),
                "a cache would replace its stored Cache-Control with the 304's");
    }

    @Test
    public void relayShouldNotMarkTheAnswerPrivateWhenTheExposedAdapterHasNoAuthentication() {
        Response from = upstreamResponse();
        from.getCacheDirectives().add(CacheDirective.publicInfo());

        assertEquals(List.of(CacheDirective.publicInfo()), relayed(from).getCacheDirectives());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://upstream:9090/v1", "http://upstream:9090/v1/",
            "http://upstream:9090/v1?page=2", "/v1", "/v1/"})
    public void relayedLocationShouldDropTheBaseUriUnderAResourceWithPath(String raw) {
        // /proxy and /proxy/ match no route /proxy/{path}: the forwarded path cannot be empty
        assertNull(ForwardResponseHeaders.relayedLocation(raw, BASE_URI + "/items", BASE_URI,
                incoming()), raw);
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

    @Test
    public void relayShouldCopyTheUpstreamDate() {
        Response from = upstreamResponse();
        Date date = new Date(1_672_653_600_000L);
        from.setDate(date);

        assertEquals(date, relayed(from).getDate());
    }

    @Test
    public void relayShouldNotSetADateWhenTheUpstreamSentNone() {
        assertNull(relayed(upstreamResponse()).getDate());
    }

    private static Response notModified(long expiresInSeconds) {
        Response from = upstreamResponse();
        from.setStatus(Status.REDIRECTION_NOT_MODIFIED);
        Date date = new Date(1_672_653_600_000L);
        from.setDate(date);
        StringRepresentation entity = new StringRepresentation("");
        entity.setExpirationDate(new Date(date.getTime() + expiresInSeconds * 1000));
        from.setEntity(entity);
        from.getCacheDirectives().add(CacheDirective.mustRevalidate());
        return from;
    }

    @Test
    public void relayShouldSendTheExpiresOfANotModifiedAsMaxAge() {
        Response relayed = relayed(notModified(60));

        assertEquals(2, relayed.getCacheDirectives().size());
        assertEquals("must-revalidate", relayed.getCacheDirectives().get(0).getName());
        assertEquals("max-age", relayed.getCacheDirectives().get(1).getName());
        assertEquals("60", relayed.getCacheDirectives().get(1).getValue());
    }

    @Test
    public void relayShouldSendAnExpiredNotModifiedAsMaxAgeZero() {
        assertEquals("0", relayed(notModified(-30)).getCacheDirectives().get(1).getValue());
    }

    @Test
    public void relayShouldNotAddCacheControlToANotModifiedThatHasNone() {
        // A cache replaces its stored Cache-Control (no-cache, for example) with the one of the
        // 304, so a synthesized max-age would let it reuse the response without revalidation
        Response from = notModified(60);
        from.getCacheDirectives().clear();

        assertTrue(relayed(from).getCacheDirectives().isEmpty());
    }

    @Test
    public void relayShouldKeepTheMaxAgeOfANotModified() {
        Response from = notModified(60);
        from.getCacheDirectives().add(CacheDirective.maxAge(10));

        Response relayed = relayed(from);

        assertEquals(2, relayed.getCacheDirectives().size());
        assertEquals("10", relayed.getCacheDirectives().get(1).getValue());
    }

    @Test
    public void relayShouldNotTurnExpiresIntoMaxAgeOutsideANotModified() {
        Response from = notModified(60);
        from.setStatus(Status.SUCCESS_OK);

        assertEquals(1, relayed(from).getCacheDirectives().size());
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
    @ValueSource(strings = {"Server", "Expect", "Via", "Warning", "Access-Control-Allow-Origin",
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
