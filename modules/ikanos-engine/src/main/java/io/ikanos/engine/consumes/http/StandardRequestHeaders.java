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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ClientInfo;
import org.restlet.data.Method;
import org.restlet.data.Tag;
import org.restlet.engine.header.CacheDirectiveReader;
import org.restlet.engine.header.CookieReader;
import org.restlet.engine.header.HeaderReader;
import org.restlet.engine.header.PreferenceReader;
import org.restlet.engine.header.RangeReader;
import org.restlet.engine.header.RecipientInfoReader;
import org.restlet.engine.header.StringReader;
import org.restlet.engine.header.TagReader;
import org.restlet.engine.header.WarningReader;

/**
 * Sets a header on an outgoing request, including a standard HTTP header (#1001).
 *
 * <p><b>Why this exists.</b> Restlet 3 refuses a standard header ({@code Accept},
 * {@code If-None-Match}, {@code User-Agent}, {@code Cookie}, …) added to
 * {@code request.getHeaders()}: the client connector skips it and only logs "Addition of the
 * standard header … is not allowed", so the request goes out without it. Restlet only writes these
 * headers from its typed properties (client info, conditions, ranges, cookies, …). This class
 * parses the header value with Restlet's own header readers and sets the matching property, so
 * the header reaches the upstream. Any other header is set as a raw header, as before.</p>
 *
 * <p>Every site that sends a capability-declared or caller-supplied header upstream goes through
 * {@link #set}: REST forward mode ({@code trustedHeaders}), consumed {@code in: header} inputs at
 * adapter and operation level, and an {@code apikey} sent in a header.</p>
 */
public final class StandardRequestHeaders {

    /** Standard request headers, by name, and how each one is set on the outgoing request. */
    private static final Map<String, BiConsumer<Request, String>> SETTERS =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * Standard headers that are never sent from a request property, with the reason. Restlet drops
     * them when added as raw headers, so they cannot be forwarded either.
     */
    private static final Map<String, String> NOT_SENDABLE =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * Standard headers that describe the request body. In forward mode the body is forwarded with
     * them, so listing one in {@code trustedHeaders} is harmless and needs no copy.
     */
    private static final Set<String> ENTITY_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        SETTERS.put("Accept", (request, value) -> preferences(request.getClientInfo(), value,
                ClientInfo::getAcceptedMediaTypes, PreferenceReader::addMediaTypes));
        SETTERS.put("Accept-Charset", (request, value) -> preferences(request.getClientInfo(),
                value, ClientInfo::getAcceptedCharacterSets, PreferenceReader::addCharacterSets));
        SETTERS.put("Accept-Language", (request, value) -> preferences(request.getClientInfo(),
                value, ClientInfo::getAcceptedLanguages, PreferenceReader::addLanguages));
        SETTERS.put("Access-Control-Request-Headers", (request, value) -> {
            request.getAccessControlRequestHeaders().clear();
            new StringReader(value).addValues(request.getAccessControlRequestHeaders());
        });
        SETTERS.put("Access-Control-Request-Method",
                (request, value) -> request.setAccessControlRequestMethod(
                        Method.valueOf(value.trim())));
        SETTERS.put("Authorization", (request, value) -> request
                .setChallengeResponse(ApiKeyAuthorizationHeaderHelper.verbatim(value)));
        SETTERS.put("Cache-Control", (request, value) -> {
            request.getCacheDirectives().clear();
            new CacheDirectiveReader(value).addValues(request.getCacheDirectives());
        });
        SETTERS.put("Cookie", (request, value) -> {
            request.getCookies().clear();
            new CookieReader(value).addValues(request.getCookies());
        });
        SETTERS.put("From", (request, value) -> request.getClientInfo().setFrom(value));
        SETTERS.put("If-Match", (request, value) -> {
            request.getConditions().getMatch().clear();
            new TagReader(value).addValues(request.getConditions().getMatch());
        });
        SETTERS.put("If-None-Match", (request, value) -> {
            request.getConditions().getNoneMatch().clear();
            new TagReader(value).addValues(request.getConditions().getNoneMatch());
        });
        SETTERS.put("If-Modified-Since",
                (request, value) -> request.getConditions().setModifiedSince(date(value)));
        SETTERS.put("If-Unmodified-Since",
                (request, value) -> request.getConditions().setUnmodifiedSince(date(value)));
        SETTERS.put("If-Range", StandardRequestHeaders::setIfRange);
        SETTERS.put("Max-Forwards",
                (request, value) -> request.setMaxForwards(Integer.parseInt(value.trim())));
        SETTERS.put("Range", (request, value) -> request.setRanges(RangeReader.read(value)));
        SETTERS.put("Referer", (request, value) -> request.setReferrerRef(value.trim()));
        SETTERS.put("User-Agent", (request, value) -> request.getClientInfo().setAgent(value));
        SETTERS.put("Via", (request, value) -> {
            request.getRecipientsInfo().clear();
            new RecipientInfoReader(value).addValues(request.getRecipientsInfo());
        });
        SETTERS.put("Warning", (request, value) -> {
            request.getWarnings().clear();
            new WarningReader(value).addValues(request.getWarnings());
        });

        String connector = "is set by the HTTP connector";
        for (String name : new String[] {"Connection", "Content-Length", "Date", "Expect",
                "Host"}) {
            NOT_SENDABLE.put(name, connector);
        }
        // The Jetty client advertises gzip itself and decodes the response before the engine
        // sees it, so a copied value would be sent twice and never reach the caller as asked
        NOT_SENDABLE.put("Accept-Encoding",
                "is set by the HTTP connector, which decodes compressed responses itself");
        NOT_SENDABLE.put("Proxy-Authorization",
                "is meant for the proxy that receives it, not for the upstream");
        String response = "is a response header";
        for (String name : new String[] {"Accept-Patch", "Accept-Ranges",
                "Access-Control-Allow-Credentials", "Access-Control-Allow-Headers",
                "Access-Control-Allow-Methods", "Access-Control-Allow-Origin",
                "Access-Control-Expose-Headers", "Access-Control-Max-Age", "Age", "Allow",
                "Authentication-Info", "ETag", "Expires", "Last-Modified", "Location",
                "Proxy-Authenticate", "Retry-After", "Server", "Set-Cookie", "Vary",
                "WWW-Authenticate"}) {
            NOT_SENDABLE.put(name, response);
        }

        ENTITY_HEADERS.addAll(Set.of("Content-Disposition", "Content-Encoding",
                "Content-Language", "Content-Location", "Content-MD5", "Content-Range",
                "Content-Type"));
    }

    private StandardRequestHeaders() {
    }

    /**
     * Sets {@code name: value} on {@code request}, replacing any value set before. A standard
     * header is set through the Restlet property the connector writes it from; any other header is
     * set as a raw header.
     *
     * @throws IllegalArgumentException when {@code value} is not a valid value of the standard
     *         header {@code name}, so that a bad value is never sent as an empty or partial header
     */
    public static void set(Request request, String name, String value) {
        BiConsumer<Request, String> setter = SETTERS.get(name);
        if (setter == null) {
            request.getHeaders().set(name, value);
            return;
        }
        try {
            setter.accept(request, value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid value for the " + name + " header", e);
        }
    }

    /**
     * @return {@code true} when {@code name} describes the request body ({@code Content-Type},
     *         …), which forward mode sends with the body itself
     */
    public static boolean isEntityHeader(String name) {
        return ENTITY_HEADERS.contains(name);
    }

    /**
     * @return why the header {@code name} can never be sent upstream, or {@code null} when it can
     */
    public static String notSendableReason(String name) {
        return name == null ? null : NOT_SENDABLE.get(name);
    }

    private static <T> void preferences(ClientInfo clientInfo, String value,
            Function<ClientInfo, List<T>> preferences,
            BiConsumer<String, ClientInfo> reader) {
        preferences.apply(clientInfo).clear();
        reader.accept(value, clientInfo);
    }

    private static void setIfRange(Request request, String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("W/")) {
            request.getConditions().setRangeTag(Tag.parse(trimmed));
            request.getConditions().setRangeDate(null);
        } else {
            request.getConditions().setRangeDate(date(trimmed));
            request.getConditions().setRangeTag(null);
        }
    }

    private static Date date(String value) {
        Date date = HeaderReader.readDate(value.trim(), false);
        if (date == null) {
            throw new IllegalArgumentException("Not an HTTP date");
        }
        return date;
    }
}
