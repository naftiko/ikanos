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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Dimension;
import org.restlet.data.Header;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.engine.header.HeaderConstants;
import org.restlet.representation.Representation;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Relays an upstream response's headers to the caller in REST forward mode (#1002).
 *
 * <p>Restlet parses the standard response headers of the upstream answer into typed properties
 * of the client {@link Response}, and its server connector refuses a standard header added as a
 * raw header. So each standard header is relayed by copying its property, and only extension
 * headers are copied as raw headers. The entity, and with it its headers ({@code Content-Type},
 * {@code Content-Disposition}, {@code ETag}, {@code Last-Modified}, {@code Expires}, ...), is
 * relayed by the caller of {@link #relay}.
 */
public final class ForwardResponseHeaders {

    /** Opt-in header: never relayed unless listed in {@code trustedResponseHeaders}. */
    static final String SET_COOKIE = "set-cookie";

    /**
     * Headers that only make sense on one connection (RFC 9110 section 7.6.1), and
     * {@code Content-Length}, which the server connector computes. Never relayed.
     */
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-authenticate",
            "proxy-authorization", "content-length");

    /** Standard headers relayed by default: response properties and entity headers. */
    static final Set<String> RELAYED_BY_DEFAULT = Set.of("location", "retry-after",
            "cache-control", "vary", "allow", "www-authenticate", "accept-ranges", "age", "date",
            "content-type", "content-encoding", "content-language", "content-location",
            "content-disposition", "content-range", "content-md5", "etag", "last-modified",
            "expires");

    /**
     * The other headers Restlet models as properties: its server connector refuses them as raw
     * headers, and the engine does not relay them (the server sets its own {@code Server},
     * {@code Via}, {@code Warning} is obsolete (RFC 9111), CORS belongs to the
     * exposed adapter, and the rest are request headers).
     */
    static final Set<String> NOT_RELAYABLE = Set.of("server", "via", "warning",
            "set-cookie2", "authentication-info", "access-control-allow-credentials",
            "access-control-allow-headers", "access-control-allow-methods",
            "access-control-allow-origin", "access-control-expose-headers",
            "access-control-max-age", "access-control-request-headers",
            "access-control-request-method", "accept", "accept-charset", "accept-encoding",
            "accept-language", "accept-patch", "authorization", "cookie", "expect", "from", "host",
            "if-match", "if-modified-since", "if-none-match", "if-range", "if-unmodified-since",
            "max-forwards", "range", "referer", "user-agent");

    /** How many times a query value is percent-decoded to find a URL or query nested in it. */
    static final int MAX_NESTED_DECODINGS = 4;

    /** The prefix, in lower case, of the query parameters of an AWS query-string signature. */
    static final String AMZ_QUERY_PREFIX = "x-amz-";

    /** Dot segments, plain or percent-encoded, in lower case. */
    static final Set<String> DOT_SEGMENTS = Set.of("..", ".", "%2e%2e", ".%2e", "%2e.", "%2e");

    /** {@code Vary} field names that Restlet maps to a {@link Dimension}. */
    static final Set<String> VARY_DIMENSIONS = Set.of("accept", "accept-charset",
            "accept-encoding", "accept-language", "authorization", "user-agent", "origin", "*");

    private ForwardResponseHeaders() {}

    /**
     * Checks a {@code trustedResponseHeaders} list when the capability loads.
     *
     * @param names the listed header names, or {@code null}
     * @param where the resource the list belongs to, for the error message
     * @throws IllegalArgumentException when an entry is blank or names a header that can never be
     *         relayed
     */
    public static void validate(List<String> names, String where) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("trustedResponseHeaders of " + where
                        + " has a blank entry. List header names only.");
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(key)) {
                throw new IllegalArgumentException("trustedResponseHeaders of " + where
                        + " lists '" + name + "', a hop-by-hop header that forward mode never "
                        + "relays.");
            }
            if (NOT_RELAYABLE.contains(key)) {
                throw new IllegalArgumentException("trustedResponseHeaders of " + where
                        + " lists '" + name + "', a standard header that forward mode cannot "
                        + "relay.");
            }
        }
    }

    /**
     * Checks the {@code trustedResponseHeaders} of every forward-mode REST resource of a spec,
     * without loading it, for {@code ikanos validate}. The same check runs when the capability
     * loads.
     *
     * @param spec the capability spec, may be null
     * @return one message per resource whose list is refused, in declaration order
     */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            return errors;
        }
        List<ServerSpec> servers = new ArrayList<>();
        if (spec.getExposes() != null) {
            servers.addAll(spec.getExposes());
        }
        if (spec.getCapability() != null && spec.getCapability().getExposes() != null) {
            servers.addAll(spec.getCapability().getExposes());
        }
        for (ServerSpec server : servers) {
            if (!(server instanceof RestServerSpec rest) || rest.getResources() == null) {
                continue;
            }
            for (RestServerResourceSpec resource : rest.getResources().values()) {
                if (resource.getForward() == null) {
                    continue;
                }
                try {
                    validate(resource.getForward().getTrustedResponseHeaders(), "REST resource '"
                            + resource.getPath() + "' in '" + rest.getNamespace() + "'");
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }
        return errors;
    }

    /**
     * Copies the headers of an upstream response to the response sent to the caller. The entity
     * must already be set on {@code to}.
     *
     * @param from the upstream response
     * @param to the response to the caller, whose request is the incoming request
     * @param trustedResponseHeaders the extension headers and opt-in headers to relay
     * @param upstreamBaseUri the resolved base URI of the forward target
     * @param relayChallenges whether to relay the upstream's {@code WWW-Authenticate}
     *        challenges: only when the upstream judged the caller's own credential, that is when
     *        the consumed adapter has no {@code authentication}. A challenge on the adapter's own
     *        credential names an internal realm and tells the caller that credential is broken.
     * @param warned the location headers already warned about for the exposed resource, owned by
     *        that resource so the set stays bounded: later drops are logged at {@code FINE}
     */
    static void relay(Response from, Response to, List<String> trustedResponseHeaders,
            String upstreamBaseUri, boolean relayChallenges, Set<String> warned) {
        relay(from, to, trustedResponseHeaders, upstreamBaseUri, relayChallenges, warned,
                new Exposure(null, Set.of(), false));
    }

    /**
     * What the relay needs to know about the exposed resource and the consumed adapter.
     *
     * @param resourcePath the path template of the exposed resource, or {@code null} when
     *        unknown: the text after {@code {{path}}} is kept around a rewritten location
     * @param injectedQueryNames the names of the query parameters the engine added to the
     *        upstream request (an {@code apikey}, an adapter input), which can carry a secret: a
     *        location whose query names one is dropped. Never {@code null}.
     * @param authenticated whether the exposed adapter authenticates its callers: the answer is
     *        then marked {@code private}, since the upstream never sees that credential
     * @param signed whether the consumed adapter signs with {@code awsSigV4}: a location whose
     *        query carries an {@code X-Amz-*} parameter the caller did not send is then dropped,
     *        so a presigned URL or an echoed session token never reaches the caller
     */
    record Exposure(String resourcePath, Set<String> injectedQueryNames, boolean authenticated,
            boolean signed) {

        Exposure(String resourcePath, Set<String> injectedQueryNames, boolean authenticated) {
            this(resourcePath, injectedQueryNames, authenticated, false);
        }
    }

    /**
     * Same, for the exposed resource and consumed adapter {@code exposure} describes.
     */
    static void relay(Response from, Response to, List<String> trustedResponseHeaders,
            String upstreamBaseUri, boolean relayChallenges, Set<String> warned,
            Exposure exposure) {
        Request incoming = to.getRequest();
        // RFC 9110 section 7.6.1: an intermediary removes every field the Connection header
        // nominates, whatever the allow-list says.
        Set<String> nominated = connectionOptions(from);
        String upstreamRequestUri = from.getRequest() == null
                || from.getRequest().getResourceRef() == null ? upstreamBaseUri
                        : from.getRequest().getResourceRef().toString();

        String location = nominated.contains("location") ? null
                : from.getHeaders().getFirstValue(HeaderConstants.HEADER_LOCATION, true);
        String relayedLocation = location == null ? null
                : relayedLocation(location, upstreamRequestUri, upstreamBaseUri, incoming,
                        exposure);
        if (location != null && relayedLocation == null) {
            logDropped(HeaderConstants.HEADER_LOCATION, from, warned);
        }
        to.setLocationRef(verbatim(relayedLocation));

        Representation entity = to.getEntity();
        if (entity != null) {
            String contentLocation = nominated.contains("content-location") ? null
                    : from.getHeaders().getFirstValue(HeaderConstants.HEADER_CONTENT_LOCATION,
                            true);
            if (contentLocation != null || entity.getLocationRef() != null) {
                String relayedContentLocation = contentLocation == null ? null
                        : relayedLocation(contentLocation, upstreamRequestUri, upstreamBaseUri,
                                incoming, exposure);
                if (contentLocation != null && relayedContentLocation == null) {
                    logDropped(HeaderConstants.HEADER_CONTENT_LOCATION, from, warned);
                }
                entity.setLocationRef(verbatim(relayedContentLocation));
            }
            removeNominatedEntityHeaders(entity, nominated);
        }

        if (!nominated.contains("retry-after")) {
            to.setRetryAfter(from.getRetryAfter());
        }
        if (!nominated.contains("cache-control")) {
            to.setCacheDirectives(new ArrayList<>(from.getCacheDirectives()));
        }
        if (!nominated.contains("vary")) {
            to.getDimensions().addAll(from.getDimensions());
            if (variesOnUnknownField(from)) {
                to.getDimensions().add(Dimension.UNSPECIFIED);
            }
        }
        if (!nominated.contains("allow")) {
            to.getAllowedMethods().addAll(from.getAllowedMethods());
        }
        if (relayChallenges && !nominated.contains("www-authenticate")) {
            to.setChallengeRequests(rawChallenges(from));
        }
        if (!nominated.contains("accept-ranges")) {
            to.getServerInfo().setAcceptingRanges(from.getServerInfo().isAcceptingRanges());
        }
        if (!nominated.contains("age")) {
            to.setAge(from.getAge());
        }
        // The upstream's Date is the one its Expires and Age are relative to (RFC 9111 section
        // 4.2): the server connector only writes its own when none is set
        if (!nominated.contains("date") && from.getDate() != null) {
            to.setDate(from.getDate());
        }
        if (Status.REDIRECTION_NOT_MODIFIED.equals(from.getStatus())) {
            keepExpiresOfNotModified(from, to, nominated);
        }
        if (exposure.authenticated()) {
            markPrivate(to);
        }

        if (trustedResponseHeaders == null) {
            return;
        }
        Set<String> listed = new LinkedHashSet<>();
        for (String name : trustedResponseHeaders) {
            if (name != null && !name.isBlank()) {
                listed.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        listed.removeAll(nominated);
        for (String key : listed) {
            if (SET_COOKIE.equals(key)) {
                to.getCookieSettings().addAll(from.getCookieSettings());
            } else if (!RELAYED_BY_DEFAULT.contains(key) && !HOP_BY_HOP.contains(key)
                    && !NOT_RELAYABLE.contains(key)) {
                for (Header header : from.getHeaders()) {
                    if (header.getName().equalsIgnoreCase(key)) {
                        to.getHeaders().add(header.getName(), header.getValue());
                    }
                }
            }
        }
    }

    /**
     * Marks the answer to a caller the exposed adapter authenticated as {@code private}, so a
     * shared cache never serves it to a caller without that credential: the upstream never sees
     * the credential, so its {@code public}, {@code s-maxage} or plain {@code max-age} cannot
     * account for it. {@code public}, {@code s-maxage} and a {@code private} limited to some
     * fields are removed, and {@code private} is added unless {@code no-store} is there. A
     * {@code 304} without directives gets none: a cache would replace its stored
     * {@code Cache-Control} with them (RFC 9111 section 4.3.4), and the stored answer was already
     * marked {@code private}.
     */
    static void markPrivate(Response to) {
        List<CacheDirective> directives = to.getCacheDirectives();
        if (directives.isEmpty() && Status.REDIRECTION_NOT_MODIFIED.equals(to.getStatus())) {
            return;
        }
        directives.removeIf(directive -> {
            String name = directive.getName() == null ? ""
                    : directive.getName().toLowerCase(Locale.ROOT);
            return name.equals("public") || name.equals("s-maxage")
                    || (name.equals("private") && directive.getValue() != null);
        });
        boolean kept = directives.stream().anyMatch(directive -> directive.getName() != null
                && (directive.getName().equalsIgnoreCase("private")
                        || directive.getName().equalsIgnoreCase("no-store")));
        if (!kept) {
            directives.add(CacheDirective.privateInfo());
        }
    }

    /**
     * Keeps the freshness an upstream {@code 304} gives through {@code Expires}. Restlet's server
     * connector writes only {@code ETag} and {@code Content-Location} from the entity of a
     * {@code 304}, so a cache refreshing its stored response (RFC 9111 section 4.3.4) would never
     * see the new {@code Expires}. When the answer has {@code Cache-Control} directives but no
     * {@code max-age}, the same lifetime is added as {@code max-age}: {@code Expires} minus the
     * upstream's {@code Date}, or minus now when it has none, and at least 0. A {@code 304}
     * without {@code Cache-Control} gets none: the cache would replace the stored
     * {@code Cache-Control} with it (RFC 9111 section 3.2) and lose a {@code no-cache}, so its
     * {@code Expires} is lost instead, a limitation of the server connector.
     */
    static void keepExpiresOfNotModified(Response from, Response to, Set<String> nominated) {
        Representation entity = from.getEntity();
        if (nominated.contains("expires") || nominated.contains("cache-control")
                || entity == null || entity.getExpirationDate() == null
                || to.getCacheDirectives().isEmpty()) {
            return;
        }
        for (CacheDirective directive : to.getCacheDirectives()) {
            if (HeaderConstants.CACHE_MAX_AGE.equalsIgnoreCase(directive.getName())) {
                return;
            }
        }
        long now = from.getDate() != null ? from.getDate().getTime() : System.currentTimeMillis();
        long seconds = (entity.getExpirationDate().getTime() - now) / 1000;
        to.getCacheDirectives().add(CacheDirective
                .maxAge((int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE))));
    }

    /**
     * @return the field names, in lower case, that the upstream's {@code Connection} headers
     *         nominate as connection options
     */
    static Set<String> connectionOptions(Response from) {
        Set<String> options = new HashSet<>();
        for (Header header : from.getHeaders()) {
            if (!header.getName().equalsIgnoreCase(HeaderConstants.HEADER_CONNECTION)) {
                continue;
            }
            for (String token : header.getValue().split(",")) {
                String key = token.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) {
                    options.add(key);
                }
            }
        }
        return options;
    }

    /**
     * Removes from the relayed entity the optional metadata headers that {@code Connection}
     * nominates. {@code Content-Type}, {@code Content-Encoding}, {@code Content-Range} and
     * {@code Content-MD5} describe the bytes themselves and are kept: RFC 9110 section 7.6.1
     * forbids a sender to nominate a field meant for every recipient of the content, and
     * dropping them would make the caller misread the body.
     */
    static void removeNominatedEntityHeaders(Representation entity, Set<String> nominated) {
        if (nominated.contains("content-disposition")) {
            entity.setDisposition(null);
        }
        if (nominated.contains("content-language")) {
            entity.getLanguages().clear();
        }
        if (nominated.contains("etag")) {
            entity.setTag(null);
        }
        if (nominated.contains("last-modified")) {
            entity.setModificationDate(null);
        }
        if (nominated.contains("expires")) {
            entity.setExpirationDate(null);
        }
    }

    /**
     * Logs that a location header was not relayed, naming the header and the upstream status but
     * never the value, which can carry the upstream host. The first redirect or {@code 201}
     * without its location for a given resource and header is worth a warning; anything else is
     * logged at {@code FINE}.
     *
     * @param warned the headers already warned about, owned by the exposed resource
     */
    static void logDropped(String headerName, Response from, Set<String> warned) {
        int status = from.getStatus() == null ? 0 : from.getStatus().getCode();
        boolean warnable = (status >= 300 && status < 400) || status == 201;
        Level level = warnable && warned.add(headerName) ? Level.WARNING
                : Level.FINE;
        Context.getCurrentLogger().log(level, () -> "Forward mode did not relay the upstream "
                + headerName + " header of a " + status + " response: it points at the "
                + "upstream outside the forward target's baseUri, or cannot be parsed.");
    }

    /**
     * @return {@code true} when a {@code Vary} field name of the upstream answer has no Restlet
     *         {@link Dimension}, so the relayed answer must vary on everything ({@code Vary: *})
     */
    static boolean variesOnUnknownField(Response from) {
        for (Header header : from.getHeaders()) {
            if (!header.getName().equalsIgnoreCase(HeaderConstants.HEADER_VARY)) {
                continue;
            }
            for (String field : header.getValue().split(",")) {
                String key = field.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty() && !VARY_DIMENSIONS.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rebuilds the upstream's {@code WWW-Authenticate} challenges from the raw header values, one
     * challenge per header line, so they are written back as received. Restlet re-formats a
     * parsed challenge, which drops the quotes of a token-valued {@code realm} that RFC 7235
     * section 2.2 requires.
     */
    static List<ChallengeRequest> rawChallenges(Response from) {
        List<ChallengeRequest> challenges = new ArrayList<>();
        for (Header header : from.getHeaders()) {
            if (!header.getName().equalsIgnoreCase(HeaderConstants.HEADER_WWW_AUTHENTICATE)) {
                continue;
            }
            String value = header.getValue().trim();
            int space = value.indexOf(' ');
            String scheme = space < 0 ? value : value.substring(0, space);
            if (scheme.isEmpty()) {
                continue;
            }
            ChallengeRequest challenge =
                    new ChallengeRequest(new ChallengeScheme("HTTP_" + scheme, scheme));
            // Restlet writes "<scheme> <raw value>" and drops the challenge when the raw value
            // adds nothing, so a bare challenge (e.g. "Negotiate") keeps a single space, which
            // HTTP treats as optional whitespace around the field value.
            challenge.setRawValue(space < 0 ? " " : value.substring(space + 1).trim());
            challenges.add(challenge);
        }
        return challenges;
    }

    /**
     * Decides what to send the caller for an upstream {@code Location} or
     * {@code Content-Location}. The value is first resolved against the upstream request URI
     * (RFC 3986 section 5.2), which applies {@code .} and {@code ..} segments but keeps empty
     * path segments, so {@code /items//42} stays distinct from {@code /items/42}.
     *
     * <ul>
     * <li>Same scheme, host and port as {@code upstreamBaseUri}, and under its path: the matching
     * path-absolute reference under the exposed resource, such as {@code /proxy/items/42}, so
     * neither the upstream host nor the caller's {@code Host} is written.</li>
     * <li>Any other value on the upstream host, whatever its form (relative, scheme-relative,
     * another scheme or port, outside the base path): dropped, so the upstream host, and paths
     * that would point into the gateway's own namespace, never reach the caller.</li>
     * <li>A value on another host: relayed unchanged, except a scheme-relative one
     * ({@code //cdn.example/x}), which is sent with the scheme of the upstream request, so a
     * caller on plain HTTP is not sent to plain HTTP.</li>
     * <li>A value whose query names the consumed adapter's query-placed {@code apikey}, wherever
     * it points, whatever the case of the name, and also inside a URL or query nested in one of
     * its values, even percent-encoded ({@code ?next=%2Fa%3Fapi_key%3D...}): dropped, so an
     * upstream that echoes its request URI never hands the caller that credential. A value still
     * encoded after {@value #MAX_NESTED_DECODINGS} decodings is dropped too.</li>
     * <li>When the consumed adapter signs with {@code awsSigV4}, a value whose query carries an
     * {@code X-Amz-*} parameter the caller did not send with that value, wherever it points:
     * dropped. The request is signed in headers, so such a parameter is a query-string signature
     * ({@code X-Amz-Signature}, {@code X-Amz-Credential}) or a session token
     * ({@code X-Amz-Security-Token}) the upstream wrote, which could act with the adapter's
     * credential.</li>
     * <li>A value with a percent-encoded dot segment as sent, even one a later {@code ..}
     * removes during resolution: dropped, like a value whose resolved path keeps one.</li>
     * <li>A value that resolves to no host, such as {@code http:/admin} or {@code urn:x}:
     * dropped. A client can read a scheme-qualified reference without an authority as relative
     * to the gateway (RFC 3986 section 5.2.2, non-strict), outside the exposed resource.</li>
     * <li>A value or base URI that cannot be parsed: dropped.</li>
     * <li>Under a resource without {@code {{path}}}, which forwards to the base URI only, a value
     * below the base URI: dropped, since no exposed path routes to it. A value equal to the base
     * URI, with or without a trailing slash, is rewritten to the exposed path as routed.</li>
     * <li>Under a resource whose path has text after {@code {{path}}}, such as
     * {@code /proxy/{{path}}/file}, that text follows the rewritten path.</li>
     * <li>Under a resource with {@code {{path}}}, a value equal to the base URI (with or without
     * a trailing slash): dropped, since its route needs a non-empty forwarded path.</li>
     * <li>A value whose rewritten path would start with {@code //}, which only happens under a
     * resource exposed at the root, such as {@code http://upstream/v1//evil.example/x}: dropped.
     * The caller would read it as a scheme-relative reference to another host.</li>
     * </ul>
     *
     * <p>Scheme and host are compared case-insensitively, a default port is ignored, a trailing
     * slash on the base URI is ignored, and only whole path segments match. The host and port are
     * read from the raw authority, so a host name with an underscore (a common Docker or
     * Kubernetes service name) is compared like any other.
     *
     * @param raw the raw header value from the upstream
     * @param upstreamRequestUri the URI the upstream request was sent to
     * @param upstreamBaseUri the resolved base URI of the forward target
     * @param incoming the incoming request, carrying the forwarded {@code path} attribute
     * @return the value to send, or {@code null} to drop the header
     */
    static String relayedLocation(String raw, String upstreamRequestUri, String upstreamBaseUri,
            Request incoming) {
        return relayedLocation(raw, upstreamRequestUri, upstreamBaseUri, incoming,
                new Exposure(null, Set.of(), false));
    }

    /**
     * Same, for the exposed resource and consumed adapter {@code exposure} describes: a value
     * whose query names a query parameter the engine added to the upstream request is dropped,
     * wherever it points, and the text around {@code {{path}}} in the resource path is kept
     * around a rewritten path.
     */
    static String relayedLocation(String raw, String upstreamRequestUri, String upstreamBaseUri,
            Request incoming, Exposure exposure) {
        URI base = parse(upstreamBaseUri);
        URI value = parse(raw.trim());
        if (base == null || value == null || host(base) == null) {
            return null;
        }
        URI requestUri = parse(upstreamRequestUri);
        URI target = requestUri != null && requestUri.isAbsolute() ? requestUri : base;
        // A same-document reference (empty, query-only or fragment-only) keeps the whole request
        // path (RFC 3986 section 5.2.2), which URI.resolve does not do, and an inherited query
        // is the caller's own: the upstream request's query can carry a query-placed credential
        // of the consumed adapter.
        boolean sameDocument = value.getScheme() == null && value.getRawAuthority() == null
                && (value.getRawPath() == null || value.getRawPath().isEmpty());
        URI resolved = resolve(target, sameDocument ? target : value);
        if (resolved == null) {
            return null;
        }
        String query = !sameDocument ? resolved.getRawQuery()
                : value.getRawQuery() != null ? value.getRawQuery()
                        : incoming.getResourceRef().getQuery();
        String fragment = sameDocument ? value.getRawFragment() : resolved.getRawFragment();
        if (exposesProtectedParameter(value.getRawQuery(), exposure,
                incoming.getResourceRef().getQuery())) {
            // An upstream that echoes the request URI would hand the caller what the engine
            // added to it, such as a query-placed credential, and a signing adapter's upstream
            // could hand back a presigned URL or its session token
            return null;
        }
        String host = host(resolved);
        if (host == null) {
            return null;
        }
        if (!host.equalsIgnoreCase(host(base))) {
            // A scheme-relative reference keeps the scheme of the upstream request, not the one
            // the caller reached the gateway with, which could downgrade https to http
            return value.getScheme() == null ? resolved.toString() : raw;
        }
        String basePath = stripTrailingSlashes(base.getRawPath());
        String path = resolved.getRawPath() == null ? "" : resolved.getRawPath();
        // An encoded dot segment is checked on the value as sent too: a later plain ".." can
        // remove it during resolution, while a WHATWG parser decodes it and climbs further
        if (hasDotSegment(path) || (value.getRawPath() != null
                && hasEncodedDotSegment(value.getRawPath()))) {
            return null;
        }
        boolean under = path.equals(basePath) || (path.startsWith(basePath)
                && (basePath.isEmpty() || path.charAt(basePath.length()) == '/'));
        if (!under || !resolved.getScheme().equalsIgnoreCase(base.getScheme())
                || port(resolved) != port(base)) {
            return null;
        }
        String tail = pathTail(exposure.resourcePath());
        String exposed = exposedPath(incoming, tail);
        if (exposed == null) {
            return null;
        }
        String suffix = path.substring(basePath.length());
        boolean forwardsPath = incoming.getAttributes().containsKey("path");
        if (!forwardsPath) {
            if (!suffix.isEmpty() && !suffix.equals("/")) {
                // A resource without {{path}} forwards to the base URI only and routes no
                // sub-path, so a location below the base URI has no exposed counterpart
                return null;
            }
            // The base URI, with or without a trailing slash, is the exposed path as routed
            suffix = "";
            tail = "";
        } else if (suffix.isEmpty() || suffix.equals("/")) {
            // Under {{path}}, the route needs a non-empty path after the exposed one, so the
            // base URI itself has no exposed counterpart: neither /proxy nor /proxy/ matches
            // /proxy/{path}
            return null;
        } else {
            // The forward target is the base URI, "/" and the forwarded path, so the suffix
            // without its first "/" is what the route captures after the exposed prefix
            suffix = suffix.substring(1);
        }
        String rewritten = exposed + suffix + tail;
        if (rewritten.isEmpty()) {
            rewritten = "/";
        }
        if (rewritten.startsWith("//")) {
            // Under a resource exposed at the root, a path that starts with an empty segment
            // would be written as a scheme-relative reference, naming another host
            return null;
        }
        if (query != null) {
            rewritten += "?" + query;
        }
        if (fragment != null) {
            rewritten += "#" + fragment;
        }
        return rewritten;
    }

    /**
     * Resolves {@code value} against {@code target} as RFC 3986 section 5.2 does, on the raw
     * components. {@link URI#resolve(URI)} and {@link URI#normalize()} also collapse empty path
     * segments, which can name another resource.
     *
     * @return the resolved URI, or {@code null} when it has no authority (including a
     *         scheme-qualified {@code value} without one) or cannot be parsed
     */
    static URI resolve(URI target, URI value) {
        String scheme = value.getScheme() != null ? value.getScheme() : target.getScheme();
        String authority;
        String path;
        if (value.getScheme() != null || value.getRawAuthority() != null) {
            authority = value.getRawAuthority();
            path = value.getRawPath();
        } else {
            authority = target.getRawAuthority();
            path = value.getRawPath() == null ? "" : value.getRawPath();
            if (!path.startsWith("/")) {
                String targetPath = target.getRawPath() == null ? "" : target.getRawPath();
                path = targetPath.isEmpty() ? "/" + path
                        : targetPath.substring(0, targetPath.lastIndexOf('/') + 1) + path;
            }
        }
        if (scheme == null || authority == null) {
            return null;
        }
        StringBuilder resolved = new StringBuilder(scheme).append("://").append(authority)
                .append(removeDotSegments(path == null ? "" : path));
        if (value.getRawQuery() != null) {
            resolved.append('?').append(value.getRawQuery());
        }
        if (value.getRawFragment() != null) {
            resolved.append('#').append(value.getRawFragment());
        }
        return parse(resolved.toString());
    }

    /**
     * Applies the plain {@code .} and {@code ..} segments of a raw path (RFC 3986 section
     * 5.2.4), keeping empty segments. A {@code ..} that would climb above the root is kept, so
     * {@link #hasDotSegment(String)} drops the value instead of pinning it to the root.
     */
    static String removeDotSegments(String path) {
        if (path.isEmpty()) {
            return path;
        }
        boolean absolute = path.startsWith("/");
        String[] segments = (absolute ? path.substring(1) : path).split("/", -1);
        List<String> output = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.equals(".")) {
                if (last) {
                    output.add("");
                }
            } else if (segment.equals("..")) {
                if (output.isEmpty() || output.get(output.size() - 1).equals("..")) {
                    output.add("..");
                } else {
                    output.remove(output.size() - 1);
                    if (last) {
                        output.add("");
                    }
                }
            } else {
                output.add(segment);
            }
        }
        return (absolute ? "/" : "") + String.join("/", output);
    }

    /**
     * @return {@code true} when a raw path segment is a dot segment, plain or percent-encoded
     *         ({@code ..}, {@code .}, {@code %2e%2e}, {@code .%2e}, {@code %2e.}, {@code %2e}, in
     *         any case). {@link #removeDotSegments(String)} keeps a leading {@code ..} on an
     *         absolute path and never decodes {@code %2e}, while WHATWG URL parsers apply both, so
     *         such a path could leave the base once the caller follows it.
     */
    static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (DOT_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} when a raw path segment is a percent-encoded dot segment
     *         ({@code %2e%2e}, {@code .%2e}, {@code %2e.}, {@code %2e}, in any case)
     */
    static boolean hasEncodedDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (segment.indexOf('%') >= 0
                    && DOT_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the names of the parameters of {@code rawQuery}, percent-decoded (a malformed
     *         escape is kept as written), or an empty set when it is {@code null}
     */
    static Set<String> queryNames(String rawQuery) {
        Set<String> names = new HashSet<>();
        if (rawQuery == null) {
            return names;
        }
        for (String pair : rawQuery.split("[&;]")) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (rawName.isEmpty()) {
                continue;
            }
            names.add(decodeQueryPart(rawName));
        }
        return names;
    }

    /**
     * @return the names of the query parameters of {@code upstreamQuery} with a value the caller
     *         did not send in {@code incomingQuery}: the engine added or replaced them, an
     *         {@code apikey}, an adapter input or a binding, so they can carry a secret. Values
     *         are compared, not only names, so that a caller parameter of the same name, such as
     *         {@code ?api_key=ignored}, never unprotects the adapter's own value.
     */
    static Set<String> injectedQueryNames(String upstreamQuery, String incomingQuery) {
        Set<Map.Entry<String, String>> sent = queryPairs(incomingQuery);
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> pair : queryPairs(upstreamQuery)) {
            if (!sent.contains(pair)) {
                names.add(pair.getKey());
            }
        }
        return names;
    }

    /**
     * @return whether the query of a location can hand the caller a protected parameter: one the
     *         engine added to the upstream request ({@code exposure.injectedQueryNames()}), or,
     *         when the consumed adapter signs with {@code awsSigV4}, an {@code X-Amz-*} one.
     *         Names match whatever their case, at the top level or in a URL or query nested in
     *         a value, even percent-encoded ({@code ?next=%2Fa%3Fapi_key%3D...}). A query with a
     *         value still encoded after {@value #MAX_NESTED_DECODINGS} decodings cannot be
     *         checked, so it counts as one when there is something to protect.
     */
    static boolean exposesProtectedParameter(String rawQuery, Exposure exposure,
            String incomingQuery) {
        if (rawQuery == null) {
            return false;
        }
        Set<String> injected = new HashSet<>();
        for (String name : exposure.injectedQueryNames()) {
            injected.add(name.toLowerCase(Locale.ROOT));
        }
        if (injected.isEmpty() && !exposure.signed()) {
            return false;
        }
        for (String name : queryNames(rawQuery)) {
            if (injected.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        Set<String> nested = nestedQueryNames(rawQuery);
        if (nested == null || !Collections.disjoint(nested, injected)) {
            return true;
        }
        return exposure.signed() && (carriesAmzQuery(rawQuery, incomingQuery)
                || nested.stream().anyMatch(name -> name.startsWith(AMZ_QUERY_PREFIX)));
    }

    /**
     * @return the names, in lower case, of the parameters of a URL or query nested in the values
     *         of {@code rawQuery}, as written and after each percent-decoding, or {@code null}
     *         when a value still changes after {@value #MAX_NESTED_DECODINGS} decodings
     */
    static Set<String> nestedQueryNames(String rawQuery) {
        Set<String> names = new HashSet<>();
        if (rawQuery == null) {
            return names;
        }
        for (String pair : rawQuery.split("[&;]")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String text = pair.substring(equals + 1);
            for (int decodings = 0;; decodings++) {
                for (String part : text.split("[?&;#]")) {
                    int partEquals = part.indexOf('=');
                    if (partEquals > 0) {
                        names.add(part.substring(0, partEquals).toLowerCase(Locale.ROOT));
                    }
                }
                String decoded = decodeQueryPart(text);
                if (decoded.equals(text)) {
                    break;
                }
                if (decodings == MAX_NESTED_DECODINGS) {
                    return null;
                }
                text = decoded;
            }
        }
        return names;
    }

    /**
     * @return whether {@code rawQuery} has an {@code X-Amz-*} parameter, whatever its case, with a
     *         value the caller did not send in {@code incomingQuery}
     */
    static boolean carriesAmzQuery(String rawQuery, String incomingQuery) {
        Set<Map.Entry<String, String>> sent = queryPairs(incomingQuery);
        for (Map.Entry<String, String> pair : queryPairs(rawQuery)) {
            if (pair.getKey().toLowerCase(Locale.ROOT).startsWith(AMZ_QUERY_PREFIX)
                    && !sent.contains(pair)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the name and value of each parameter of {@code rawQuery}, percent-decoded (a
     *         malformed escape is kept as written), or an empty set when it is {@code null}
     */
    private static Set<Map.Entry<String, String>> queryPairs(String rawQuery) {
        Set<Map.Entry<String, String>> pairs = new HashSet<>();
        if (rawQuery == null) {
            return pairs;
        }
        for (String pair : rawQuery.split("[&;]")) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (rawName.isEmpty()) {
                continue;
            }
            pairs.add(Map.entry(decodeQueryPart(rawName),
                    equals < 0 ? "" : decodeQueryPart(pair.substring(equals + 1))));
        }
        return pairs;
    }

    /**
     * Decodes the percent-escapes of {@code raw}, keeping {@code +} as a literal plus sign: the
     * engine encodes a query as RFC 3986 does, a space as {@code %20}
     * ({@code Resolver.appendQueryParameter}), so {@code a+b} and {@code a%20b} differ.
     */
    private static String decodeQueryPart(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    /**
     * @return the static text after {@code {{path}}} in the path template of the exposed resource
     *         ({@code /file} for {@code /proxy/{{path}}/file}), or an empty string when there is
     *         none or the template is unknown. The text before it needs no template: it is read
     *         from the incoming path, whatever separator it ends with ({@code /proxy/item-},
     *         {@code /proxy//}).
     */
    static String pathTail(String resourcePath) {
        if (resourcePath == null) {
            return "";
        }
        int start = resourcePath.indexOf("{{path}}");
        return start < 0 ? "" : resourcePath.substring(start + "{{path}}".length());
    }

    /**
     * @return the path of the incoming request up to its forwarded {@code {{path}}} part, the
     *         separator before it included ({@code /proxy/} for {@code /proxy/items}), or
     *         {@code null} when the path does not end with that part
     */
    static String exposedPath(Request incoming) {
        return exposedPath(incoming, "");
    }

    /**
     * Same, when the resource path has {@code tail} after {@code {{path}}}: the path must end
     * with the forwarded part followed by {@code tail}, and both are removed. The path of a
     * resource without {@code {{path}}} is returned as routed, a trailing slash included.
     *
     * @return the exposed prefix, or {@code null} when the path does not have that form, or
     *         when {@code tail} holds another variable, which cannot be rebuilt
     */
    static String exposedPath(Request incoming, String tail) {
        String path = incoming.getResourceRef().getPath();
        if (path == null) {
            return null;
        }
        if (!incoming.getAttributes().containsKey("path")) {
            return path;
        }
        if (tail.contains("{{")) {
            return null;
        }
        Object forwarded = incoming.getAttributes().get("path");
        String forwardedPath = forwarded == null ? "" : forwarded.toString();
        String end = forwardedPath + tail;
        if (!path.endsWith(end)) {
            return null;
        }
        String prefix = path.substring(0, path.length() - end.length());
        // An empty forwarded path leaves no separator to read: the forward target adds "/"
        return forwardedPath.isEmpty() ? stripTrailingSlashes(prefix) + "/" : prefix;
    }

    /**
     * @return the host of {@code uri}, read from its raw authority (without user info and port),
     *         or {@code null} when it has none. {@link URI#getHost()} returns {@code null} for a
     *         host name with an underscore, which {@link URI} parses as a registry-based
     *         authority.
     */
    static String host(URI uri) {
        String hostPort = hostPort(uri);
        if (hostPort == null) {
            return null;
        }
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            return end < 0 ? null : hostPort.substring(0, end + 1);
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        return host.isEmpty() ? null : host;
    }

    /**
     * @return the port of {@code uri}, read from its raw authority, else the default port of its
     *         scheme (443 for {@code https}, 80 otherwise), or -1 when the port is not a number
     */
    static int port(URI uri) {
        String hostPort = hostPort(uri);
        if (hostPort != null) {
            int colon = hostPort.lastIndexOf(':');
            if (colon > hostPort.lastIndexOf(']') && colon < hostPort.length() - 1) {
                try {
                    return Integer.parseInt(hostPort.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String hostPort(URI uri) {
        String authority = uri.getRawAuthority();
        return authority == null ? null : authority.substring(authority.lastIndexOf('@') + 1);
    }

    private static String stripTrailingSlashes(String path) {
        String result = path == null ? "" : path;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static URI parse(String uri) {
        try {
            return uri == null ? null : new URI(uri);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * @return a reference Restlet writes exactly as given, or {@code null} when {@code value} is
     *         {@code null}. Restlet 3.0.0-m1 writes {@code Location} and {@code Content-Location}
     *         from {@link Reference#getTargetRef()}, which throws for a relative reference with no
     *         base; a path-absolute location needs no base.
     */
    static Reference verbatim(String value) {
        return value == null ? null : new VerbatimReference(value);
    }

    /** A reference that is its own target, so a relative value is written as is. */
    static final class VerbatimReference extends Reference {
        VerbatimReference(String value) {
            super(value);
        }

        @Override
        public Reference getTargetRef() {
            return this;
        }
    }
}
