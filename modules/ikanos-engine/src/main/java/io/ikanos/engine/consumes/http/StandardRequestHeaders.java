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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.restlet.Request;
import org.restlet.data.ClientInfo;
import org.restlet.data.Cookie;
import org.restlet.data.Metadata;
import org.restlet.data.Method;
import org.restlet.data.Preference;
import org.restlet.data.Range;
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
 * <p><b>Strict parsing.</b> Restlet's list readers skip an element they cannot read (an unquoted
 * entity tag, for example) and log the failure at {@code INFO}, so a header could go out empty or
 * with part of its elements. Every list is therefore parsed into a fresh collection first, and its
 * size is compared with the number of distinct non-empty elements of the value: a value that does
 * not parse in full is refused, and the request keeps what it had before.</p>
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

    /** Preconditions that guard a write: dropping one would turn it into an unconditional one. */
    private static final Set<String> CONDITIONAL_HEADERS =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        SETTERS.put("Accept", (request, value) -> preferences(request, value,
                ClientInfo::getAcceptedMediaTypes, PreferenceReader::addMediaTypes));
        SETTERS.put("Accept-Charset", (request, value) -> preferences(request, value,
                ClientInfo::getAcceptedCharacterSets, PreferenceReader::addCharacterSets));
        SETTERS.put("Accept-Language", (request, value) -> preferences(request, value,
                ClientInfo::getAcceptedLanguages, PreferenceReader::addLanguages));
        SETTERS.put("Access-Control-Request-Headers", (request, value) -> replace(
                request.getAccessControlRequestHeaders(), parse(value, ',',
                        list -> new StringReader(value).addValues(list))));
        SETTERS.put("Access-Control-Request-Method",
                (request, value) -> request.setAccessControlRequestMethod(
                        Method.valueOf(value.trim())));
        SETTERS.put("Authorization", (request, value) -> request
                .setChallengeResponse(ApiKeyAuthorizationHeaderHelper.verbatim(value)));
        SETTERS.put("Cache-Control", (request, value) -> replace(request.getCacheDirectives(),
                parse(value, ',', list -> new CacheDirectiveReader(value).addValues(list))));
        SETTERS.put("Cookie", StandardRequestHeaders::mergeCookies);
        SETTERS.put("From", (request, value) -> request.getClientInfo().setFrom(value));
        SETTERS.put("If-Match", (request, value) -> replace(request.getConditions().getMatch(),
                parse(value, ',', list -> new TagReader(value).addValues(list))));
        SETTERS.put("If-None-Match", (request, value) -> replace(
                request.getConditions().getNoneMatch(),
                parse(value, ',', list -> new TagReader(value).addValues(list))));
        SETTERS.put("If-Modified-Since",
                (request, value) -> request.getConditions().setModifiedSince(date(value)));
        SETTERS.put("If-Unmodified-Since",
                (request, value) -> request.getConditions().setUnmodifiedSince(date(value)));
        SETTERS.put("If-Range", StandardRequestHeaders::setIfRange);
        SETTERS.put("Max-Forwards",
                (request, value) -> request.setMaxForwards(Integer.parseInt(value.trim())));
        SETTERS.put("Range", (request, value) -> request.setRanges(ranges(value)));
        SETTERS.put("Referer", (request, value) -> request.setReferrerRef(value.trim()));
        SETTERS.put("User-Agent", (request, value) -> request.getClientInfo().setAgent(value));
        SETTERS.put("Via", (request, value) -> replace(request.getRecipientsInfo(),
                parse(value, ',', list -> new RecipientInfoReader(value).addValues(list))));
        SETTERS.put("Warning", (request, value) -> replace(request.getWarnings(),
                parse(value, ',', list -> new WarningReader(value).addValues(list))));

        String connector = "is set by the HTTP connector";
        for (String name : new String[] {"Content-Length", "Host"}) {
            NOT_SENDABLE.put(name, connector);
        }
        String restlet = "is set by Restlet itself";
        for (String name : new String[] {"Date", "Expect"}) {
            NOT_SENDABLE.put(name, restlet);
        }
        String hopByHop = "is a hop-by-hop header (RFC 9110 §7.6.1), which applies to a "
                + "single connection";
        for (String name : new String[] {"Connection", "Keep-Alive", "Proxy-Connection", "TE",
                "Trailer", "Transfer-Encoding", "Upgrade"}) {
            NOT_SENDABLE.put(name, hopByHop);
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

        CONDITIONAL_HEADERS.addAll(
                Set.of("If-Match", "If-None-Match", "If-Unmodified-Since", "If-Range"));
    }

    private StandardRequestHeaders() {
    }

    /**
     * Sets {@code name: value} on {@code request}. A standard header is set through the Restlet
     * property the connector writes it from and replaces any value set before, except
     * {@code Cookie}, which is merged by cookie name (the later value wins for a name), so that an
     * {@code apikey} cookie and a declared or forwarded cookie are both sent. Any other header is
     * set as a raw header.
     *
     * @throws IllegalArgumentException when {@code value} is not a valid value of the standard
     *         header {@code name}, or only part of it can be parsed. The request is left unchanged
     *         for that header. The exception names the header and carries no cause, so that the
     *         value, which may be a credential, never reaches a log
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
            throw new IllegalArgumentException("Invalid value for the " + name + " header");
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
     * @return {@code true} when {@code name} is a precondition that guards a write
     *         ({@code If-Match}, {@code If-None-Match}, {@code If-Unmodified-Since},
     *         {@code If-Range})
     */
    public static boolean isConditional(String name) {
        return CONDITIONAL_HEADERS.contains(name);
    }

    /**
     * @return why the header {@code name} can never be sent upstream, or {@code null} when it can
     */
    public static String notSendableReason(String name) {
        return name == null ? null : NOT_SENDABLE.get(name);
    }

    static void setIfRange(Request request, String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("W/")) {
            Tag tag = Tag.parse(trimmed);
            if (tag == null) {
                throw new IllegalArgumentException("Not an entity tag");
            }
            request.getConditions().setRangeTag(tag);
            request.getConditions().setRangeDate(null);
        } else {
            request.getConditions().setRangeDate(date(trimmed));
            request.getConditions().setRangeTag(null);
        }
    }

    private static <T extends Metadata> void preferences(Request request, String value,
            Function<ClientInfo, List<Preference<T>>> preferences,
            BiConsumer<String, ClientInfo> reader) {
        List<Preference<T>> parsed = parse(value, ',', list -> {
            ClientInfo scratch = new ClientInfo();
            reader.accept(value, scratch);
            list.addAll(preferences.apply(scratch));
        });
        replace(preferences.apply(request.getClientInfo()), parsed);
    }

    private static void mergeCookies(Request request, String value) {
        List<Cookie> parsed = parse(value, ';', list -> new CookieReader(value).addValues(list));
        for (Cookie cookie : parsed) {
            request.getCookies().removeAll(cookie.getName());
            request.getCookies().add(cookie);
        }
    }

    private static List<Range> ranges(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("bytes=")) {
            throw new IllegalArgumentException("Only byte ranges can be sent");
        }
        List<Range> parsed = RangeReader.read(trimmed);
        if (parsed.size() != elements(trimmed.substring("bytes=".length()), ',')) {
            throw new IllegalArgumentException("Not a valid range list");
        }
        return parsed;
    }

    /**
     * Parses {@code value} into a fresh list and checks that every distinct non-empty element was
     * read, since Restlet's readers skip what they cannot read.
     */
    private static <T> List<T> parse(String value, char separator, Consumer<List<T>> reader) {
        List<T> parsed = new ArrayList<>();
        reader.accept(parsed);
        int expected = elements(value, separator);
        if (expected == 0 || parsed.size() < expected) {
            throw new IllegalArgumentException("Not every element could be read");
        }
        return parsed;
    }

    private static <T> void replace(Collection<T> target, Collection<T> parsed) {
        target.clear();
        target.addAll(parsed);
    }

    /**
     * @return the number of distinct non-empty elements of {@code value}, split on
     *         {@code separator} outside double-quoted strings (RFC 9110 §5.6.1 allows empty
     *         elements, and Restlet's readers drop duplicates)
     */
    static int elements(String value, char separator) {
        Set<String> elements = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' && (i == 0 || value.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            }
            if (c == separator && !quoted) {
                addElement(elements, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addElement(elements, current);
        return elements.size();
    }

    private static void addElement(Set<String> elements, StringBuilder element) {
        String trimmed = element.toString().trim();
        if (!trimmed.isEmpty()) {
            elements.add(trimmed);
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
