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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.data.CacheDirective;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Tag;

class StandardRequestHeadersTest {

    private final Request request = new Request(Method.GET, "http://localhost/target");

    @Test
    void setShouldKeepCustomHeaderAsRawHeaderWhenNameIsNotStandard() {
        StandardRequestHeaders.set(request, "X-Trace", "abc");

        assertEquals("abc", request.getHeaders().getFirstValue("X-Trace", true));
    }

    @Test
    void setShouldReplaceAcceptWhenAcceptIsSetTwice() {
        StandardRequestHeaders.set(request, "Accept", "application/xml");
        StandardRequestHeaders.set(request, "accept", "application/json");

        assertEquals(1, request.getClientInfo().getAcceptedMediaTypes().size());
        assertEquals(MediaType.APPLICATION_JSON,
                request.getClientInfo().getAcceptedMediaTypes().get(0).getMetadata());
    }

    @Test
    void setShouldFailWithoutEchoingValueWhenDateCannotBeParsed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "If-Modified-Since", "yesterday"));

        assertTrue(error.getMessage().contains("If-Modified-Since"), error.getMessage());
        assertFalse(error.getMessage().contains("yesterday"),
                "the message must not echo the value, which may be a credential");
        assertNull(error.getCause(), "a cause could carry the value in its own message");
        assertNull(request.getConditions().getModifiedSince());
    }

    @Test
    void setShouldFailWithoutEchoingValueWhenNumberCannotBeParsed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "Max-Forwards", "secret-42"));

        assertFalse(error.getMessage().contains("secret-42"), error.getMessage());
        assertNull(error.getCause(), "NumberFormatException's message echoes the value");
    }

    @Test
    void setShouldFailWhenIfMatchTagIsNotQuoted() {
        // Restlet's Tag.parse returns null for an unquoted tag and the reader skips it, so the
        // header would otherwise go out empty and the condition would be lost
        assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "If-Match", "v1"));
        assertTrue(request.getConditions().getMatch().isEmpty());
    }

    @Test
    void setShouldFailWhenOnlySomeIfNoneMatchTagsCanBeParsed() {
        assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "If-None-Match", "\"v1\", v2"));
        assertTrue(request.getConditions().getNoneMatch().isEmpty(),
                "a partial parse must not be kept");
    }

    @Test
    void setShouldFailWhenRangeUnitIsNotBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "Range", "items=0-9"));
    }

    @Test
    void setShouldWriteAuthorizationVerbatimWhenAuthorizationIsSet() {
        StandardRequestHeaders.set(request, "Authorization", "Token abc");

        assertNotNull(request.getChallengeResponse());
        assertEquals("Token abc", request.getChallengeResponse().getIdentifier());
    }

    @Test
    void setShouldKeepEveryCookieWhenValueHasSeveralCookies() {
        StandardRequestHeaders.set(request, "Cookie", "a=1; b=2");

        assertEquals(2, request.getCookies().size());
        assertEquals("2", request.getCookies().getFirstValue("b"));
    }

    @Test
    void setShouldMergeCookiesByNameWhenCookieIsSetTwice() {
        StandardRequestHeaders.set(request, "Cookie", "session=abc");
        StandardRequestHeaders.set(request, "Cookie", "lang=en; session=xyz");

        assertEquals(2, request.getCookies().size(), request.getCookies().toString());
        assertEquals("xyz", request.getCookies().getFirstValue("session"),
                "the later source wins for a cookie of the same name");
        assertEquals("en", request.getCookies().getFirstValue("lang"));
    }

    @Test
    void setShouldKeepCallerOrderAndRepeatsWhenCookieValueRepeatsAName() {
        StandardRequestHeaders.set(request, "Cookie", "sid=A; theme=x; sid=B");

        assertEquals(List.of("sid=A", "theme=x", "sid=B"), request.getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue()).toList(),
                "only cookies set by an earlier source are replaced, never the caller's own");
    }

    @Test
    void setShouldAcceptCookieWhenSameNameIsRepeatedWithQuotes() {
        StandardRequestHeaders.set(request, "Cookie", "a=1; a=\"1\"");

        assertEquals(2, request.getCookies().size(), request.getCookies().toString());
    }

    @Test
    void setShouldAcceptLegacyCookieWhenValueHasVersionAttribute() {
        StandardRequestHeaders.set(request, "Cookie", "$Version=1; a=1");

        assertEquals("1", request.getCookies().getFirstValue("a"));
    }

    @Test
    void setShouldKeepWeakAndStrongTagWhenBothHaveTheSameOpaqueValue() {
        // Tag.equals ignores weakness, so Restlet's reader would drop the second one
        StandardRequestHeaders.set(request, "If-None-Match", "W/\"a\", \"a\"");

        assertEquals(2, request.getConditions().getNoneMatch().size());
    }

    @Test
    void setShouldRefuseTagWhenItContainsAComma() {
        // Documented limitation: Restlet's TagReader splits on commas inside quotes, so such an
        // entity tag cannot be read; it is refused rather than sent truncated
        assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "If-Match", "\"a,b\""));
    }

    @Test
    void setShouldAcceptViaWhenCommentContainsAComma() {
        StandardRequestHeaders.set(request, "Via", "1.1 proxy (x, y)");

        assertEquals(1, request.getRecipientsInfo().size());
    }

    @Test
    void elementsShouldCountQuotedCommaAsPartOfOneElement() {
        assertEquals(2, StandardRequestHeaders.elements("\"a,b\", \"c\"",
                StandardRequestHeaders.Syntax.LIST));
    }

    @Test
    void elementsShouldSkipEmptyElements() {
        assertEquals(2, StandardRequestHeaders.elements("a, , b,",
                StandardRequestHeaders.Syntax.LIST));
    }

    @Test
    void elementsShouldCountRepeatedElements() {
        assertEquals(2, StandardRequestHeaders.elements("a, a",
                StandardRequestHeaders.Syntax.LIST));
    }

    @Test
    void elementsShouldSplitOnSemicolonWhenSyntaxIsCookie() {
        assertEquals(2, StandardRequestHeaders.elements("a=1; b=2",
                StandardRequestHeaders.Syntax.COOKIE));
    }

    @Test
    void elementsShouldSkipDollarAttributesWhenSyntaxIsCookie() {
        assertEquals(1, StandardRequestHeaders.elements("$Version=1; a=1; $Path=/",
                StandardRequestHeaders.Syntax.COOKIE));
    }

    @Test
    void elementsShouldKeepEscapedQuoteInsideQuotedString() {
        assertEquals(2, StandardRequestHeaders.elements("\"a\\\"b, c\", d",
                StandardRequestHeaders.Syntax.LIST));
    }

    @Test
    void elementsShouldCloseQuotedStringAfterEscapedBackslash() {
        // "a\\" is a quoted string ending with one escaped backslash, then ", b" is a new element
        assertEquals(2, StandardRequestHeaders.elements("\"a\\\\\", b",
                StandardRequestHeaders.Syntax.LIST));
    }

    @Test
    void elementsShouldTreatCommentAsOneElementWhenSyntaxIsVia() {
        assertEquals(2, StandardRequestHeaders.elements("1.1 proxy (x, y), 1.0 other",
                StandardRequestHeaders.Syntax.VIA));
    }

    @Test
    void setShouldKeepQualityWhenAcceptLanguageHasQValues() {
        StandardRequestHeaders.set(request, "Accept-Language", "fr-CA, en;q=0.5");

        assertEquals(2, request.getClientInfo().getAcceptedLanguages().size());
        assertEquals(Language.valueOf("en"),
                request.getClientInfo().getAcceptedLanguages().get(1).getMetadata());
        assertEquals(0.5f, request.getClientInfo().getAcceptedLanguages().get(1).getQuality());
    }

    @Test
    void setShouldKeepWeakTagWhenIfNoneMatchHasOne() {
        StandardRequestHeaders.set(request, "If-None-Match", "W/\"v1\"");

        assertEquals(List.of(new Tag("v1", true)), request.getConditions().getNoneMatch());
    }

    @Test
    void setShouldKeepWildcardWhenIfMatchIsStar() {
        StandardRequestHeaders.set(request, "If-Match", "*");

        assertEquals(List.of(Tag.ALL), request.getConditions().getMatch());
    }

    @Test
    void setShouldKeepEveryRangeWhenRangeHasSeveral() {
        StandardRequestHeaders.set(request, "Range", "bytes=0-99, 200-299");

        assertEquals(2, request.getRanges().size());
    }

    @Test
    void setShouldKeepEveryDirectiveWhenCacheControlHasSeveral() {
        StandardRequestHeaders.set(request, "Cache-Control", "no-cache, max-age=0");

        // Compared by name and value: a parsed max-age has digit=false, unlike maxAge(0)
        assertEquals(List.of("no-cache=null", "max-age=0"), request.getCacheDirectives().stream()
                .map(CacheDirective::toString).map(d -> d.replaceAll(
                        ".*name=([^,]+), value=([^\\]]+)\\]", "$1=$2"))
                .toList());
    }

    @Test
    void setIfRangeShouldSetDateWhenValueIsAnHttpDate() {
        StandardRequestHeaders.setIfRange(request, "Tue, 10 Nov 2026 08:00:00 GMT");

        assertNotNull(request.getConditions().getRangeDate());
        assertNull(request.getConditions().getRangeTag());
    }

    @Test
    void setIfRangeShouldSetTagWhenValueIsQuoted() {
        StandardRequestHeaders.setIfRange(request, "\"v1\"");

        assertEquals(new Tag("v1", false), request.getConditions().getRangeTag());
        assertNull(request.getConditions().getRangeDate());
    }

    @Test
    void notSendableReasonShouldNameTheConnectorWhenHeaderIsHost() {
        assertEquals("is set by the HTTP connector",
                StandardRequestHeaders.notSendableReason("host"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Date", "Expect"})
    void notSendableReasonShouldSayRestletSetsItWhenHeaderIsDateOrExpect(String name) {
        assertEquals("is set by Restlet itself", StandardRequestHeaders.notSendableReason(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Transfer-Encoding", "TE", "Upgrade", "Trailer", "Keep-Alive",
        "Proxy-Connection", "Connection"})
    void notSendableReasonShouldSayHopByHopWhenHeaderIsHopByHop(String name) {
        // RFC 9110 §7.6.1: these apply to a single connection and are never forwarded
        String reason = StandardRequestHeaders.notSendableReason(name);

        assertNotNull(reason, name);
        assertTrue(reason.contains("hop-by-hop"), reason);
    }

    @Test
    void notSendableReasonShouldNameTheConnectorWhenHeaderIsAcceptEncoding() {
        // The Jetty client sends its own Accept-Encoding and decodes the response: a copied value
        // went out twice ("gzip,gzip") and the caller still got a decoded body
        assertNotNull(StandardRequestHeaders.notSendableReason("Accept-Encoding"));
    }

    @Test
    void notSendableReasonShouldBeNullWhenHeaderCanBeSent() {
        assertNull(StandardRequestHeaders.notSendableReason("If-None-Match"));
        assertNull(StandardRequestHeaders.notSendableReason("X-Trace"));
        assertNull(StandardRequestHeaders.notSendableReason("Content-Type"));
    }

    @Test
    void isEntityHeaderShouldBeTrueWhenHeaderIsContentType() {
        assertTrue(StandardRequestHeaders.isEntityHeader("content-type"));
        assertFalse(StandardRequestHeaders.isEntityHeader("Accept"));
    }
}
